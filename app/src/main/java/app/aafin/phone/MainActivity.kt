package app.aafin.phone

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.aafin.AAFinApp
import app.aafin.phone.ui.AAFinTheme
import app.aafin.phone.ui.PhoneApp

/**
 * The phone UI's single activity: accounts, browse-to-download, downloads, car profiles and
 * settings. Nothing plays here - video only ever plays on the car screen.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val backend = graphPhoneBackend(this, AAFinApp.graph(this))
        setContent {
            AAFinTheme {
                PhoneApp(backend)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Unfinished downloads only carry on once the app is on screen again (there is no
        // background scheduler by design): starting the download service is allowed here.
        AAFinApp.graph(this).downloads.resumePending()
    }
}
