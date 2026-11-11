package app.aafin.phone

import app.aafin.data.accounts.AccountsState
import app.aafin.data.jellyfin.AuthRepository
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.ServerInfo
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // --- Finding the server ------------------------------------------------------------------

    @Test
    fun `a server that answers moves on to the choice of sign-in method`() = runTest(dispatcher) {
        val phone = FakePhone()
        val viewModel = AddAccountViewModel(phone.backend)

        viewModel.checkServer("jellyfin.example.com/")
        assertTrue(viewModel.state.value.checkingServer)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(AddAccountStep.METHOD, state.step)
        assertEquals(ServerInfo("https://jellyfin.example.com", "Home", "server-1", "12.4.0"), state.server)
        assertFalse(state.checkingServer)
        assertNull(state.serverError)
    }

    @Test
    fun `a server that does not answer stays on the address with the reason`() = runTest(dispatcher) {
        val viewModel = AddAccountViewModel(FakePhone().backend)

        viewModel.checkServer("nowhere.example.com")
        advanceUntilIdle()
        assertEquals(AddAccountStep.SERVER, viewModel.state.value.step)
        assertEquals(JellyfinException.Kind.NETWORK.userMessage, viewModel.state.value.serverError)

        viewModel.checkServer("not a url")
        advanceUntilIdle()
        assertEquals(JellyfinException.Kind.INVALID_URL.userMessage, viewModel.state.value.serverError)
    }

    // --- Quick Connect -----------------------------------------------------------------------

    private suspend fun kotlinx.coroutines.test.TestScope.atMethodStep(phone: FakePhone): AddAccountViewModel {
        val viewModel = AddAccountViewModel(phone.backend)
        viewModel.checkServer("jellyfin.example.com")
        advanceUntilIdle()
        return viewModel
    }

    @Test
    fun `Quick Connect shows the code, waits for approval, then signs in`() = runTest(dispatcher) {
        val phone = FakePhone()
        val viewModel = atMethodStep(phone)

        viewModel.signedIn.test {
            viewModel.startQuickConnect()
            assertEquals(AddAccountStep.QUICK_CONNECT, viewModel.state.value.step)
            assertEquals(QuickConnectUi.Starting, viewModel.state.value.quickConnect)

            runCurrent()
            assertEquals(QuickConnectUi.Code("123456"), viewModel.state.value.quickConnect)

            // Still waiting after several polls.
            advanceTimeBy(3 * AuthRepository.DEFAULT_POLL_INTERVAL_MS)
            assertEquals(QuickConnectUi.Code("123456"), viewModel.state.value.quickConnect)
            expectNoEvents()

            phone.gateway.approved = true
            advanceTimeBy(AuthRepository.DEFAULT_POLL_INTERVAL_MS + 1)

            val account = awaitItem()
            assertEquals("ben", account.userName)
            assertEquals(account, phone.accountStore.active())
            // The form is blank again, ready for another account.
            assertEquals(AddAccountUiState(), viewModel.state.value)
        }
    }

    @Test
    fun `Quick Connect that is never approved times out and can be started again`() = runTest(dispatcher) {
        val phone = FakePhone()
        val viewModel = atMethodStep(phone)

        viewModel.startQuickConnect()
        advanceTimeBy(AuthRepository.DEFAULT_TIMEOUT_MS + 1)
        assertEquals(QuickConnectUi.TimedOut, viewModel.state.value.quickConnect)
        assertNull(phone.accountStore.active())

        viewModel.startQuickConnect()
        runCurrent()
        assertEquals(QuickConnectUi.Code("123456"), viewModel.state.value.quickConnect)
        assertEquals(2, phone.gateway.codesIssued)
    }

    @Test
    fun `Quick Connect switched off on the server offers the password instead`() = runTest(dispatcher) {
        val phone = FakePhone()
        phone.gateway.quickConnectEnabled = false
        val viewModel = atMethodStep(phone)

        viewModel.startQuickConnect()
        advanceUntilIdle()

        assertEquals(
            QuickConnectUi.Failed(JellyfinException.Kind.QUICK_CONNECT_DISABLED.userMessage, offerPassword = true),
            viewModel.state.value.quickConnect,
        )
        assertEquals(0, phone.gateway.codesIssued)
    }

    @Test
    fun `cancelling Quick Connect stops polling and returns to the choice of method`() = runTest(dispatcher) {
        val phone = FakePhone()
        val viewModel = atMethodStep(phone)

        viewModel.startQuickConnect()
        advanceTimeBy(2 * AuthRepository.DEFAULT_POLL_INTERVAL_MS + 1)
        val polls = phone.gateway.polls
        assertTrue(polls > 0)

        viewModel.cancelQuickConnect()
        phone.gateway.approved = true
        advanceTimeBy(10 * AuthRepository.DEFAULT_POLL_INTERVAL_MS)

        assertEquals(AddAccountStep.METHOD, viewModel.state.value.step)
        assertEquals(polls, phone.gateway.polls)
        assertNull(phone.accountStore.active())
    }

    @Test
    fun `switching to the password abandons the Quick Connect code`() = runTest(dispatcher) {
        val phone = FakePhone()
        val viewModel = atMethodStep(phone)
        viewModel.startQuickConnect()
        runCurrent()

        viewModel.usePassword()
        phone.gateway.approved = true
        advanceTimeBy(10 * AuthRepository.DEFAULT_POLL_INTERVAL_MS)

        assertEquals(AddAccountStep.PASSWORD, viewModel.state.value.step)
        assertNull(phone.accountStore.active())
    }

    // --- Password ----------------------------------------------------------------------------

    @Test
    fun `a wrong password says so and a right one signs in`() = runTest(dispatcher) {
        val phone = FakePhone()
        val viewModel = atMethodStep(phone)
        viewModel.usePassword()

        viewModel.signIn("", "x")
        assertEquals("Enter your Jellyfin username.", viewModel.state.value.passwordError)

        viewModel.signIn("carol", "wrong")
        assertTrue(viewModel.state.value.signingIn)
        advanceUntilIdle()
        assertEquals("Wrong username or password.", viewModel.state.value.passwordError)
        assertFalse(viewModel.state.value.signingIn)
        assertEquals(AddAccountStep.PASSWORD, viewModel.state.value.step)

        viewModel.signedIn.test {
            viewModel.signIn(" carol ", FakeAuthGateway.PASSWORD)
            assertEquals("carol", awaitItem().userName)
        }
        assertEquals("server-1:user-carol", phone.accountStore.active()?.id)
    }

    @Test
    fun `back steps through the stages and reports when there is nowhere left to go`() = runTest(dispatcher) {
        val viewModel = atMethodStep(FakePhone())

        viewModel.usePassword()
        assertTrue(viewModel.back())
        assertEquals(AddAccountStep.METHOD, viewModel.state.value.step)

        viewModel.startQuickConnect()
        assertTrue(viewModel.back())
        assertEquals(AddAccountStep.METHOD, viewModel.state.value.step)

        assertTrue(viewModel.back())
        assertEquals(AddAccountStep.SERVER, viewModel.state.value.step)
        assertFalse(viewModel.back())
    }

    // --- App state and account switching -----------------------------------------------------

    @Test
    fun `first run - setup not seen, signed out`() = runTest(dispatcher) {
        val phone = FakePhone(setupSeen = false)
        val viewModel = AppViewModel(phone.backend)
        assertTrue(viewModel.state.value.loading)

        runCurrent()
        assertEquals(AppUiState(loading = false, setupSeen = false, accounts = emptyList(), active = null), viewModel.state.value)

        viewModel.finishSetup()
        runCurrent()
        assertTrue(viewModel.state.value.setupSeen)
    }

    @Test
    fun `switching makes another account active`() = runTest(dispatcher) {
        val phone = FakePhone.signedIn(FakePhone.BOB)
        val viewModel = AppViewModel(phone.backend)
        runCurrent()
        assertEquals(FakePhone.ALICE, viewModel.state.value.active)
        assertEquals(listOf(FakePhone.ALICE, FakePhone.BOB), viewModel.state.value.accounts)

        viewModel.switchAccount(FakePhone.BOB.id)
        runCurrent()
        assertEquals(FakePhone.BOB, viewModel.state.value.active)
        assertEquals(FakePhone.BOB, phone.accountStore.active())

        // An account that has gone is ignored rather than crashing.
        viewModel.switchAccount("server-1:nobody")
        runCurrent()
        assertEquals(FakePhone.BOB, viewModel.state.value.active)
    }

    @Test
    fun `removing the active account falls back to another, and removing the last signs out`() = runTest(dispatcher) {
        val phone = FakePhone.signedIn(FakePhone.BOB)
        val viewModel = AppViewModel(phone.backend)
        runCurrent()

        viewModel.removeAccount(FakePhone.ALICE.id)
        runCurrent()
        assertEquals(FakePhone.BOB, viewModel.state.value.active)

        viewModel.removeAccount(FakePhone.BOB.id)
        runCurrent()
        assertNull(viewModel.state.value.active)
        assertEquals(emptyList<Any>(), viewModel.state.value.accounts)
    }

    @Test
    fun `signing in adds the account to the app state`() = runTest(dispatcher) {
        val phone = FakePhone(accounts = AccountsState())
        val app = AppViewModel(phone.backend)
        val add = atMethodStep(phone)
        add.usePassword()
        add.signIn("carol", FakeAuthGateway.PASSWORD)
        advanceUntilIdle()

        assertEquals("carol", app.state.value.active?.userName)
    }

    // --- Downloads stranded by a new sign-in -------------------------------------------------

    @Test
    fun `restarted downloads are announced, and nothing is said when there were none`() = runTest(dispatcher) {
        val phone = FakePhone.signedIn()
        val viewModel = AppViewModel(phone.backend)

        viewModel.messages.test {
            viewModel.checkDownloadsAfterSignIn()
            runCurrent()
            expectNoEvents()
            assertEquals(1, phone.downloads.restartStaleCalls)

            phone.downloads.staleRestarted = 2
            viewModel.checkDownloadsAfterSignIn()
            assertEquals("Restarted 2 unfinished downloads after signing in again.", awaitItem())
        }
    }

    @Test
    fun `a failed restart is reported, not swallowed`() = runTest(dispatcher) {
        val phone = FakePhone.signedIn()
        val viewModel = AppViewModel(phone.backend)

        viewModel.messages.test {
            phone.downloads.restartStaleError = JellyfinException(JellyfinException.Kind.NETWORK)
            viewModel.checkDownloadsAfterSignIn()
            assertTrue(awaitItem().contains("tap Retry"))

            phone.downloads.restartStaleError = IOException("disk full")
            viewModel.checkDownloadsAfterSignIn()
            assertTrue(awaitItem().contains(STORAGE_ERROR_MESSAGE))
        }
    }
}
