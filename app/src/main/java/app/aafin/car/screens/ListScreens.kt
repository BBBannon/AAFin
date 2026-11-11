package app.aafin.car.screens

import androidx.car.app.CarContext
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import app.aafin.R
import app.aafin.car.CarEnv
import app.aafin.car.CarFormat
import app.aafin.car.Paging
import app.aafin.car.PosterBoard
import app.aafin.data.accounts.Account
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.MediaEntry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A fixed list of films and episodes (Continue watching, Next up): poster, title, progress.
 * Longer lists continue behind a "More…" row.
 */
class EntryListScreen(
    carContext: CarContext,
    env: CarEnv,
    firstStep: Int,
    private val title: String,
    private val entries: List<MediaEntry>,
    private val emptyMessage: String,
    private val startIndex: Int = 0,
) : CarScreen(carContext, env, firstStep) {

    private val posters = PosterBoard(scope, env.posters, ::invalidate)

    override fun content(): Content {
        val limit = Paging.usableLimit(env.limits.list, LIST_CAP)
        val window = pageWindow(entries.size, startIndex, limit, stepsBelow = 0)
        val shown = entries.drop(startIndex).take(window.count)
        posters.load(shown)

        val items = ItemList.Builder().setNoItemsMessage(emptyMessage)
        shown.forEach { entry ->
            val poster = posters.icon(entry.id)
            items.addItem(
                row(
                    title = CarFormat.entryTitle(entry),
                    text = CarFormat.status(entry),
                    image = poster ?: icon(placeholderIcon(entry.kind)),
                    imageType = if (poster != null) Row.IMAGE_TYPE_LARGE else Row.IMAGE_TYPE_ICON,
                ) { openEntry(entry) },
            )
        }
        if (window.hasMore) {
            items.addItem(
                moreRow { open { EntryListScreen(carContext, env, it, title, entries, emptyMessage, startIndex + window.count) } },
            )
        }
        val structure = shown.joinToString("|") { it.id } + if (window.hasMore) "|$MORE" else ""
        return listContent(title, items.build(), structure)
    }
}

internal fun placeholderIcon(kind: ItemKind): Int = when (kind) {
    ItemKind.SERIES, ItemKind.SEASON, ItemKind.EPISODE -> R.drawable.ic_car_tv
    ItemKind.BOX_SET, ItemKind.FOLDER -> R.drawable.ic_car_folder
    else -> R.drawable.ic_car_film
}

/**
 * Finished downloads, playable with no network at all: everything shown comes from the metadata
 * and poster files saved beside each download.
 */
class DownloadsScreen(
    carContext: CarContext,
    env: CarEnv,
    firstStep: Int,
    private val startIndex: Int = 0,
) : CarScreen(carContext, env, firstStep) {

    private val posters = PosterBoard(scope, env.posters, ::invalidate)
    private var completed: List<DownloadedItem> = completedNow()

    init {
        // A download finishing (or being deleted on the phone) while this is open updates the list.
        scope.launch {
            env.backend.downloads.map { all -> all.filter { it.status == DownloadStatus.COMPLETED } }
                .distinctUntilChanged { old, new -> old.map { it.itemId } == new.map { it.itemId } }
                .collect {
                    completed = it
                    invalidate()
                }
        }
    }

    private fun completedNow() = env.backend.downloads.value.filter { it.status == DownloadStatus.COMPLETED }

    override fun content(): Content {
        val limit = Paging.usableLimit(env.limits.list, LIST_CAP)
        val window = pageWindow(completed.size, startIndex, limit, stepsBelow = 0)
        val shown = completed.drop(startIndex).take(window.count)

        val items = ItemList.Builder()
            .setNoItemsMessage("No downloads yet. Download films and episodes in AAFin on your phone.")
        shown.forEach { item ->
            posters.load(item.itemId, item.posterFile)
            val entry = item.entry
            val poster = posters.icon(item.itemId)
            items.addItem(
                row(
                    title = CarFormat.entryTitle(entry),
                    text = listOfNotNull(CarFormat.status(entry), item.metadata.quality.label).joinToString(" · "),
                    image = poster ?: icon(R.drawable.ic_car_download),
                    imageType = if (poster != null) Row.IMAGE_TYPE_LARGE else Row.IMAGE_TYPE_ICON,
                ) { play(entry) },
            )
        }
        if (window.hasMore) {
            items.addItem(moreRow { open { DownloadsScreen(carContext, env, it, startIndex + window.count) } })
        }
        val structure = shown.joinToString("|") { it.itemId } + if (window.hasMore) "|$MORE" else ""
        return listContent("Downloads", items.build(), structure)
    }
}

/** Pick which signed-in account to browse as. Signing in and out happens on the phone. */
class AccountsScreen(carContext: CarContext, env: CarEnv, firstStep: Int) : CarScreen(carContext, env, firstStep) {

    private class Accounts(val all: List<Account>, val activeId: String?)

    private var state: Load<Accounts> = Load.Loading

    init {
        scope.launch {
            state = Load.Ready(Accounts(env.backend.accounts(), env.backend.activeAccount.first()?.id))
            invalidate()
        }
    }

    override fun content(): Content {
        val accounts = (state as? Load.Ready)?.value ?: return loadingList(TITLE)
        val limit = Paging.usableLimit(env.limits.list, LIST_CAP)
        val shown = accounts.all.take(limit)
        if (shown.isEmpty()) {
            return listContent(TITLE, ItemList.Builder().setNoItemsMessage("Sign in on your phone first.").build(), "empty")
        }
        val items = ItemList.Builder()
        shown.forEach { account ->
            items.addItem(Row.Builder().setTitle(account.userName).addText(account.serverName).build())
        }
        items.setSelectedIndex(shown.indexOfFirst { it.id == accounts.activeId }.coerceAtLeast(0))
        items.setOnSelectedListener { index -> switchTo(shown[index]) }
        return listContent(TITLE, items.build(), shown.joinToString("|") { it.id })
    }

    private fun switchTo(account: Account) {
        scope.launch {
            env.backend.stop()
            try {
                env.backend.setActiveAccount(account.id)
            } catch (e: IllegalArgumentException) {
                // Removed on the phone while this list was open.
                toast("That account is no longer signed in")
            }
            // Home notices the change and reloads.
            screenManager.popToRoot()
        }
    }

    private companion object {
        const val TITLE = "Switch account"
    }
}
