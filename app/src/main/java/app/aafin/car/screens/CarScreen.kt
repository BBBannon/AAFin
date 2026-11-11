package app.aafin.car.screens

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import app.aafin.R
import app.aafin.car.CarEnv
import app.aafin.car.CarLog
import app.aafin.car.FlowBudget
import app.aafin.car.PageWindow
import app.aafin.car.Paging
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where a screen's data has got to. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>

    /** @property retrying a retry is in flight; the same message stays up meanwhile. */
    data class Failed(val error: JellyfinException, val retrying: Boolean = false) : Load<Nothing>
}

/**
 * Base class of every AAFin car screen.
 *
 * Its main job is to keep the app inside Android Auto's template budget (see [FlowBudget]):
 * each screen knows which step of the current task it is, counts a step whenever what it shows
 * changes in a way the host does not treat as a refresh, and never pushes a screen the host would
 * reject. If the budget does run out, the flow is restarted from Home instead of crashing.
 *
 * @param firstStep the step this screen's first template is (Home is 1).
 */
abstract class CarScreen(
    carContext: CarContext,
    protected val env: CarEnv,
    firstStep: Int,
) : Screen(carContext) {

    /**
     * What a screen wants to show.
     *
     * @property structure changes exactly when the host would count the template as a new step:
     *   for lists and grids, the title plus the titles of the rows.
     * @property loading a loading template; whatever replaces it (of the same type) is free.
     */
    protected class Content(val template: Template, val structure: String, val loading: Boolean = false)

    /** The step of the template currently on show. */
    var step: Int = firstStep
        private set

    private var shown: Content? = null
    private var restarting = false

    /** The highest step at which this screen will show a list or grid; beyond it the flow restarts. */
    protected open val maxContentStep: Int get() = FlowBudget.MAX_STEPS - 1

    protected val scope: CoroutineScope get() = lifecycleScope

    protected abstract fun content(): Content

    final override fun onGetTemplate(): Template {
        val next = content()
        val previous = shown
        if (previous == null) {
            shown = next
            return next.template
        }
        val sameType = previous.template.javaClass == next.template.javaClass
        val refresh = sameType && (previous.loading || previous.structure == next.structure)
        if (!refresh) {
            val terminal = next.template.isTerminal()
            val nextStep = if (next.template is NavigationTemplate) 1 else step + 1
            if (!FlowBudget.allows(nextStep, terminal) || (!terminal && nextStep > maxContentStep)) {
                restartFlow()
                return previous.template
            }
            step = nextStep
        }
        shown = next
        return next.template
    }

    private fun Template.isTerminal(): Boolean = this is MessageTemplate || this is NavigationTemplate

    // --- Navigation ------------------------------------------------------------------------

    /** Pushes a browsing screen as the next step, or restarts the flow if there is no step left. */
    protected fun open(create: (step: Int) -> Screen) {
        val next = step + 1
        if (FlowBudget.allows(next, terminal = false)) screenManager.push(create(next)) else restartFlow()
    }

    /** Opens whatever [entry] is: plays films and episodes, browses into series and collections. */
    protected fun openEntry(entry: MediaEntry) {
        when {
            entry.kind.isPlayable -> play(entry)
            entry.kind == ItemKind.SERIES -> open { SeriesScreen(carContext, env, it, entry.id, entry.name) }
            entry.kind == ItemKind.SEASON && entry.seriesId != null ->
                open { SeriesScreen(carContext, env, it, entry.seriesId, entry.seriesName ?: entry.name, seasonKey = entry.id) }
            entry.kind == ItemKind.BOX_SET || entry.kind == ItemKind.FOLDER ->
                open { LibraryScreen(carContext, env, it, LibrarySource.Folder(entry.id, entry.name)) }
            else -> toast("AAFin can't open this item")
        }
    }

    /** The player is a navigation template, which starts a new task: always allowed. */
    protected fun play(entry: MediaEntry) {
        Log.i(CarLog.CAR, "Open player: ${entry.kind} ${entry.id}")
        screenManager.push(PlayerScreen(carContext, env, entry))
    }

    /**
     * Out of template budget: show a navigation template (which resets the host's count), then
     * replace the whole stack with a fresh Home. Deferred, because it is usually asked for while
     * the host is waiting for this screen's template.
     */
    protected fun restartFlow() {
        if (restarting) return
        restarting = true
        Log.i(CarLog.CAR, "Template budget used up at step $step; restarting from Home")
        Handler(Looper.getMainLooper()).post {
            restarting = false
            if (screenManager.top === this) screenManager.push(FlowResetScreen(carContext, env))
        }
    }

    protected fun toast(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_LONG).show()

    // --- Loading ---------------------------------------------------------------------------

    /**
     * Runs [fetch] and hands the outcome to [set], redrawing after each change.
     *
     * - A first load shows the loading state.
     * - A retry after a failure keeps the same message on screen (a new message would cost a step).
     * - A reload over content that is already showing keeps that content, even if the reload fails.
     */
    protected fun <T> load(current: Load<T>, set: (Load<T>) -> Unit, fetch: suspend () -> T): Job {
        if (current is Load.Failed) {
            set(current.copy(retrying = true))
            invalidate()
        }
        return scope.launch {
            val result = try {
                Load.Ready(fetch())
            } catch (e: JellyfinException) {
                Log.w(CarLog.CAR, "${this@CarScreen.javaClass.simpleName}: load failed (${e.kind})")
                if (current is Load.Ready) {
                    toast(e.userMessage)
                    return@launch
                }
                Load.Failed(e)
            }
            set(result)
            invalidate()
        }
    }

    // --- Template pieces -------------------------------------------------------------------

    /** A white glyph, tinted by the host so it reads on both its light and dark themes. */
    protected fun icon(@DrawableRes id: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, id)).setTint(CarColor.DEFAULT).build()

    protected fun iconAction(@DrawableRes id: Int, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(id)).setOnClickListener(onClick).build()

    protected fun textAction(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener(onClick).build()

    /** A search button for a list or grid, when there is budget for search and what it leads to. */
    protected fun searchStrip(): ActionStrip? =
        if (FlowBudget.canPage(step, stepsBelow = 1)) {
            ActionStrip.Builder().addAction(iconAction(R.drawable.ic_car_search) { open { SearchScreen(carContext, env, it) } }).build()
        } else {
            null
        }

    // Title + header action are deprecated in favour of Header, which needs car API level 7;
    // the manifest's minimum is 1, so the original calls are used throughout.
    @Suppress("DEPRECATION")
    protected fun loadingList(title: String, headerAction: Action = Action.BACK): Content =
        Content(
            ListTemplate.Builder().setTitle(title).setHeaderAction(headerAction).setLoading(true).build(),
            structure = "loading",
            loading = true,
        )

    @Suppress("DEPRECATION")
    protected fun listContent(
        title: String,
        items: ItemList,
        structure: String,
        headerAction: Action = Action.BACK,
        actionStrip: ActionStrip? = null,
    ): Content {
        val builder = ListTemplate.Builder().setTitle(title).setHeaderAction(headerAction).setSingleList(items)
        actionStrip?.let(builder::setActionStrip)
        return Content(builder.build(), structure)
    }

    /**
     * A friendly error with Retry (and optionally one more action).
     * While the retry runs the same message stays up with a spinner, which the host treats as a refresh.
     */
    @Suppress("DEPRECATION")
    protected fun errorContent(
        title: String,
        failure: Load.Failed,
        headerAction: Action = Action.BACK,
        extraAction: Action? = null,
        onRetry: () -> Unit,
    ): Content {
        val builder = MessageTemplate.Builder(failure.error.userMessage).setTitle(title).setHeaderAction(headerAction)
        if (failure.retrying && carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            builder.setLoading(true)
        } else {
            builder.setIcon(if (failure.error.kind == JellyfinException.Kind.NOT_SIGNED_IN) CarIcon.APP_ICON else CarIcon.ERROR)
            if (!failure.retrying) builder.addAction(textAction("Retry", onRetry))
            extraAction?.let(builder::addAction)
        }
        return Content(builder.build(), structure = "error:${failure.error.kind}")
    }

    /** A plain row: title, optional second line, optional image, tap action. */
    protected fun row(
        title: String,
        text: String? = null,
        image: CarIcon? = null,
        imageType: Int = Row.IMAGE_TYPE_ICON,
        browsable: Boolean = false,
        onClick: () -> Unit,
    ): Row {
        val builder = Row.Builder().setTitle(title).setOnClickListener(onClick).setBrowsable(browsable)
        if (!text.isNullOrBlank()) builder.addText(text)
        image?.let { builder.setImage(it, imageType) }
        return builder.build()
    }

    /**
     * The slice of a longer list that fits on this screen.
     *
     * @param stepsBelow browsing screens the listed things still need before the player.
     */
    protected fun pageWindow(total: Int, start: Int, limit: Int, stepsBelow: Int): PageWindow =
        Paging.window(remaining = total - start, limit = limit, canPage = FlowBudget.canPage(step, stepsBelow))

    protected fun moreRow(onClick: () -> Unit): Row =
        row(MORE, image = icon(R.drawable.ic_car_more), browsable = true, onClick = onClick)

    protected companion object {
        const val MORE = "More…"
        const val LIST_CAP = 100
        const val GRID_CAP = 48
    }
}

/**
 * Shown for a moment when the template budget has run out. A navigation template resets the
 * host's step count; as soon as the host has taken it, the stack is replaced by a fresh Home.
 */
class FlowResetScreen(carContext: CarContext, private val env: CarEnv) : Screen(carContext) {
    private var handedOver = false

    override fun onGetTemplate(): Template {
        if (!handedOver) {
            handedOver = true
            Handler(Looper.getMainLooper()).post(::showHome)
        }
        val home = Action.Builder().setTitle("Home").setOnClickListener(::showHome).build()
        return NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(home).build()).build()
    }

    private fun showHome() {
        if (screenManager.top !== this) return
        val old = screenManager.screenStack.toList()
        // The reset template was step 1 of the new task, so this Home is step 2.
        screenManager.push(HomeScreen(carContext, env, firstStep = 2))
        old.forEach(screenManager::remove)
    }
}
