package app.aafin.phone

import app.aafin.data.accounts.Account
import app.aafin.data.accounts.AccountStore
import app.aafin.data.accounts.AccountsState
import app.aafin.data.downloads.DownloadMetadata
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.AuthGateway
import app.aafin.data.jellyfin.AuthRepository
import app.aafin.data.jellyfin.AuthResult
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.Page
import app.aafin.data.jellyfin.QuickConnectTicket
import app.aafin.data.jellyfin.ServerInfo
import app.aafin.data.jellyfin.SourceInfo
import app.aafin.data.profiles.CarProfileStore
import app.aafin.data.profiles.CarProfilesState
import app.aafin.testing.InMemoryDataStore
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Stand-ins for the data layer, shared by the phone UI's JVM tests and on-device Compose tests.
 * The account store, sign-in repository and car profile store are the real classes, running on
 * in-memory storage and a scripted gateway.
 */

/** Scriptable Jellyfin sign-in endpoints. */
class FakeAuthGateway : AuthGateway {
    /** Servers that answer, by base URL. */
    var servers: Map<String, ServerInfo> = mapOf(SERVER_URL to ServerInfo(SERVER_URL, "Home", "server-1", "12.4.0"))
    var quickConnectEnabled = true

    /** Flip to true to "approve the code in another Jellyfin app". */
    @Volatile
    var approved = false
    var codesIssued = 0
    var polls = 0

    /** Token handed out by the next successful sign-in. */
    var token = "token-1"

    override suspend fun publicInfo(baseUrl: String): ServerInfo =
        servers[baseUrl] ?: throw JellyfinException(JellyfinException.Kind.NETWORK)

    override suspend fun quickConnectEnabled(baseUrl: String) = quickConnectEnabled

    override suspend fun quickConnectInitiate(baseUrl: String): QuickConnectTicket {
        codesIssued++
        return QuickConnectTicket(secret = "secret-$codesIssued", code = CODE)
    }

    override suspend fun quickConnectApproved(baseUrl: String, secret: String): Boolean {
        polls++
        return approved
    }

    override suspend fun authenticateWithQuickConnect(baseUrl: String, secret: String) =
        AuthResult(userId = "user-ben", userName = "ben", accessToken = token, serverId = "server-1")

    override suspend fun authenticateByName(baseUrl: String, username: String, password: String): AuthResult {
        if (password != PASSWORD) throw JellyfinException(JellyfinException.Kind.UNAUTHORISED)
        return AuthResult(userId = "user-$username", userName = username, accessToken = token, serverId = "server-1")
    }

    companion object {
        const val SERVER_URL = "https://jellyfin.example.com"
        const val CODE = "123456"
        const val PASSWORD = "correct"
    }
}

/** An in-memory library. Set [failure] to make every call fail. */
class FakeLibrary : PhoneLibrary {
    var failure: JellyfinException? = null
    var libraryList = listOf(Library("lib-films", "Films", LibraryType.MOVIES, null), Library("lib-tv", "TV", LibraryType.TV_SHOWS, null))

    /** What a library or folder contains, by its id. */
    var contents: Map<String, List<MediaEntry>> = emptyMap()
    var seasonList: List<MediaEntry> = emptyList()

    /** Episodes by season id (null key = the whole series). */
    var episodeLists: Map<String?, List<MediaEntry>> = emptyMap()
    var sources: Map<String, SourceInfo> = emptyMap()
    var searchResults: List<MediaEntry> = emptyList()
    val searches = mutableListOf<String>()
    val pageRequests = mutableListOf<Pair<Int, Int>>()

    private fun <T> server(value: () -> T): T = failure?.let { throw it } ?: value()

    override suspend fun libraries() = server { libraryList }

    override suspend fun libraryItems(library: Library, startIndex: Int, limit: Int) = page(library.id, startIndex, limit)

    override suspend fun children(parentId: String, startIndex: Int, limit: Int) = page(parentId, startIndex, limit)

    private fun page(parentId: String, startIndex: Int, limit: Int): Page<MediaEntry> = server {
        pageRequests += startIndex to limit
        val all = contents[parentId].orEmpty()
        Page(all.drop(startIndex).take(limit), startIndex, all.size)
    }

    override suspend fun seasons(seriesId: String) = server { seasonList }

    override suspend fun episodes(seriesId: String, seasonId: String?) = server { episodeLists[seasonId].orEmpty() }

    override suspend fun item(itemId: String): MediaEntry = server {
        (contents.values.flatten() + episodeLists.values.flatten()).firstOrNull { it.id == itemId }
            ?: throw JellyfinException(JellyfinException.Kind.NOT_FOUND)
    }

    override suspend fun search(query: String, limit: Int) = server {
        searches += query
        searchResults.take(limit)
    }

    override suspend fun sourceInfo(itemId: String): SourceInfo = server {
        sources[itemId] ?: throw JellyfinException(JellyfinException.Kind.NOT_FOUND)
    }

    override suspend fun posterUrl(entry: MediaEntry, maxWidth: Int): String? = null

    override suspend fun libraryImageUrl(library: Library, maxWidth: Int): String? = null
}

/** Records what the screens ask of the downloads, and lets a test set what they show. */
class FakeDownloads : PhoneDownloads {
    override val downloads = MutableStateFlow<List<DownloadedItem>>(emptyList())

    val enqueued = mutableListOf<Pair<MediaEntry, DownloadQuality>>()
    val deleted = mutableListOf<String>()
    val paused = mutableListOf<String>()
    val resumed = mutableListOf<String>()
    val retried = mutableListOf<String>()

    /** Item ids whose [enqueue] or [retry] fails, and how. */
    var failures: Map<String, Exception> = emptyMap()

    /** What [restartStale] reports; set [restartStaleError] to make it fail instead. */
    var staleRestarted = 0
    var restartStaleError: Exception? = null
    var restartStaleCalls = 0
    var usedBytes = 0L
    var freeBytes = 64_000_000_000L

    override suspend fun enqueue(entry: MediaEntry, quality: DownloadQuality) {
        failures[entry.id]?.let { throw it }
        enqueued += entry to quality
        downloads.value += fakeDownload(entry, DownloadStatus.QUEUED, quality = quality)
    }

    override suspend fun delete(itemId: String) {
        deleted += itemId
        downloads.value = downloads.value.filterNot { it.itemId == itemId }
    }

    override fun pause(itemId: String) {
        paused += itemId
    }

    override fun resume(itemId: String) {
        resumed += itemId
    }

    override suspend fun retry(itemId: String) {
        failures[itemId]?.let { throw it }
        retried += itemId
    }

    override suspend fun restartStale(): Int {
        restartStaleCalls++
        restartStaleError?.let { throw it }
        return staleRestarted
    }

    override suspend fun storageUsedBytes() = usedBytes

    override fun freeSpaceBytes() = freeBytes
}

/** A complete fake data layer for the phone screens. */
class FakePhone(setupSeen: Boolean = true, accounts: AccountsState = AccountsState()) {
    val gateway = FakeAuthGateway()
    val library = FakeLibrary()
    val downloads = FakeDownloads()
    val accountStore = AccountStore(InMemoryDataStore(accounts))
    val carProfiles = CarProfileStore(InMemoryDataStore(CarProfilesState()))

    val backend = PhoneBackend(
        accounts = accountStore,
        auth = AuthRepository(gateway, accountStore),
        carProfiles = carProfiles,
        settings = PhoneSettingsStore(InMemoryDataStore(PhoneSettings(setupSeen = setupSeen))),
        library = library,
        downloads = downloads,
        appVersion = "1.0-test",
    )

    companion object {
        val ALICE = Account("server-1:user-alice", FakeAuthGateway.SERVER_URL, "Home", "user-alice", "alice", "token-alice")
        val BOB = Account("server-1:user-bob", FakeAuthGateway.SERVER_URL, "Home", "user-bob", "bob", "token-bob")

        /** Signed in as Alice. */
        fun signedIn(vararg others: Account) =
            FakePhone(accounts = AccountsState(deviceId = "device", accounts = listOf(ALICE) + others, activeAccountId = ALICE.id))
    }
}

/** Item ids must look like Jellyfin GUIDs; this makes one from a small number. */
fun fakeId(n: Int): String = "%032x".format(n)

fun fakeFilm(n: Int, name: String, runtimeMs: Long? = 2 * 60 * 60_000L, canDownload: Boolean = true) =
    MediaEntry(id = fakeId(n), name = name, kind = ItemKind.MOVIE, productionYear = 2020, runtimeMs = runtimeMs, canDownload = canDownload)

fun fakeEpisode(n: Int, number: Int, season: Int = 1, runtimeMs: Long? = 30 * 60_000L, canDownload: Boolean = true) = MediaEntry(
    id = fakeId(n),
    name = "Episode $number",
    kind = ItemKind.EPISODE,
    runtimeMs = runtimeMs,
    seriesId = fakeId(9000),
    seriesName = "The Show",
    seasonId = fakeId(9000 + season),
    indexNumber = number,
    parentIndexNumber = season,
    canDownload = canDownload,
)

fun fakeDownload(
    entry: MediaEntry,
    status: DownloadStatus,
    percent: Float = if (status == DownloadStatus.COMPLETED) 100f else 0f,
    bytesDownloaded: Long = 0,
    quality: DownloadQuality = DownloadQuality.P720,
    accountId: String = FakePhone.ALICE.id,
    playableOnThisPhone: Boolean = true,
    createdAtMs: Long = 0,
) = DownloadedItem(
    metadata = DownloadMetadata(
        itemId = entry.id,
        accountId = accountId,
        serverName = "Home",
        entry = entry,
        quality = quality,
        mediaSourceId = entry.id,
        playableOnThisPhone = playableOnThisPhone,
        createdAtMs = createdAtMs,
    ),
    status = status,
    percent = percent,
    bytesDownloaded = bytesDownloaded,
    posterFile = null,
)
