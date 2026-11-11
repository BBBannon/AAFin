package app.aafin.car

import android.os.Looper
import androidx.car.app.HandshakeInfo
import androidx.car.app.OnDoneCallback
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.car.app.testing.TestScreenManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Shared set-up for car screen tests: a `TestCarContext`, fakes for everything behind the
 * screens, and small helpers for reading and tapping templates.
 */
abstract class CarScreenTestBase {
    protected val carContext: TestCarContext =
        TestCarContext.createCarContext(ApplicationProvider.getApplicationContext()).also {
            // The level a current Android Auto reports; individual tests lower it.
            it.updateHandshakeInfo(HandshakeInfo("test.host", 7))
            // The app is on screen: pushed screens are started and invalidate() reaches the (fake) host.
            it.lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }
    protected val backend = FakeBackend()
    protected val limits = FakeLimits()
    protected val quality = FakeQuality()
    protected val overlay = FakeOverlay()
    protected val env = CarEnv(backend, limits, NoPosters, quality, overlay)

    protected val screenManager: TestScreenManager get() = carContext.getCarService(TestScreenManager::class.java)
    protected val toasts: List<String> get() = carContext.getCarService(TestAppManager::class.java).toastsShown.map { it.toString() }

    private val done = object : OnDoneCallback {}

    /** Runs everything queued on the main thread (coroutines, posted clicks). */
    protected fun idle() = shadowOf(Looper.getMainLooper()).idle()

    protected fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /**
     * Puts [screen] on top of the stack. Because the app is resumed, the screen manager starts it
     * and the test host asks for its template, exactly as Android Auto would.
     */
    protected fun <S : Screen> show(screen: S): S {
        screenManager.push(screen)
        idle()
        return screen
    }

    /** Templates the (fake) host has been given by [screen], oldest first. */
    protected fun templatesSentBy(screen: Screen): List<Template> = ScreenController(screen).templatesReturned

    protected val top: Screen get() = screenManager.top

    protected inline fun <reified T : Template> Screen.template(): T {
        val template = onGetTemplate()
        assertTrue("Expected ${T::class.simpleName} but was ${template.javaClass.simpleName}", template is T)
        return template as T
    }

    protected fun ItemList.rows(): List<Row> = items.map { it as Row }
    protected fun ItemList.gridItems(): List<GridItem> = items.map { it as GridItem }
    protected fun ListTemplate.rows(): List<Row> = singleList!!.rows()
    protected fun ListTemplate.titles(): List<String> = rows().map { it.title.toString() }
    protected fun GridTemplate.titles(): List<String> = singleList!!.gridItems().map { it.title.toString() }
    protected fun Row.text(): String? = texts.firstOrNull()?.toString()
    protected fun MessageTemplate.actionTitles(): List<String> = actions.map { it.title.toString() }

    protected fun Row.tap() {
        onClickDelegate!!.sendClick(done)
        idle()
    }

    protected fun GridItem.tap() {
        onClickDelegate!!.sendClick(done)
        idle()
    }

    protected fun Action.tap() {
        onClickDelegate!!.sendClick(done)
        idle()
    }

    protected fun ItemList.select(index: Int) {
        onSelectedDelegate!!.sendSelected(index, done)
        idle()
    }

    protected fun ListTemplate.row(title: String): Row = rows().first { it.title.toString() == title }
    protected fun MessageTemplate.action(title: String): Action = actions.first { it.title.toString() == title }
}
