package app.aafin.car.screens

import android.util.Log
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.aafin.R
import app.aafin.car.CarEnv
import app.aafin.car.CarLog
import app.aafin.car.FlowBudget
import app.aafin.car.Paging
import app.aafin.data.accounts.Account
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What Home shows. [account] null = signed out. */
data class HomeData(
    val account: Account?,
    val accountCount: Int = 0,
    val libraries: List<Library> = emptyList(),
    val resume: List<MediaEntry> = emptyList(),
    val nextUp: List<MediaEntry> = emptyList(),
)

/**
 * The first screen: Continue watching, Next up, the libraries, Downloads, Search and (with more
 * than one account) Switch account. If that is more rows than the car allows, the rest sit behind
 * a "More…" row, which opens this screen again from [startIndex].
 *
 * The rows do not depend on how much there is to continue or what is next up, so reloading
 * (every time Home comes back into view) never costs a template step.
 */
class HomeScreen(
    carContext: CarContext,
    env: CarEnv,
    firstStep: Int = 1,
    private val startIndex: Int = 0,
    preloaded: HomeData? = null,
) : CarScreen(carContext, env, firstStep) {

    private class MenuEntry(val title: String, val text: String?, @param:DrawableRes val icon: Int, val onClick: () -> Unit)

    private var state: Load<HomeData> = preloaded?.let { Load.Ready(it) } ?: Load.Loading
    private var loading: Job? = null
    private val isMainPage = startIndex == 0

    // Home itself is kept at step 2 or lower, so a library, a series and the player always fit after it.
    override val maxContentStep: Int get() = if (isMainPage) 2 else super.maxContentStep

    init {
        if (isMainPage) {
            // Reloads when someone signs in or switches account on the phone (and loads the first time).
            scope.launch {
                var first = true
                env.backend.activeAccount.map { it?.id }.distinctUntilChanged().collect {
                    Log.i(CarLog.CAR, "Home: active account ${if (it == null) "none" else "present"}")
                    // A different user: never leave the previous user's libraries on screen.
                    if (!first) state = Load.Loading
                    first = false
                    reload()
                }
            }
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // Coming back from the player or a library: refresh the counts quietly.
                    if (state is Load.Ready) reload()
                }
            })
        }
    }

    private fun reload() {
        loading?.cancel()
        loading = load(state, { state = it }) {
            val account = env.backend.activeAccount.first() ?: return@load HomeData(account = null)
            coroutineScope {
                val libraries = async { env.backend.libraries() }
                val resume = async { env.backend.resumeItems(SHELF_SIZE) }
                val nextUp = async { env.backend.nextUp(SHELF_SIZE) }
                HomeData(account, env.backend.accounts().size, libraries.await(), resume.await(), nextUp.await())
            }
        }
    }

    override fun content(): Content {
        val header = if (isMainPage) Action.APP_ICON else Action.BACK
        return when (val current = state) {
            Load.Loading -> loadingList(TITLE, header)
            is Load.Failed -> errorContent(TITLE, current, header, extraAction = downloadsAction(), onRetry = ::reload)
            is Load.Ready -> if (current.value.account == null) signedOut(header) else menu(current.value, header)
        }
    }

    @Suppress("DEPRECATION") // see CarScreen.loadingList
    private fun signedOut(header: Action): Content {
        val template = MessageTemplate.Builder(JellyfinException.Kind.NOT_SIGNED_IN.userMessage)
            .setTitle(TITLE)
            .setHeaderAction(header)
            .setIcon(androidx.car.app.model.CarIcon.APP_ICON)
            .addAction(textAction("Retry", ::reload))
            .addAction(downloadsAction())
            .build()
        return Content(template, structure = "signed-out")
    }

    private fun downloadsAction(): Action = textAction("Downloads") { open { DownloadsScreen(carContext, env, it) } }

    private fun menu(data: HomeData, header: Action): Content {
        val entries = entries(data)
        val limit = Paging.usableLimit(env.limits.list, LIST_CAP)
        // A library reached from a later page of Home is one step deeper; two steps below covers it.
        val window = pageWindow(entries.size, startIndex, limit, stepsBelow = 2)
        val shown = entries.drop(startIndex).take(window.count)

        val items = ItemList.Builder()
        shown.forEach { items.addItem(row(it.title, it.text, icon(it.icon), browsable = true, onClick = it.onClick)) }
        if (window.hasMore) {
            items.addItem(moreRow { open { HomeScreen(carContext, env, it, startIndex + window.count, data) } })
        }
        val structure = shown.joinToString("|") { it.title } + if (window.hasMore) "|$MORE" else ""
        return listContent(TITLE, items.build(), structure, header, searchStrip())
    }

    private fun entries(data: HomeData): List<MenuEntry> = buildList {
        add(
            MenuEntry("Continue watching", countText(data.resume.size, "in progress", "Nothing in progress"), R.drawable.ic_car_play) {
                open { EntryListScreen(carContext, env, it, "Continue watching", data.resume, "Nothing in progress.") }
            },
        )
        add(
            MenuEntry("Next up", countText(data.nextUp.size, "ready to watch", "Nothing queued up"), R.drawable.ic_car_next) {
                open { EntryListScreen(carContext, env, it, "Next up", data.nextUp, "Nothing is next up.") }
            },
        )
        data.libraries.forEach { library ->
            add(
                MenuEntry(library.name, null, libraryIcon(library.type)) {
                    open { LibraryScreen(carContext, env, it, LibrarySource.Top(library)) }
                },
            )
        }
        val offline = env.backend.downloads.value.count { it.status == DownloadStatus.COMPLETED }
        add(
            MenuEntry("Downloads", countText(offline, "available offline", "Nothing downloaded yet"), R.drawable.ic_car_download) {
                open { DownloadsScreen(carContext, env, it) }
            },
        )
        if (FlowBudget.canPage(step, stepsBelow = 1)) {
            add(MenuEntry("Search", null, R.drawable.ic_car_search) { open { SearchScreen(carContext, env, it) } })
        }
        if (data.accountCount > 1) {
            add(
                MenuEntry("Switch account", data.account?.let { "${it.userName} · ${it.serverName}" }, R.drawable.ic_car_account) {
                    open { AccountsScreen(carContext, env, it) }
                },
            )
        }
    }

    private fun countText(count: Int, some: String, none: String): String = if (count == 0) none else "$count $some"

    private fun libraryIcon(type: LibraryType): Int = when (type) {
        LibraryType.MOVIES -> R.drawable.ic_car_film
        LibraryType.TV_SHOWS -> R.drawable.ic_car_tv
        LibraryType.BOX_SETS, LibraryType.MIXED -> R.drawable.ic_car_folder
    }

    private companion object {
        const val TITLE = "AAFin"
        const val SHELF_SIZE = 24
    }
}
