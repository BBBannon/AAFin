package app.aafin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Regression test for a crash found on the emulator: the Jellyfin SDK reads the response body on
 * the thread the coroutine resumes on, so a call made from the main thread (which is where the
 * car and phone screens call from) died with `NetworkOnMainThreadException` whenever the body
 * arrived after the headers. `jellyfinCall` now moves every SDK call to a background thread.
 *
 * The server here is a bare HTTP/1.1 socket that sends its headers, waits, then sends the body -
 * so the body can never already be buffered when the call resumes.
 */
@RunWith(AndroidJUnit4::class)
class MainThreadNetworkTest {

    private val graph = AAFinApp.graph(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun sdkCallMadeFromTheMainThreadReadsItsReplyElsewhere() {
        val body = """{"ServerName":"Slow server","Version":"12.4.0","Id":"srv-slow","StartupWizardCompleted":true}"""
        // 127.0.0.1 by name: on Android the "loopback address" is the IPv6 one.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread {
                server.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    while (!request.readLine().isNullOrEmpty()) Unit // skip the request line and headers
                    val out = socket.getOutputStream()
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n").toByteArray(),
                    )
                    out.flush()
                    Thread.sleep(500)
                    out.write(body.toByteArray())
                    out.flush()
                }
            }

            val info = runBlocking {
                withContext(Dispatchers.Main) { graph.auth.findServer("http://127.0.0.1:${server.localPort}") }
            }

            assertEquals("Slow server", info.name)
            assertEquals("12.4.0", info.version)
            responder.join(5_000)
        }
    }
}
