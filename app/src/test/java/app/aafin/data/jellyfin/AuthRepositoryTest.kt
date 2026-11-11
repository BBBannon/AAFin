package app.aafin.data.jellyfin

import app.aafin.data.accounts.AccountStore
import app.aafin.data.accounts.AccountsState
import app.aafin.testing.InMemoryDataStore
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    private val server = ServerInfo("https://jf.example.com", "Home", "server-1", "10.11.5")

    /** Scriptable stand-in for the Jellyfin sign-in endpoints. */
    private class FakeGateway : AuthGateway {
        var reachable: Set<String> = setOf("https://jf.example.com")
        var enabled = true
        var approveAfterPolls = 3
        var pollErrors: MutableList<JellyfinException> = mutableListOf()
        var authError: JellyfinException? = null
        var polls = 0
        var initiated = 0
        val infoRequests = mutableListOf<String>()

        override suspend fun publicInfo(baseUrl: String): ServerInfo {
            infoRequests += baseUrl
            if (baseUrl !in reachable) throw JellyfinException(JellyfinException.Kind.NETWORK)
            return ServerInfo(baseUrl, "Home", "server-1", "10.11.5")
        }

        override suspend fun quickConnectEnabled(baseUrl: String) = enabled

        override suspend fun quickConnectInitiate(baseUrl: String): QuickConnectTicket {
            initiated++
            return QuickConnectTicket(secret = "s3cret", code = "123456")
        }

        override suspend fun quickConnectApproved(baseUrl: String, secret: String): Boolean {
            check(secret == "s3cret")
            polls++
            pollErrors.removeFirstOrNull()?.let { throw it }
            return polls >= approveAfterPolls
        }

        override suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String): AuthResult {
            authError?.let { throw it }
            return AuthResult(userId = "user-1", userName = "ben", accessToken = "token-qc", serverId = "server-1")
        }

        override suspend fun authenticateByName(baseUrl: String, username: String, password: String): AuthResult {
            if (password != "correct") throw JellyfinException(JellyfinException.Kind.UNAUTHORISED)
            return AuthResult(userId = "user-1", userName = username, accessToken = "token-pw", serverId = "server-1")
        }
    }

    private val gateway = FakeGateway()
    private val accounts = AccountStore(InMemoryDataStore(AccountsState()))
    private val auth = AuthRepository(gateway, accounts)

    // --- Quick Connect state machine -------------------------------------------------------

    @Test
    fun `happy path shows the code, polls until approved, then signs in`() = runTest {
        val states = auth.quickConnect(server, pollIntervalMs = 4_000).toList()

        assertEquals(QuickConnectState.Starting, states[0])
        assertEquals(QuickConnectState.AwaitingApproval("123456"), states[1])
        assertEquals(QuickConnectState.Authenticating, states[2])
        val success = states[3] as QuickConnectState.Success
        assertEquals(4, states.size)

        assertEquals(3, gateway.polls)
        assertEquals(8_000L, currentTime) // approved on the third poll: two waits of 4 s
        assertEquals("server-1:user-1", success.account.id)
        assertEquals("token-qc", success.account.accessToken)
        assertEquals("https://jf.example.com", success.account.serverUrl)
        assertEquals(success.account, accounts.active())
    }

    @Test
    fun `disabled on the server fails without requesting a code`() = runTest {
        gateway.enabled = false

        val states = auth.quickConnect(server).toList()

        assertEquals(QuickConnectState.Starting, states[0])
        val failed = states[1] as QuickConnectState.Failed
        assertEquals(JellyfinException.Kind.QUICK_CONNECT_DISABLED, failed.error.kind)
        assertEquals(2, states.size)
        assertEquals(0, gateway.initiated)
        assertNull(accounts.active())
    }

    @Test
    fun `times out when the code is never approved`() = runTest {
        gateway.approveAfterPolls = Int.MAX_VALUE

        val states = auth.quickConnect(server, pollIntervalMs = 4_000, timeoutMs = 60_000).toList()

        assertEquals(QuickConnectState.TimedOut, states.last())
        assertEquals(3, states.size) // Starting, AwaitingApproval, TimedOut
        assertEquals(60_000L, currentTime)
        assertEquals(15, gateway.polls) // at 0 s, 4 s … 56 s
        assertNull(accounts.active())
    }

    @Test
    fun `cancelling collection stops polling`() = runTest {
        gateway.approveAfterPolls = Int.MAX_VALUE

        auth.quickConnect(server, pollIntervalMs = 4_000).test {
            assertEquals(QuickConnectState.Starting, awaitItem())
            assertEquals(QuickConnectState.AwaitingApproval("123456"), awaitItem())
            advanceTimeBy(9_000)
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        val pollsAtCancel = gateway.polls
        assertEquals(3, pollsAtCancel) // 0 s, 4 s, 8 s

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(pollsAtCancel, gateway.polls)
        assertNull(accounts.active())
    }

    @Test
    fun `network blips while polling are tolerated`() = runTest {
        gateway.pollErrors = mutableListOf(
            JellyfinException(JellyfinException.Kind.NETWORK),
            JellyfinException(JellyfinException.Kind.NETWORK),
        )

        val states = auth.quickConnect(server).toList()

        assertTrue(states.last() is QuickConnectState.Success)
        assertEquals(3, gateway.polls)
    }

    @Test
    fun `a server error while polling fails the attempt`() = runTest {
        gateway.pollErrors = mutableListOf(JellyfinException(JellyfinException.Kind.NOT_FOUND))

        val states = auth.quickConnect(server).toList()

        val failed = states.last() as QuickConnectState.Failed
        assertEquals(JellyfinException.Kind.NOT_FOUND, failed.error.kind)
        assertEquals(1, gateway.polls)
    }

    @Test
    fun `failure exchanging the approved code is reported`() = runTest {
        gateway.authError = JellyfinException(JellyfinException.Kind.UNAUTHORISED)

        val states = auth.quickConnect(server).toList()

        assertEquals(QuickConnectState.Authenticating, states[states.size - 2])
        assertEquals(JellyfinException.Kind.UNAUTHORISED, (states.last() as QuickConnectState.Failed).error.kind)
        assertNull(accounts.active())
    }

    // --- Password sign-in and server discovery ---------------------------------------------

    @Test
    fun `password sign-in saves and activates the account`() = runTest {
        val account = auth.signInWithPassword(server, "  ben ", "correct")

        assertEquals("ben", account.userName)
        assertEquals("token-pw", account.accessToken)
        assertEquals(account, accounts.active())
        assertEquals(listOf(account), accounts.accounts.first())
    }

    @Test
    fun `wrong password is rejected and nothing is saved`() = runTest {
        val error = runCatching { auth.signInWithPassword(server, "ben", "wrong") }.exceptionOrNull() as JellyfinException
        assertEquals(JellyfinException.Kind.UNAUTHORISED, error.kind)
        assertNull(accounts.active())
    }

    @Test
    fun `signing in again as the same user replaces the stored account`() = runTest {
        auth.signInWithPassword(server, "ben", "correct")
        auth.quickConnect(server).toList()

        val stored = accounts.all()
        assertEquals(1, stored.size)
        assertEquals("token-qc", stored.single().accessToken)
    }

    @Test
    fun `findServer normalises input and validates it`() = runTest {
        val found = auth.findServer("  jf.example.com/ ")
        assertEquals("https://jf.example.com", found.url)
        assertEquals(listOf("https://jf.example.com"), gateway.infoRequests)
    }

    @Test
    fun `findServer falls back to http for a local address only`() = runTest {
        gateway.reachable = setOf("http://192.168.1.10:8096")
        val found = auth.findServer("192.168.1.10:8096")
        assertEquals("http://192.168.1.10:8096", found.url)
        assertEquals(listOf("https://192.168.1.10:8096", "http://192.168.1.10:8096"), gateway.infoRequests)
    }

    @Test
    fun `findServer reports the failure when nothing answers`() = runTest {
        gateway.reachable = emptySet()
        val error = runCatching { auth.findServer("jf.example.com") }.exceptionOrNull() as JellyfinException
        assertEquals(JellyfinException.Kind.NETWORK, error.kind)

        val invalid = assertThrows(JellyfinException::class.java) { ServerUrl.candidates("not a url") }
        assertEquals(JellyfinException.Kind.INVALID_URL, invalid.kind)
    }
}
