package app.aafin.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aafin.data.accounts.Account
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.playback.QualityTier
import app.aafin.data.profiles.CarProfile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageUi(val usedBytes: Long, val freeBytes: Long)

data class DownloadsUiState(
    /** Newest first. */
    val items: List<DownloadedItem> = emptyList(),
    /** Null until first measured. */
    val storage: StorageUi? = null,
)

/** The Downloads tab: live progress, pause/resume, retry, delete, storage. */
class DownloadsViewModel(private val backend: PhoneBackend) : ViewModel() {
    private val storage = MutableStateFlow<StorageUi?>(null)

    val state: StateFlow<DownloadsUiState> =
        combine(backend.downloads.downloads, storage) { items, storage -> DownloadsUiState(items.reversed(), storage) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        // Storage is measured whenever a download starts, finishes or goes, and every few
        // seconds while something is downloading.
        viewModelScope.launch {
            backend.downloads.downloads
                .map { items -> items.map { it.itemId to it.status } }
                .distinctUntilChanged()
                .collectLatest { statuses ->
                    do {
                        storage.value = StorageUi(backend.downloads.storageUsedBytes(), backend.downloads.freeSpaceBytes())
                        delay(STORAGE_REFRESH_MS)
                    } while (statuses.any { it.second == DownloadStatus.DOWNLOADING })
                }
        }
    }

    fun pause(itemId: String) = backend.downloads.pause(itemId)

    fun resume(itemId: String) = backend.downloads.resume(itemId)

    fun delete(itemId: String) {
        viewModelScope.launch {
            failureMessage { backend.downloads.delete(itemId) }?.let { _messages.send("Couldn't delete that download. $it") }
        }
    }

    /**
     * Starts a failed download again. The server is asked for a fresh address, so this also
     * recovers downloads broken by signing in again - but only as the account that made them.
     */
    fun retry(item: DownloadedItem) {
        viewModelScope.launch {
            val accounts = backend.accounts.state.first()
            val owner = accounts.accounts.firstOrNull { it.id == item.metadata.accountId }
            val problem = when {
                owner == null ->
                    "The account that downloaded this (${item.metadata.serverName}) has been removed. Sign in to it again, then retry."
                owner.id != accounts.activeAccountId ->
                    "Switch to ${owner.userName} on ${owner.serverName} to retry this download."
                else -> failureMessage { backend.downloads.retry(item.itemId) }
            }
            problem?.let { _messages.send(it) }
        }
    }

    private companion object {
        const val STORAGE_REFRESH_MS = 5_000L
    }
}

/** The Cars tab: one profile per car that has run AAFin. */
class CarsViewModel(private val backend: PhoneBackend) : ViewModel() {

    /** Most recently used first; cars never seen connecting (older profiles) last. */
    val profiles: StateFlow<List<CarProfile>?> = backend.carProfiles.profiles
        .map { profiles -> profiles.sortedByDescending { it.lastUsedAtMs ?: 0 } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun rename(key: String, name: String) {
        viewModelScope.launch { backend.carProfiles.rename(key, name) }
    }

    fun setQuality(key: String, quality: QualityTier) {
        viewModelScope.launch { backend.carProfiles.setQuality(key, quality) }
    }

    fun delete(key: String) {
        viewModelScope.launch { backend.carProfiles.remove(key) }
    }
}

/** What Settings shows about one account's server. */
data class AccountServerUi(
    val account: Account,
    /** "Jellyfin 12.4.0", "Checking…" or why it could not be read. */
    val serverVersion: String,
)

data class SettingsUiState(
    val defaultQuality: QualityTier = QualityTier.DEFAULT,
    val accounts: List<AccountServerUi> = emptyList(),
    val appVersion: String = "",
)

class SettingsViewModel(private val backend: PhoneBackend) : ViewModel() {
    /** Server address → version text, filled in as each server answers. */
    private val versions = MutableStateFlow<Map<String, String>>(emptyMap())

    val state: StateFlow<SettingsUiState> =
        combine(backend.carProfiles.defaultQuality, backend.accounts.accounts, versions) { quality, accounts, versions ->
            SettingsUiState(
                defaultQuality = quality,
                accounts = accounts.map { AccountServerUi(it, versions[it.serverUrl] ?: "Checking version…") },
                appVersion = backend.appVersion,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(appVersion = backend.appVersion))

    init {
        viewModelScope.launch {
            backend.accounts.accounts
                .map { accounts -> accounts.map { it.serverUrl }.distinct() }
                .distinctUntilChanged()
                .collectLatest { servers ->
                    for (server in servers) {
                        val text = try {
                            backend.auth.findServer(server).version?.let { "Jellyfin $it" } ?: "Version not reported"
                        } catch (e: JellyfinException) {
                            "Version unknown: ${e.userMessage}"
                        }
                        versions.update { it + (server to text) }
                    }
                }
        }
    }

    fun setDefaultQuality(quality: QualityTier) {
        viewModelScope.launch { backend.carProfiles.setDefaultQuality(quality) }
    }
}
