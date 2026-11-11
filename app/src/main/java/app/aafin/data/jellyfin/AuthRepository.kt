package app.aafin.data.jellyfin

import app.aafin.data.accounts.Account
import app.aafin.data.accounts.AccountStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** Progress of a Quick Connect sign-in; see [AuthRepository.quickConnect]. */
sealed interface QuickConnectState {
    /** Asking the server whether Quick Connect is on and requesting a code. */
    data object Starting : QuickConnectState

    /** Show [code] to the user; they approve it in another Jellyfin client (Settings → Quick Connect). */
    data class AwaitingApproval(val code: String) : QuickConnectState

    /** The code was approved; exchanging it for an access token. */
    data object Authenticating : QuickConnectState

    /** Terminal: signed in. [account] has been saved and made active. */
    data class Success(val account: Account) : QuickConnectState

    /** Terminal: the code was not approved in time. Start again for a new code. */
    data object TimedOut : QuickConnectState

    /** Terminal: something went wrong; show `error.userMessage`. */
    data class Failed(val error: JellyfinException) : QuickConnectState
}

/**
 * Finding a server and signing in. Successful sign-ins are saved to [AccountStore] and made active.
 */
class AuthRepository(
    private val gateway: AuthGateway,
    private val accountStore: AccountStore,
) {

    /**
     * Normalises [input] (see [ServerUrl]) and checks each candidate with `/System/Info/Public`.
     *
     * @return the first server that answers.
     * @throws JellyfinException the failure of the preferred (first) candidate if none answer.
     */
    suspend fun findServer(input: String): ServerInfo {
        var firstError: JellyfinException? = null
        for (candidate in ServerUrl.candidates(input)) {
            try {
                return gateway.publicInfo(candidate)
            } catch (e: JellyfinException) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: JellyfinException(JellyfinException.Kind.INVALID_URL)
    }

    /**
     * Quick Connect as a cold flow: collecting it starts the attempt, cancelling collection abandons it.
     *
     * Emits [QuickConnectState.Starting] → [QuickConnectState.AwaitingApproval] → (polls every
     * [pollIntervalMs]) → [QuickConnectState.Authenticating] → [QuickConnectState.Success], or ends
     * early with [QuickConnectState.TimedOut] / [QuickConnectState.Failed]. The flow completes after
     * a terminal state. Brief network drop-outs while polling are tolerated.
     */
    fun quickConnect(
        server: ServerInfo,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Flow<QuickConnectState> = flow {
        emit(QuickConnectState.Starting)

        val terminal: QuickConnectState = try {
            if (!gateway.quickConnectEnabled(server.url)) {
                throw JellyfinException(JellyfinException.Kind.QUICK_CONNECT_DISABLED)
            }
            val ticket = gateway.quickConnectInitiate(server.url)
            emit(QuickConnectState.AwaitingApproval(ticket.code))

            val approved = withTimeoutOrNull(timeoutMs) {
                while (!pollApproved(server.url, ticket.secret)) delay(pollIntervalMs)
                true
            }
            if (approved == null) {
                QuickConnectState.TimedOut
            } else {
                emit(QuickConnectState.Authenticating)
                val result = gateway.authenticateWithQuickConnect(server.url, ticket.secret)
                QuickConnectState.Success(save(server, result))
            }
        } catch (e: JellyfinException) {
            QuickConnectState.Failed(e)
        }
        emit(terminal)
    }

    /** Username/password sign-in. The password is sent once and never stored. */
    suspend fun signInWithPassword(server: ServerInfo, username: String, password: String): Account =
        save(server, gateway.authenticateByName(server.url, username.trim(), password))

    /** One poll of the Quick Connect state. A network blip counts as "not approved yet". */
    private suspend fun pollApproved(baseUrl: String, secret: String): Boolean = try {
        gateway.quickConnectApproved(baseUrl, secret)
    } catch (e: JellyfinException) {
        if (e.kind != JellyfinException.Kind.NETWORK) throw e
        false
    }

    private suspend fun save(server: ServerInfo, result: AuthResult): Account {
        val account = Account(
            id = Account.idFor(result.serverId.ifBlank { server.id }, result.userId),
            serverUrl = server.url,
            serverName = server.name,
            userId = result.userId,
            userName = result.userName,
            accessToken = result.accessToken,
        )
        accountStore.upsert(account, makeActive = true)
        return account
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 4_000L
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
    }
}
