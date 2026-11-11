package app.aafin.phone

import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadRepo
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.jellyfin.ServerInfo
import app.aafin.data.jellyfin.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLogicTest {

    private val twoHours = 2 * 60 * 60_000L

    // --- Size estimates ----------------------------------------------------------------------

    @Test
    fun `720p estimate is the 4 Mbps cap for the running time`() {
        // 4,000,000 bit/s × 7,200 s ÷ 8 = 3.6 GB
        assertEquals(3_600_000_000L, DownloadChoice.estimate720pBytes(twoHours, sourceBitrate = null))
        assertEquals(3_600_000_000L, DownloadChoice.estimate720pBytes(twoHours, sourceBitrate = 25_000_000))
    }

    @Test
    fun `720p estimate uses the file's own bitrate when that is lower than the cap`() {
        assertEquals(1_350_000_000L, DownloadChoice.estimate720pBytes(twoHours, sourceBitrate = 1_500_000))
    }

    @Test
    fun `720p estimate is unknown without a running time`() {
        assertNull(DownloadChoice.estimate720pBytes(null, 4_000_000))
        assertNull(DownloadChoice.estimate720pBytes(0, 4_000_000))
    }

    @Test
    fun `original estimate prefers the size the server reports`() {
        assertEquals(8_000_000_000L, DownloadChoice.estimateOriginalBytes(twoHours, SourceInfo(sizeBytes = 8_000_000_000L, bitrate = 1)))
    }

    @Test
    fun `original estimate falls back to bitrate times running time`() {
        // 10 Mbps × 7,200 s ÷ 8 = 9 GB
        assertEquals(9_000_000_000L, DownloadChoice.estimateOriginalBytes(twoHours, SourceInfo(sizeBytes = null, bitrate = 10_000_000)))
        assertNull(DownloadChoice.estimateOriginalBytes(null, SourceInfo(sizeBytes = null, bitrate = 10_000_000)))
        assertNull(DownloadChoice.estimateOriginalBytes(twoHours, SourceInfo(null, null)))
        assertNull(DownloadChoice.estimateOriginalBytes(twoHours, null))
    }

    // --- The Original / 720p choice ----------------------------------------------------------

    @Test
    fun `720p is the pre-selected quality`() {
        assertEquals(DownloadQuality.P720, DownloadChoice.DEFAULT)
    }

    @Test
    fun `both options are offered, Original first, with sizes when known`() {
        val film = fakeFilm(1, "Film")
        val options = DownloadChoice.options(listOf(film), mapOf(film.id to SourceInfo(12_000_000_000L, 13_000_000)))

        assertEquals(listOf(DownloadQuality.ORIGINAL, DownloadQuality.P720), options.map { it.quality })
        assertTrue(options.all { it.enabled })
        assertEquals(12_000_000_000L, options[0].sizeBytes)
        assertEquals(3_600_000_000L, options[1].sizeBytes)
        assertEquals(DownloadChoice.ORIGINAL_ALLOWED, options[0].description)
    }

    @Test
    fun `Original is switched off with an explanation when the account may not download`() {
        val film = fakeFilm(1, "Film", canDownload = false)
        val (original, p720) = DownloadChoice.options(listOf(film), emptyMap())

        assertFalse(original.enabled)
        assertEquals(DownloadChoice.ORIGINAL_NOT_ALLOWED, original.description)
        assertTrue(p720.enabled)
    }

    @Test
    fun `before the server answers, 720p already has a size and Original does not`() {
        val (original, p720) = DownloadChoice.options(listOf(fakeFilm(1, "Film")), emptyMap())

        assertNull(original.sizeBytes)
        assertEquals(3_600_000_000L, p720.sizeBytes)
        assertEquals("Checking size…", DownloadChoice.sizeLabel(original, checking = true))
        assertEquals("Size unknown", DownloadChoice.sizeLabel(original, checking = false))
        assertEquals("About 3.6 GB", DownloadChoice.sizeLabel(p720, checking = true))
    }

    @Test
    fun `a season's size is the sum of its episodes, or unknown if any episode is`() {
        val episodes = listOf(fakeEpisode(1, 1), fakeEpisode(2, 2), fakeEpisode(3, 3))
        val sources = episodes.associate { it.id to SourceInfo(1_000_000_000L, 5_000_000) }

        val (original, p720) = DownloadChoice.options(episodes, sources)
        assertEquals(3_000_000_000L, original.sizeBytes)
        assertEquals(3 * 900_000_000L, p720.sizeBytes) // 30 min at 4 Mbps each

        val (partial, _) = DownloadChoice.options(episodes, sources - episodes[1].id)
        assertNull(partial.sizeBytes)

        val (_, noRuntime) = DownloadChoice.options(episodes + fakeEpisode(4, 4, runtimeMs = null), sources)
        assertNull(noRuntime.sizeBytes)
    }

    @Test
    fun `a season where one episode may not be downloaded switches Original off`() {
        val (original, _) = DownloadChoice.options(listOf(fakeEpisode(1, 1), fakeEpisode(2, 2, canDownload = false)), emptyMap())
        assertFalse(original.enabled)
    }

    // --- Formatting --------------------------------------------------------------------------

    @Test
    fun `sizes are shown in KB, MB or GB`() {
        assertEquals("640 KB", Format.bytes(640_000))
        assertEquals("310 MB", Format.bytes(310_400_000))
        assertEquals("1.2 GB", Format.bytes(1_240_000_000))
        assertEquals("0 KB", Format.bytes(0))
    }

    @Test
    fun `running times are shown in hours and minutes`() {
        assertEquals("42 min", Format.duration(42 * 60_000L))
        assertEquals("1 h 42 min", Format.duration(102 * 60_000L))
        assertEquals("2 h", Format.duration(twoHours))
        assertEquals("1 min", Format.duration(10_000))
    }

    @Test
    fun `download status lines`() {
        val film = fakeFilm(1, "Film")
        assertEquals("Waiting to start", downloadStatusText(fakeDownload(film, DownloadStatus.QUEUED)))
        assertEquals(
            "Downloading · 42% · 310 MB",
            downloadStatusText(fakeDownload(film, DownloadStatus.DOWNLOADING, percent = 42.7f, bytesDownloaded = 310_000_000)),
        )
        assertEquals("Paused · 10%", downloadStatusText(fakeDownload(film, DownloadStatus.PAUSED, percent = 10f)))
        assertEquals("Downloaded · 1.2 GB", downloadStatusText(fakeDownload(film, DownloadStatus.COMPLETED, bytesDownloaded = 1_200_000_000)))
        assertTrue(downloadStatusText(fakeDownload(film, DownloadStatus.FAILED)).startsWith("Failed"))
        assertEquals("Downloading 42%", downloadBadgeText(fakeDownload(film, DownloadStatus.DOWNLOADING, percent = 42.7f)))
        assertEquals("Downloaded", downloadBadgeText(fakeDownload(film, DownloadStatus.COMPLETED)))
    }

    @Test
    fun `car keys are described in words`() {
        assertEquals("Recognised by model: dacia duster 2025", describeCarKey("model:dacia|duster|2025"))
        assertEquals("Recognised by screen: 1920 × 720, 160 dpi", describeCarKey("screen:1920x720@160"))
        assertEquals("The car didn't identify itself", describeCarKey("screen:unknown"))
    }

    @Test
    fun `entry subtitle skips what is unknown`() {
        assertEquals("2020 · 2 h", entrySubtitle(fakeFilm(1, "Film")))
        assertEquals("S1:E3 · 30 min", entrySubtitle(fakeEpisode(1, 3)))
        assertEquals("2020", entrySubtitle(fakeFilm(1, "Film", runtimeMs = null)))
    }

    // --- Server warnings ---------------------------------------------------------------------

    @Test
    fun `a supported server over HTTPS has no warnings - including Jellyfin 12`() {
        assertEquals(emptyList<String>(), serverWarnings(ServerInfo("https://jf.example.com", "Home", "id", "12.4.0")))
        assertEquals(emptyList<String>(), serverWarnings(ServerInfo("https://jf.example.com", "Home", "id", "10.11.5")))
    }

    @Test
    fun `old, unknown and unencrypted servers are warned about`() {
        assertTrue(serverWarnings(ServerInfo("https://jf.example.com", "Home", "id", "10.9.0")).single().contains("10.10 or newer"))
        assertTrue(serverWarnings(ServerInfo("https://jf.example.com", "Home", "id", null)).single().contains("didn't say"))
        assertTrue(serverWarnings(ServerInfo("https://jf.example.com", "Home", "id", "13.0.0")).single().contains("newer than"))
        val plain = serverWarnings(ServerInfo("http://192.168.1.10:8096", "Home", "id", "12.4.0"))
        assertTrue(plain.single().contains("not encrypted"))
    }

    // --- Tokens in download addresses --------------------------------------------------------

    @Test
    fun `a download address is stale only when it embeds a different token`() {
        val url = "https://jf.example.com/videos/abc/master.m3u8?MediaSourceId=abc&ApiKey=old-token&PlaySessionId=1"
        assertTrue(DownloadRepo.hasStaleToken(url, currentToken = "new-token"))
        assertFalse(DownloadRepo.hasStaleToken(url, currentToken = "old-token"))
        assertTrue(DownloadRepo.hasStaleToken("https://jf.example.com/videos/abc/master.m3u8?api_key=old-token", "new-token"))
        // Header-authenticated addresses (Original downloads) carry no token and never go stale.
        assertFalse(DownloadRepo.hasStaleToken("https://jf.example.com/Items/abc/Download", "new-token"))
        assertFalse(DownloadRepo.hasStaleToken("not a url", "new-token"))
    }
}
