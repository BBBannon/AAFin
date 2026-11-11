package app.aafin.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {

    @Test
    fun `newer versions are spotted part by part`() {
        assertTrue(isNewerVersion("1.9", "1.8"))
        assertTrue(isNewerVersion("1.10", "1.9"))
        assertTrue(isNewerVersion("2.0", "1.12"))
        assertTrue(isNewerVersion("v1.8.1", "1.8"))
    }

    @Test
    fun `same or older versions are not updates`() {
        assertFalse(isNewerVersion("1.8", "1.8"))
        assertFalse(isNewerVersion("1.8.0", "1.8"))
        assertFalse(isNewerVersion("1.7", "1.8"))
        assertFalse(isNewerVersion("1.9", "1.10"))
    }

    @Test
    fun `suffixes are ignored and nonsense is never an update`() {
        assertFalse(isNewerVersion("1.8", "1.8-debug"))
        assertFalse(isNewerVersion("latest", "1.8"))
        assertTrue(isNewerVersion("1.8", "unknown"))
    }

    @Test
    fun `reads the APK link from GitHub's reply`() {
        val release = parseLatestRelease(
            """{"tag_name":"v1.9","html_url":"https://github.com/o/r/releases/tag/v1.9","draft":false,"prerelease":false,
               "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                         {"name":"AAFin-v1.9.apk","browser_download_url":"https://x/AAFin-v1.9.apk","size":1}],
               "extra":"ignored"}""",
        )
        assertEquals(Release("1.9", "https://x/AAFin-v1.9.apk", "https://github.com/o/r/releases/tag/v1.9"), release)
    }

    @Test
    fun `drafts, pre-releases, missing APKs and bad replies give nothing`() {
        val apk = """[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]"""
        assertNull(parseLatestRelease("""{"tag_name":"v2","html_url":"h","draft":true,"assets":$apk}"""))
        assertNull(parseLatestRelease("""{"tag_name":"v2","html_url":"h","prerelease":true,"assets":$apk}"""))
        assertNull(parseLatestRelease("""{"tag_name":"v2","html_url":"h","assets":[]}"""))
        assertNull(parseLatestRelease("not json"))
    }
}
