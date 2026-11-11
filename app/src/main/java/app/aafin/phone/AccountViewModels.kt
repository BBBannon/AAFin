package app.aafin.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aafin.data.accounts.Account
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.QuickConnectState
import app.aafin.data.jellyfin.ServerInfo
import app.aafin.data.update.Release
import app.aafin.data.update.isNewerVersion
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** What the whole phone app hangs off: first-run state and the accounts. */
data class AppUiState(
    /** True until the saved settings and accounts have been read. */
    val loading: Boolean = true,
    val setupSeen: Boolean = false,
    val accounts: List<Account> = emptyList(),
    /** Null = signed out. */
    val active: Account? = null,
)

class AppViewModel(private val backend: PhoneBackend) : ViewModel() {

    val state: StateFlow<AppUiState> =
        combine(backend.settings.setupSeen, backend.accounts.state) { setupSeen, accounts ->
            AppUiState(loading = false, setupSeen = setupSeen, accounts = accounts.accounts, active = accounts.activeAccount)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off messages for a snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun finishSetup() {
        viewModelScope.launch { backend.settings.setSetupSeen() }
    }

    fun switchAccount(id: String) {
        viewModelScope.launch {
            // The account may have been removed a moment ago (from the car, say): nothing to switch to.
            if (backend.accounts.get(id) != null) backend.accounts.setActive(id)
        }
    }

    /** Removes the account from this phone. Its downloads stay and still play offline. */
    fun removeAccount(id: String) {
        viewModelScope.launch { backend.accounts.remove(id) }
    }

    /**
     * Call when the active account changes while the app is on screen. Signing in again can
     * change the access token; unfinished 720p downloads made with the old one are restarted
     * here rather than left to fail later.
     */
    fun checkDownloadsAfterSignIn() {
        viewModelScope.launch {
            var restarted = 0
            val error = failureMessage { restarted = backend.downloads.restartStale() }
            when {
                error != null -> _messages.send("Some unfinished downloads need starting again. Open Downloads and tap Retry. ($error)")
                restarted == 1 -> _messages.send("Restarted 1 unfinished download after signing in again.")
                restarted > 1 -> _messages.send("Restarted $restarted unfinished downloads after signing in again.")
            }
        }
    }

    private val _update = MutableStateFlow<UpdateUi>(UpdateUi.Checking)

    /** Whether a newer AAFin is on GitHub. Checked once when the app opens, and on request. */
    val update: StateFlow<UpdateUi> = _update.asStateFlow()

    private var checkingUpdates: Job? = null

    init {
        checkForUpdates()
    }

    fun checkForUpdates() {
        if (checkingUpdates?.isActive == true) return
        _update.value = UpdateUi.Checking
        checkingUpdates = viewModelScope.launch {
            _update.value = try {
                val latest = backend.updates.latest()
                if (latest != null && isNewerVersion(latest.version, backend.appVersion)) {
                    UpdateUi.Available(latest)
                } else {
                    UpdateUi.UpToDate
                }
            } catch (e: IOException) {
                UpdateUi.Failed
            }
        }
    }
}

/** The update check, as Settings and the banner show it. */
sealed interface UpdateUi {
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi

    /** Couldn't reach GitHub (no signal, say). Not shown as an error: it is only a check. */
    data object Failed : UpdateUi
    data class Available(val release: Release) : UpdateUi
}

enum class AddAccountStep { SERVER, METHOD, QUICK_CONNECT, PASSWORD }

/** Where a Quick Connect attempt has got to. */
sealed interface QuickConnectUi {
    data object Starting : QuickConnectUi

    /** Show [code]; the user types it into a Jellyfin app that is already signed in. */
    data class Code(val code: String) : QuickConnectUi
    data object Authenticating : QuickConnectUi
    data object TimedOut : QuickConnectUi

    /** @property offerPassword true when Quick Connect is switched off on the server. */
    data class Failed(val message: String, val offerPassword: Boolean) : QuickConnectUi
}

data class AddAccountUiState(
    val step: AddAccountStep = AddAccountStep.SERVER,
    val checkingServer: Boolean = false,
    val serverError: String? = null,
    /** The server that answered; set from [AddAccountStep.METHOD] onwards. */
    val server: ServerInfo? = null,
    val quickConnect: QuickConnectUi = QuickConnectUi.Starting,
    val signingIn: Boolean = false,
    val passwordError: String? = null,
)

/** Add an account: find the server, then Quick Connect or username and password. */
class AddAccountViewModel(private val backend: PhoneBackend) : ViewModel() {

    private val _state = MutableStateFlow(AddAccountUiState())
    val state: StateFlow<AddAccountUiState> = _state.asStateFlow()

    private val _signedIn = Channel<Account>(Channel.BUFFERED)

    /** Emits once per successful sign-in. The account is already saved and active. */
    val signedIn: Flow<Account> = _signedIn.receiveAsFlow()

    private var quickConnectJob: Job? = null

    fun checkServer(input: String) {
        if (_state.value.checkingServer) return
        _state.update { it.copy(checkingServer = true, serverError = null) }
        viewModelScope.launch {
            try {
                val server = backend.auth.findServer(input)
                _state.update { it.copy(checkingServer = false, server = server, step = AddAccountStep.METHOD) }
            } catch (e: JellyfinException) {
                _state.update { it.copy(checkingServer = false, serverError = e.userMessage) }
            }
        }
    }

    fun startQuickConnect() {
        val server = _state.value.server ?: return
        quickConnectJob?.cancel()
        _state.update { it.copy(step = AddAccountStep.QUICK_CONNECT, quickConnect = QuickConnectUi.Starting) }
        quickConnectJob = viewModelScope.launch {
            backend.auth.quickConnect(server).collect { progress ->
                when (progress) {
                    QuickConnectState.Starting -> show(QuickConnectUi.Starting)
                    is QuickConnectState.AwaitingApproval -> show(QuickConnectUi.Code(progress.code))
                    QuickConnectState.Authenticating -> show(QuickConnectUi.Authenticating)
                    QuickConnectState.TimedOut -> show(QuickConnectUi.TimedOut)
                    is QuickConnectState.Failed -> show(
                        QuickConnectUi.Failed(
                            message = progress.error.userMessage,
                            offerPassword = progress.error.kind == JellyfinException.Kind.QUICK_CONNECT_DISABLED,
                        ),
                    )
                    is QuickConnectState.Success -> finish(progress.account)
                }
            }
        }
    }

    /** Abandons the code (the server forgets it by itself) and goes back to the choice of method. */
    fun cancelQuickConnect() {
        quickConnectJob?.cancel()
        _state.update { it.copy(step = AddAccountStep.METHOD) }
    }

    fun usePassword() {
        quickConnectJob?.cancel()
        _state.update { it.copy(step = AddAccountStep.PASSWORD, passwordError = null) }
    }

    fun signIn(username: String, password: String) {
        val server = _state.value.server ?: return
        if (_state.value.signingIn) return
        if (username.isBlank()) {
            _state.update { it.copy(passwordError = "Enter your Jellyfin username.") }
            return
        }
        _state.update { it.copy(signingIn = true, passwordError = null) }
        viewModelScope.launch {
            try {
                finish(backend.auth.signInWithPassword(server, username, password))
            } catch (e: JellyfinException) {
                val message = if (e.kind == JellyfinException.Kind.UNAUTHORISED) "Wrong username or password." else e.userMessage
                _state.update { it.copy(signingIn = false, passwordError = message) }
            }
        }
    }

    /** Steps back one stage. Returns false at the first stage (the caller leaves the screen). */
    fun back(): Boolean = when (_state.value.step) {
        AddAccountStep.SERVER -> false
        AddAccountStep.METHOD -> {
            _state.update { it.copy(step = AddAccountStep.SERVER, server = null) }
            true
        }
        AddAccountStep.QUICK_CONNECT -> {
            cancelQuickConnect()
            true
        }
        AddAccountStep.PASSWORD -> {
            if (!_state.value.signingIn) _state.update { it.copy(step = AddAccountStep.METHOD) }
            true
        }
    }

    private fun show(quickConnect: QuickConnectUi) {
        _state.update { it.copy(quickConnect = quickConnect) }
    }

    /** Back to a blank form, ready for the next account, and tell the screen. */
    private suspend fun finish(account: Account) {
        _state.value = AddAccountUiState()
        _signedIn.send(account)
    }
}
