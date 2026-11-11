package app.aafin.data

import app.aafin.data.accounts.Account
import app.aafin.data.accounts.AccountStore
import app.aafin.data.accounts.AccountsState
import app.aafin.data.downloads.DownloadMetadata
import app.aafin.data.downloads.DownloadMetadataStore
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadedSubtitle
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.StreamTrack
import app.aafin.data.profiles.CarProfile
import app.aafin.data.profiles.CarProfileStore
import app.aafin.data.profiles.CarProfilesState
import app.aafin.testing.testJsonDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Persistence tests. These use real files, and DataStore with the app's JSON serializer, in a temporary folder. */
class StoresTest {

    @get:Rule
    val temp = TemporaryFolder()

    // --- Car profiles ----------------------------------------------------------------------

    private fun profileStore(file: File = File(temp.root, "car-profiles.json")) =
        CarProfileStore(testJsonDataStore(file, CarProfilesState.serializer(), CarProfilesState()))

    @Test
    fun `getOrCreate makes a default profile for a new car and returns it thereafter`() = runBlocking {
        val store = profileStore()

        val created = store.getOrCreate("model:dacia|duster|2025", "Dacia Duster")
        assertEquals(CarProfile("model:dacia|duster|2025", "Dacia Duster", QualityTier.P720_4M), created)

        store.setQuality(created.key, QualityTier.P1080_8M)
        store.rename(created.key, "  Duster  ")

        val again = store.getOrCreate("model:dacia|duster|2025", "Ignored name")
        assertEquals(CarProfile("model:dacia|duster|2025", "Duster", QualityTier.P1080_8M), again)
        assertEquals(1, store.profiles.first().size)
    }

    @Test
    fun `each car gets its own profile and new cars use the default quality setting`() = runBlocking {
        val store = profileStore()
        store.getOrCreate("screen:1920x720@160", "Car 1")
        store.setDefaultQuality(QualityTier.P480_2M)
        val second = store.getOrCreate("screen:800x480@160", "")

        assertEquals(QualityTier.P480_2M, second.quality)
        assertEquals("My car", second.name)
        assertEquals(listOf("screen:1920x720@160", "screen:800x480@160"), store.profiles.first().map { it.key })
        assertEquals(QualityTier.P720_4M, store.get("screen:1920x720@160")?.quality)

        store.remove("screen:1920x720@160")
        assertNull(store.get("screen:1920x720@160"))
    }

    @Test
    fun `concurrent getOrCreate for the same car creates one profile`() = runBlocking {
        val store = profileStore()
        val results = List(20) { async { store.getOrCreate("screen:1920x720@160", "Car") } }.awaitAll()

        assertEquals(1, results.toSet().size)
        assertEquals(1, store.profiles.first().size)
    }

    @Test
    fun `profiles survive being reloaded from disk`() {
        val file = File(temp.root, "persisted.json")
        runBlocking {
            // Own scope, so this DataStore has let go of the file before the next one opens it.
            val job = Job()
            val first = CarProfileStore(
                testJsonDataStore(file, CarProfilesState.serializer(), CarProfilesState(), CoroutineScope(Dispatchers.IO + job)),
            )
            first.getOrCreate("model:skoda|octavia|2019", "Octavia")
            first.setQuality("model:skoda|octavia|2019", QualityTier.ORIGINAL)
            job.cancelAndJoin()
        }
        assertTrue(file.readText().contains("\"ORIGINAL\""))

        runBlocking {
            val reloaded = profileStore(file)
            assertEquals(CarProfile("model:skoda|octavia|2019", "Octavia", QualityTier.ORIGINAL), reloaded.get("model:skoda|octavia|2019"))
        }
    }

    @Test
    fun `car keys prefer the model and fall back to the screen`() {
        assertEquals("model:dacia|duster|2025", CarProfileStore.keyForModel("Dacia", " Duster ", 2025))
        assertEquals("model:dacia", CarProfileStore.keyForModel("Dacia", null, null))
        assertNull(CarProfileStore.keyForModel(null, "  ", 2025))
        assertEquals("screen:1920x720@160", CarProfileStore.keyForScreen(1920, 720, 160))
    }

    // --- Accounts --------------------------------------------------------------------------

    private fun account(n: Int) = Account("server:$n", "https://jf.example.com", "Home", "user$n", "User $n", "token$n")

    @Test
    fun `device id is generated once and then stable`() = runBlocking {
        val file = File(temp.root, "accounts.json")
        val store = AccountStore(testJsonDataStore(file, AccountsState.serializer(), AccountsState()))

        val id = store.deviceId()
        assertTrue(id.isNotBlank())
        assertEquals(id, store.deviceId())
        assertTrue(file.readText().contains(id))
    }

    @Test
    fun `accounts can be added, switched and removed`() = runBlocking {
        val store = AccountStore(testJsonDataStore(File(temp.root, "accounts2.json"), AccountsState.serializer(), AccountsState()))
        assertNull(store.active())

        store.upsert(account(1))
        store.upsert(account(2), makeActive = false)
        assertEquals(account(1), store.active())

        store.setActive("server:2")
        assertEquals(account(2), store.activeAccount.first())

        store.upsert(account(2).copy(accessToken = "fresh"), makeActive = false)
        assertEquals(2, store.all().size)
        assertEquals("fresh", store.get("server:2")?.accessToken)

        store.remove("server:2")
        assertEquals(account(1), store.active())
        store.remove("server:1")
        assertNull(store.active())

        val unknown = runCatching { store.setActive("nope") }.exceptionOrNull()
        assertTrue(unknown is IllegalArgumentException)
    }

    @Test
    fun `account toString hides the token`() {
        assertFalse(account(1).toString().contains("token1"))
    }

    // --- Download metadata -----------------------------------------------------------------

    private val itemId = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun metadata(id: String = itemId, createdAt: Long = 1_000) = DownloadMetadata(
        itemId = id,
        accountId = "server:1",
        serverName = "Home",
        entry = MediaEntry(
            id = id,
            name = "The One with the Tests",
            kind = ItemKind.EPISODE,
            overview = "Unicode survives: café — “quotes” – 日本語",
            productionYear = 2024,
            runtimeMs = 1_320_000,
            resumePositionMs = 60_000,
            seriesId = "11111111-2222-3333-4444-555555555555",
            seriesName = "Testing Times",
            seasonName = "Season 2",
            indexNumber = 5,
            parentIndexNumber = 2,
            primaryImageTag = "tag",
            canDownload = true,
        ),
        quality = DownloadQuality.P720,
        mediaSourceId = "abc123",
        posterFileName = "poster.jpg",
        subtitles = listOf(DownloadedSubtitle(3, "subtitle-3.srt", "application/x-subrip", "eng", "English")),
        audioTracks = listOf(StreamTrack(1, "English - AAC", "eng", "aac", isDefault = true)),
        createdAtMs = createdAt,
        resumePositionMs = 60_000,
    )

    @Test
    fun `download metadata round-trips through json on disk`() {
        val store = DownloadMetadataStore(temp.newFolder("items"))
        val original = metadata()

        store.save(original)

        assertEquals(original, store.load(itemId))
        assertTrue(File(store.folder(itemId), "metadata.json").isFile)
        assertEquals("Testing Times · S2:E5 · The One with the Tests", store.load(itemId)!!.entry.displayTitle)
    }

    @Test
    fun `metadata json tolerates fields added by a later version`() {
        val json = AppJson.encodeToString(DownloadMetadata.serializer(), metadata())
        val withExtra = json.replaceFirst("{", "{\"addedLater\":42,")
        assertEquals(metadata(), AppJson.decodeFromString(DownloadMetadata.serializer(), withExtra))
    }

    @Test
    fun `saving again replaces, all lists oldest first, delete removes the folder`() {
        val store = DownloadMetadataStore(temp.newFolder("items2"))
        val other = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        store.save(metadata(other, createdAt = 2_000))
        store.save(metadata(createdAt = 1_000))
        store.save(metadata(createdAt = 1_000).copy(resumePositionMs = 90_000))

        assertEquals(listOf(itemId, other), store.all().map { it.itemId })
        assertEquals(90_000L, store.load(itemId)?.resumePositionMs)
        assertTrue(store.sizeBytes() > 0)

        File(store.folder(itemId), "poster.jpg").writeBytes(ByteArray(10))
        store.delete(itemId)
        assertFalse(store.folder(itemId).exists())
        assertNull(store.load(itemId))
        assertEquals(listOf(other), store.all().map { it.itemId })
    }

    @Test
    fun `unreadable metadata is skipped and reported, not thrown`() {
        val root = temp.newFolder("items3")
        val reported = mutableListOf<String>()
        val store = DownloadMetadataStore(root) { id, _ -> reported += id }
        store.save(metadata())
        File(store.folder(itemId), "metadata.json").writeText("{ not json")

        assertNull(store.load(itemId))
        assertTrue(store.all().isEmpty())
        assertNotNull(reported.firstOrNull())
        assertEquals(itemId, reported.first())
    }

    @Test
    fun `item ids that are not guids are refused`() {
        val store = DownloadMetadataStore(temp.newFolder("items4"))
        assertThrows(IllegalArgumentException::class.java) { store.folder("../../etc") }
        assertThrows(IllegalArgumentException::class.java) { store.folder("") }
    }
}
