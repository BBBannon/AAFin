package app.aafin.phone.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aafin.R

/** One step of the setup checklist. The same words are in `docs/PHONE-SETUP.md`. */
data class SetupStep(val title: String, val body: String, val opensAndroidAuto: Boolean = false)

val SetupSteps = listOf(
    SetupStep(
        "Install AAFin from a computer",
        "Done if you did it this way. Android Auto only shows apps installed by Google Play, so AAFin must be installed over USB " +
            "from a computer with: $ADB_COMMAND — opening the APK file on the phone installs it, but Android Auto won't list it. " +
            "Install updates the same way; your accounts and downloads are kept.",
    ),
    SetupStep(
        "Sign in to Jellyfin",
        "Add your Jellyfin server here in AAFin and sign in. Quick Connect is the quickest way: AAFin shows a code and you " +
            "approve it in a Jellyfin app that is already signed in.",
    ),
    SetupStep(
        "Connect to the car once",
        "Connect the phone to the car with a cable or wirelessly. Android Auto only adds AAFin when a car connects, so it may " +
            "not appear until then.",
    ),
    SetupStep(
        "Show AAFin in the car launcher",
        "In the phone's Settings, go to Apps, then Android Auto, then Customise launcher, and make sure AAFin is ticked. " +
            "Not there? AAFin was probably installed on the phone rather than from a computer: install it again with the command in step 1.",
        opensAndroidAuto = true,
    ),
    SetupStep(
        "Open AAFin on the car screen",
        "Open AAFin from the Android Auto launcher on the car screen. Films and episodes play there, not on the phone.",
    ),
)

/** The install command; the same in README.md and docs/PHONE-SETUP.md. */
const val ADB_COMMAND = "adb install -r -i com.android.vending AAFin.apk"

/**
 * The setup checklist.
 *
 * @param onContinue shown as a "Continue" button on first run; null when opened from Settings.
 */
@Composable
fun SetupScreen(onContinue: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var androidAutoMissing by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .then(if (onContinue != null) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                .padding(16.dp)
                .testTag("setup-checklist"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onContinue != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(72.dp))
                    Column {
                        Text(
                            "Welcome to AAFin",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "Five steps to get Jellyfin on your car screen.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SetupSteps.forEachIndexed { index, step ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(
                            Modifier.size(36.dp).background(BrandGradient, CircleShape).clearAndSetSemantics { },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Step ${index + 1}: ${step.title}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(step.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (step.opensAndroidAuto) {
                                OutlinedButton(
                                    onClick = { androidAutoMissing = !openAndroidAutoAppInfo(context) },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    Text("Open Android Auto app info")
                                }
                                Text(
                                    "From there, tap “Additional settings in the app”.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (androidAutoMissing) {
                                    Notice("Couldn't open Android Auto's page. Find Android Auto in the phone's Settings instead.", warning = true)
                                }
                            }
                        }
                    }
                }
            }

            if (onContinue != null) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("setup-continue")) {
                    Text("Continue")
                }
                Text(
                    "You can read this again later in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

/** Opens the system's app-info page for Android Auto. Returns false if the phone cannot show it. */
private fun openAndroidAutoAppInfo(context: Context): Boolean = try {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}
