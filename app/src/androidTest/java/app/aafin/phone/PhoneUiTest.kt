package app.aafin.phone

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.jellyfin.SourceInfo
import app.aafin.phone.ui.AAFinTheme
import app.aafin.phone.ui.PhoneApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone screens on a real Android runtime, driven through the whole app (`PhoneApp`) with a
 * fake data layer: no Jellyfin server, no real downloads, the device's real accounts untouched.
 *
 * Run with an emulator attached: `gradlew.bat connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class PhoneUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun allowNotifications() {
        // Otherwise the first download would stop at Android's permission prompt, which a Compose test cannot answer.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun show(phone: FakePhone) {
        compose.setContent { AAFinTheme { PhoneApp(phone.backend) } }
    }

    private fun ComposeContentTestRule.awaitTag(tag: String, timeoutMs: Long = 10_000) {
        waitUntil(timeoutMs) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeContentTestRule.awaitText(text: String, timeoutMs: Long = 10_000) {
        waitUntil(timeoutMs) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    // --- Add account ---------------------------------------------------------------------------

    @Test
    fun firstRun_checklistThenQuickConnectSignIn() {
        val phone = FakePhone(setupSeen = false)
        show(phone)

        // First run: the setup checklist, all five steps.
        compose.awaitTag("setup-checklist")
        compose.onNodeWithText("Step 1: Install AAFin from a computer").assertExists()
        compose.onNodeWithText("Step 5: Open AAFin on the car screen").assertExists()
        compose.onNodeWithTag("setup-continue").performScrollTo().performClick()

        // Signed out: straight to "add your server". The address is normalised and checked.
        compose.awaitTag("server-address")
        compose.onNodeWithTag("server-address").performTextInput("jellyfin.example.com/")
        compose.onNodeWithTag("server-continue").performScrollTo().performClick()
        compose.awaitTag("server-card")
        compose.onNodeWithText("Home").assertIsDisplayed()
        compose.onNodeWithText("Jellyfin 12.4.0").assertIsDisplayed()
        compose.onNodeWithText(FakeAuthGateway.SERVER_URL).assertIsDisplayed()

        // Quick Connect: the code is shown and the app waits.
        compose.onNodeWithTag("use-quick-connect").performScrollTo().performClick()
        compose.awaitTag("quick-connect-code")
        compose.onNodeWithTag("quick-connect-code").assertTextEquals(FakeAuthGateway.CODE)
        compose.onNodeWithTag("quick-connect-status").assertTextContains("Waiting", substring = true)
        assertNull(runBlocking { phone.accountStore.active() })

        // Approved in "another Jellyfin app": the next poll signs in and the tabs appear.
        phone.gateway.approved = true
        compose.awaitTag("tab-Browse", timeoutMs = 20_000)
        compose.onNodeWithTag("account-switcher").assertTextContains("ben")
        compose.awaitTag("library-Films")
        assertEquals("server-1:user-ben", runBlocking { phone.accountStore.active() }?.id)
    }

    @Test
    fun quickConnectCanBeCancelled_andPasswordSignInWorks() {
        val phone = FakePhone()
        show(phone)

        compose.awaitTag("server-address")
        compose.onNodeWithTag("server-address").performTextInput("jellyfin.example.com")
        compose.onNodeWithTag("server-continue").performScrollTo().performClick()
        compose.awaitTag("use-quick-connect")

        compose.onNodeWithTag("use-quick-connect").performScrollTo().performClick()
        compose.awaitTag("quick-connect-cancel")
        compose.onNodeWithTag("quick-connect-cancel").performScrollTo().performClick()

        // Back at the choice of method; take the password route.
        compose.awaitTag("use-password")
        compose.onNodeWithTag("use-password").performScrollTo().performClick()
        compose.awaitTag("username")
        compose.onNodeWithTag("username").performTextInput("carol")
        compose.onNodeWithTag("password").performTextInput("wrong")
        compose.onNodeWithTag("sign-in").performScrollTo().performClick()
        compose.awaitTag("password-error")
        compose.onNodeWithText("Wrong username or password.").assertExists()

        compose.onNodeWithTag("password").performScrollTo().performTextReplacement(FakeAuthGateway.PASSWORD)
        compose.onNodeWithTag("sign-in").performScrollTo().performClick()
        compose.awaitTag("tab-Browse")
        compose.onNodeWithTag("account-switcher").assertTextContains("carol")
    }

    @Test
    fun unreachableServer_showsTheReasonAndStaysOnTheAddress() {
        show(FakePhone())

        compose.awaitTag("server-address")
        compose.onNodeWithTag("server-address").performTextInput("nowhere.example.com")
        compose.onNodeWithTag("server-continue").performScrollTo().performClick()

        compose.awaitText("Can't reach the server")
        compose.onNodeWithTag("server-address").assertExists()
    }

    @Test
    fun accountSwitcher_changesTheActiveAccount() {
        val phone = FakePhone.signedIn(FakePhone.BOB)
        show(phone)

        compose.awaitTag("account-switcher")
        compose.onNodeWithTag("account-switcher").assertTextContains("alice").performClick()
        compose.awaitTag("switch-to-bob")
        compose.onNodeWithTag("switch-to-bob").performClick()

        compose.waitUntil(10_000) { runBlocking { phone.accountStore.active() } == FakePhone.BOB }
        compose.onNodeWithTag("account-switcher").assertTextContains("bob")
    }

    // --- Download dialog -----------------------------------------------------------------------

    private fun openFilmFromBrowse(phone: FakePhone, name: String) {
        show(phone)
        compose.awaitTag("library-Films")
        compose.onNodeWithTag("library-Films").performClick()
        compose.awaitText(name)
        compose.onNodeWithText(name).performClick()
        compose.awaitTag("download")
        // No playback on the phone, and the screen says so.
        compose.onNodeWithText("Play from your car screen", substring = true).assertExists()
        compose.onNodeWithTag("download").performScrollTo().performClick()
        compose.awaitTag("download-dialog")
    }

    @Test
    fun downloadDialog_preselects720p_andDownloadsWhatIsChosen() {
        val phone = FakePhone.signedIn()
        val film = fakeFilm(1, "Big Film")
        phone.library.contents = mapOf("lib-films" to listOf(film))
        phone.library.sources = mapOf(film.id to SourceInfo(sizeBytes = 12_000_000_000L, bitrate = 13_000_000))

        openFilmFromBrowse(phone, "Big Film")

        compose.onNodeWithTag("quality-P720").assertIsSelected()
        compose.onNodeWithTag("quality-ORIGINAL").assertIsNotSelected().assertIsEnabled()
        compose.onNodeWithTag("size-P720", useUnmergedTree = true).assertTextEquals("About 3.6 GB")
        compose.awaitText("About 12.0 GB")

        // Confirm without touching the choice: 720p.
        compose.onNodeWithTag("download-confirm").performClick()
        compose.waitUntil(10_000) { phone.downloads.enqueued.isNotEmpty() }
        assertEquals(listOf(film to DownloadQuality.P720), phone.downloads.enqueued.toList())

        // The film now shows as in the downloads instead of offering Download again.
        compose.awaitText("This is in your downloads")
    }

    @Test
    fun downloadDialog_originalIsExplainedWhenTheAccountMayNotDownload() {
        val phone = FakePhone.signedIn()
        phone.library.contents = mapOf("lib-films" to listOf(fakeFilm(1, "Locked Film", canDownload = false)))

        openFilmFromBrowse(phone, "Locked Film")

        compose.onNodeWithTag("quality-P720").assertIsSelected()
        compose.onNodeWithTag("quality-ORIGINAL").assertIsNotEnabled().assertIsNotSelected()
        compose.onNodeWithText(DownloadChoice.ORIGINAL_NOT_ALLOWED, useUnmergedTree = true).assertExists()
    }

    @Test
    fun downloadDialog_originalCanBeChosen() {
        val phone = FakePhone.signedIn()
        val film = fakeFilm(1, "Big Film")
        phone.library.contents = mapOf("lib-films" to listOf(film))

        openFilmFromBrowse(phone, "Big Film")
        compose.onNodeWithTag("quality-ORIGINAL").performClick()
        compose.onNodeWithTag("quality-ORIGINAL").assertIsSelected()
        compose.onNodeWithTag("quality-P720").assertIsNotSelected()
        compose.onNodeWithTag("download-confirm").performClick()

        compose.waitUntil(10_000) { phone.downloads.enqueued.isNotEmpty() }
        assertEquals(DownloadQuality.ORIGINAL, phone.downloads.enqueued.single().second)
    }

    // --- Downloads list ------------------------------------------------------------------------

    @Test
    fun downloadsList_showsProgressStatesStorageAndActions() {
        val phone = FakePhone.signedIn()
        val downloading = fakeDownload(fakeFilm(1, "Downloading Film"), DownloadStatus.DOWNLOADING, percent = 42f, bytesDownloaded = 310_000_000)
        val done = fakeDownload(fakeEpisode(2, 3), DownloadStatus.COMPLETED, bytesDownloaded = 1_200_000_000, quality = DownloadQuality.ORIGINAL)
        val failed = fakeDownload(fakeFilm(3, "Broken Film"), DownloadStatus.FAILED)
        phone.downloads.downloads.value = listOf(downloading, done, failed)
        phone.downloads.usedBytes = 5_000_000_000
        phone.downloads.freeBytes = 40_000_000_000
        show(phone)

        compose.awaitTag("tab-Downloads")
        compose.onNodeWithTag("tab-Downloads").performClick()
        compose.awaitTag("downloads-list")
        compose.awaitText("Downloads use 5.0 GB · 40.0 GB free on this phone")

        val list = compose.onNodeWithTag("downloads-list")

        list.performScrollToNode(hasTestTag("download-row-${downloading.itemId}"))
        compose.onNodeWithText("Downloading Film").assertExists()
        compose.onNodeWithTag("download-status-${downloading.itemId}").assertTextEquals("Downloading · 42% · 310 MB")
        compose.onNodeWithTag("pause-${downloading.itemId}").performScrollTo().performClick()
        assertEquals(listOf(downloading.itemId), phone.downloads.paused.toList())

        list.performScrollToNode(hasTestTag("download-row-${done.itemId}"))
        compose.onNodeWithText("The Show · S1:E3 · Episode 3").assertExists()
        compose.onNodeWithTag("download-status-${done.itemId}").assertTextEquals("Downloaded · 1.2 GB")

        list.performScrollToNode(hasTestTag("download-row-${failed.itemId}"))
        compose.onNodeWithTag("download-status-${failed.itemId}").assertTextContains("Failed", substring = true)
        compose.onNodeWithTag("retry-${failed.itemId}").performScrollTo().performClick()
        compose.waitUntil(10_000) { phone.downloads.retried.isNotEmpty() }
        assertEquals(listOf(failed.itemId), phone.downloads.retried.toList())

        // Delete asks first.
        compose.onNodeWithTag("delete-${failed.itemId}").performScrollTo().performClick()
        compose.awaitText("Delete download?")
        assertEquals(emptyList<String>(), phone.downloads.deleted.toList())
        compose.onNodeWithTag("confirm-dialog-confirm").performClick()
        compose.waitUntil(10_000) { phone.downloads.deleted.isNotEmpty() }
        assertEquals(listOf(failed.itemId), phone.downloads.deleted.toList())
    }

    // --- Cars and settings ---------------------------------------------------------------------

    @Test
    fun carsAndSettingsTabsRender() {
        val phone = FakePhone.signedIn()
        runBlocking {
            phone.carProfiles.getOrCreate("model:dacia|duster|2025", "Dacia Duster")
            phone.carProfiles.markUsed("model:dacia|duster|2025")
        }
        show(phone)

        compose.awaitTag("tab-Cars")
        compose.onNodeWithTag("tab-Cars").performClick()
        compose.awaitText("Dacia Duster")
        compose.onNodeWithText("Quality: Auto (recommended)").assertExists()
        compose.onNodeWithText("Recognised by model: dacia duster 2025").assertExists()
        compose.onNodeWithText("Last used", substring = true).assertExists()

        compose.onNodeWithTag("tab-Settings").performClick()
        compose.awaitTag("settings")
        compose.awaitText("Jellyfin 12.4.0")
        compose.onNodeWithText("1.0-test").assertExists()
        compose.onNodeWithTag("open-setup").performScrollTo().performClick()
        compose.awaitTag("setup-checklist")
    }
}
