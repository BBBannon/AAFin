package app.aafin.data.jellyfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    @Test
    fun `explicit https url is kept and trailing slash removed`() {
        assertEquals(listOf("https://jellyfin.example.com"), ServerUrl.candidates("https://jellyfin.example.com/"))
    }

    @Test
    fun `public hostname without scheme is https only`() {
        assertEquals(listOf("https://jellyfin.example.com"), ServerUrl.candidates("jellyfin.example.com"))
    }

    @Test
    fun `surrounding spaces and upper case host are tidied`() {
        assertEquals("https://jellyfin.example.com", ServerUrl.normalise("  HTTPS://Jellyfin.Example.com  "))
    }

    @Test
    fun `base path is preserved`() {
        assertEquals("https://example.com/jellyfin", ServerUrl.normalise("example.com/jellyfin/"))
        assertEquals("https://example.com/media/jf", ServerUrl.normalise("https://example.com/media//jf//"))
    }

    @Test
    fun `non default port is preserved and default port dropped`() {
        assertEquals("https://example.com:8920", ServerUrl.normalise("example.com:8920"))
        assertEquals("https://example.com", ServerUrl.normalise("https://example.com:443/"))
    }

    @Test
    fun `url copied from the web client is reduced to the server base`() {
        assertEquals("https://example.com", ServerUrl.normalise("https://example.com/web/#/home.html"))
        assertEquals("https://example.com/jellyfin", ServerUrl.normalise("https://example.com/jellyfin/web/index.html?x=1#/home"))
    }

    @Test
    fun `explicit http is respected as typed`() {
        assertEquals(listOf("http://192.168.1.10:8096"), ServerUrl.candidates("http://192.168.1.10:8096"))
        assertEquals(listOf("http://jellyfin.example.com"), ServerUrl.candidates("http://jellyfin.example.com"))
    }

    @Test
    fun `local addresses without scheme try https then http`() {
        assertEquals(
            listOf("https://192.168.1.10:8096", "http://192.168.1.10:8096"),
            ServerUrl.candidates("192.168.1.10:8096"),
        )
        assertEquals(listOf("https://nas:8096", "http://nas:8096"), ServerUrl.candidates("nas:8096"))
        assertEquals(listOf("https://media.local", "http://media.local"), ServerUrl.candidates("media.local"))
        assertEquals(listOf("https://localhost:8096", "http://localhost:8096"), ServerUrl.candidates("localhost:8096"))
    }

    @Test
    fun `blank and malformed input is rejected with INVALID_URL`() {
        for (input in listOf("", "   ", "ftp://example.com", "https://", "exa mple.com", "http://exa mple.com")) {
            val error = assertThrows("input '$input'", JellyfinException::class.java) { ServerUrl.candidates(input) }
            assertEquals(JellyfinException.Kind.INVALID_URL, error.kind)
        }
    }

    @Test
    fun `server compatibility is derived from the reported version`() {
        assertEquals(ServerCompatibility.SUPPORTED, ServerCompatibility.of("10.11.5"))
        assertEquals(ServerCompatibility.SUPPORTED, ServerCompatibility.of("10.11"))
        assertEquals(ServerCompatibility.TOO_OLD, ServerCompatibility.of("10.10.7"))
        assertEquals(ServerCompatibility.TOO_OLD, ServerCompatibility.of("9.0.0"))
        assertEquals(ServerCompatibility.NEWER_UNTESTED, ServerCompatibility.of("12.4.0"))
        assertEquals(ServerCompatibility.UNKNOWN, ServerCompatibility.of(null))
        assertEquals(ServerCompatibility.UNKNOWN, ServerCompatibility.of("banana"))
    }

    @Test
    fun `server info knows whether the connection is encrypted`() {
        assertTrue(ServerInfo("https://example.com", "Home", "id", "10.11.0").isSecure)
        assertFalse(ServerInfo("http://192.168.1.10:8096", "Home", "id", "10.11.0").isSecure)
    }
}
