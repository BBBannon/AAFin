package app.aafin.phone.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aafin.R
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.phone.DownloadChoice
import app.aafin.phone.DownloadDialogUi
import app.aafin.phone.DownloadRequester
import app.aafin.phone.GridViewModel
import app.aafin.phone.ItemViewModel
import app.aafin.phone.LibrariesViewModel
import app.aafin.phone.Loadable
import app.aafin.phone.PosterEntry
import app.aafin.phone.SearchViewModel
import app.aafin.phone.SeriesViewModel
import app.aafin.phone.downloadBadgeText
import app.aafin.phone.entrySubtitle

/** The Browse tab: the account's libraries, and a way into Search. */
@Composable
fun LibrariesScreen(viewModel: LibrariesViewModel, onOpenLibrary: (Library) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val libraries = state) {
        Loadable.Loading -> LoadingBox(modifier)
        is Loadable.Failed -> ErrorBox(libraries.message, viewModel::load, modifier)
        is Loadable.Ready -> LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                FilledTonalButton(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("open-search")) {
                    Icon(painterResource(R.drawable.ic_car_search), contentDescription = null)
                    HSpace(8)
                    Text("Search films and TV")
                }
            }
            item { PlayInCarHint() }
            if (libraries.value.isEmpty()) {
                item {
                    Notice("This account has no film or TV libraries. Music and live TV aren't shown in AAFin.")
                }
            }
            items(libraries.value, key = { it.library.id }) { card ->
                Card(
                    onClick = { onOpenLibrary(card.library) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.testTag("library-${card.library.name}"),
                ) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Artwork(
                            card.imageUrl,
                            Modifier.width(112.dp).aspectRatio(16f / 9f),
                            placeholder = if (card.library.type == LibraryType.TV_SHOWS) R.drawable.ic_car_tv else R.drawable.ic_car_film,
                        )
                        HSpace(16)
                        Column(Modifier.weight(1f)) {
                            Text(card.library.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                when (card.library.type) {
                                    LibraryType.MOVIES -> "Films"
                                    LibraryType.TV_SHOWS -> "TV series"
                                    LibraryType.BOX_SETS -> "Collections"
                                    LibraryType.MIXED -> "Films and TV"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** A grid of posters for a library, collection or folder; more load as the end comes into view. */
@Composable
fun GridScreen(viewModel: GridViewModel, onOpen: (MediaEntry) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.items.isEmpty()) {
        when {
            state.error != null -> ErrorBox(state.error.orEmpty(), viewModel::loadMore, modifier)
            state.endReached -> EmptyBox("Nothing here yet", "This library has no films or series AAFin can show.", modifier)
            else -> LoadingBox(modifier)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = modifier.fillMaxSize().testTag("poster-grid"),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(state.items, key = { it.entry.id }) { item ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = "Open", role = Role.Button) { onOpen(item.entry) },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Artwork(
                    item.posterUrl,
                    Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                    placeholder = placeholderFor(item.entry.kind),
                )
                Text(item.entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.entry.productionYear?.let {
                    Text("$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!state.endReached) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.error != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = viewModel::loadMore) { Text("Try again") }
                        }
                    } else {
                        // Coming into view is the cue to fetch the next page.
                        LaunchedEffect(state.items.size) { viewModel.loadMore() }
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

private fun placeholderFor(kind: ItemKind): Int = when (kind) {
    ItemKind.SERIES, ItemKind.SEASON, ItemKind.EPISODE -> R.drawable.ic_car_tv
    ItemKind.BOX_SET, ItemKind.FOLDER -> R.drawable.ic_car_folder
    else -> R.drawable.ic_car_film
}

/** A series: season chips, "Download season", and the episodes with a Download button each. */
@Composable
fun SeriesScreen(viewModel: SeriesViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads = rememberDownloadsById(viewModel.downloads)
    DownloadFlow(viewModel.downloads, snackbar)

    val seasons = when (val seasons = state.seasons) {
        Loadable.Loading -> return LoadingBox(modifier)
        is Loadable.Failed -> return ErrorBox(seasons.message, viewModel::load, modifier)
        is Loadable.Ready -> seasons.value
    }

    Column(modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons, key = { it.id }) { season ->
                    FilterChip(
                        selected = season.id == state.selectedSeasonId,
                        onClick = { viewModel.selectSeason(season.id) },
                        label = { Text(season.name) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
        when (val episodes = state.episodes) {
            Loadable.Loading -> LoadingBox()
            is Loadable.Failed -> ErrorBox(episodes.message, { viewModel.selectSeason(state.selectedSeasonId) })
            is Loadable.Ready -> LazyColumn(
                Modifier.fillMaxSize().testTag("episode-list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (episodes.value.isEmpty()) {
                    item { Notice("No episodes here yet.") }
                } else {
                    item {
                        val remaining = episodes.value.count { it.entry.id !in downloads }
                        Button(
                            onClick = viewModel::downloadSeason,
                            enabled = remaining > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("download-season"),
                        ) {
                            Icon(painterResource(R.drawable.ic_car_download), contentDescription = null)
                            HSpace(8)
                            Text(
                                when {
                                    remaining == 0 -> "Whole season downloaded"
                                    seasons.size > 1 -> "Download season ($remaining episodes)"
                                    else -> "Download all ($remaining episodes)"
                                },
                            )
                        }
                    }
                    item { PlayInCarHint() }
                }
                items(episodes.value, key = { it.entry.id }) { episode ->
                    EpisodeRow(episode, downloads[episode.entry.id]) {
                        viewModel.downloads.request(episode.entry.displayTitle, listOf(episode.entry))
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: PosterEntry, download: DownloadedItem?, onDownload: () -> Unit) {
    val entry = episode.entry
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Artwork(episode.posterUrl, Modifier.width(120.dp).aspectRatio(16f / 9f), placeholder = R.drawable.ic_car_tv)
        HSpace(12)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(entry.indexNumber?.let { "$it." }, entry.name).joinToString(" "),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                entry.runtimeMs?.takeIf { it > 0 }?.let(app.aafin.phone.Format::duration),
                "Watched".takeIf { entry.played },
            ).joinToString(" · ")
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (download != null) DownloadBadge(download)
        }
        if (download == null) {
            FilledTonalIconButton(onClick = onDownload, modifier = Modifier.size(48.dp).testTag("download-${entry.id}")) {
                Icon(painterResource(R.drawable.ic_car_download), contentDescription = "Download ${entry.displayTitle}")
            }
        }
    }
}

/** "Downloaded" / "Downloading 42%" for something already in the downloads. */
@Composable
private fun DownloadBadge(download: DownloadedItem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (download.status == DownloadStatus.COMPLETED) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Text(
            downloadBadgeText(download),
            style = MaterialTheme.typography.labelLarge,
            color = if (download.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** One film: poster, details, and Download. */
@Composable
fun ItemScreen(viewModel: ItemViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads = rememberDownloadsById(viewModel.downloads)
    DownloadFlow(viewModel.downloads, snackbar)

    val detail = when (val detail = state) {
        Loadable.Loading -> return LoadingBox(modifier)
        is Loadable.Failed -> return ErrorBox(detail.message, viewModel::load, modifier)
        is Loadable.Ready -> detail.value
    }
    val entry = detail.entry
    val download = downloads[entry.id]

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Artwork(detail.posterUrl, Modifier.width(128.dp).aspectRatio(2f / 3f), placeholder = placeholderFor(entry.kind))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.displayTitle, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                val subtitle = entrySubtitle(entry)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (download != null) DownloadBadge(download)
            }
        }
        if (entry.kind.isPlayable) {
            if (download == null) {
                Button(onClick = viewModel::download, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("download")) {
                    Icon(painterResource(R.drawable.ic_car_download), contentDescription = null)
                    HSpace(8)
                    Text("Download")
                }
            } else {
                Notice("This is in your downloads. Manage it in the Downloads tab.")
            }
        }
        PlayInCarHint()
        entry.overview?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** Search: results appear as you type. */
@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpen: (MediaEntry) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text("Search films and TV") },
            singleLine = true,
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("search-field"),
        )
        when (val results = state.results) {
            null -> EmptyBox("Search your library", "Type the name of a film, series or episode.", icon = painterResource(R.drawable.ic_car_search))
            Loadable.Loading -> LoadingBox(label = "Searching")
            is Loadable.Failed -> ErrorBox(results.message, { viewModel.onQueryChange(state.query) })
            is Loadable.Ready -> if (results.value.isEmpty()) {
                EmptyBox("Nothing found", "Nothing in your library matches “${state.query.trim()}”.")
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results.value, key = { it.entry.id }) { result ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClickLabel = "Open", role = Role.Button) { onOpen(result.entry) }
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(result.posterUrl, Modifier.width(56.dp).aspectRatio(2f / 3f), placeholder = placeholderFor(result.entry.kind))
                            HSpace(12)
                            Column(Modifier.weight(1f)) {
                                Text(result.entry.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val kind = when (result.entry.kind) {
                                    ItemKind.SERIES -> "Series"
                                    ItemKind.EPISODE -> "Episode"
                                    else -> "Film"
                                }
                                Text(
                                    listOfNotNull(kind, result.entry.productionYear?.toString()).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDownloadsById(requester: DownloadRequester): Map<String, DownloadedItem> {
    val downloads by requester.existing.collectAsStateWithLifecycle()
    return remember(downloads) { downloads.associateBy { it.itemId } }
}

/**
 * Everything around a [DownloadRequester]: the choice dialog, the notification-permission
 * request before the first download (Android 13+), the "adding…" line and the result message.
 */
@Composable
fun DownloadFlow(requester: DownloadRequester, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val dialog by requester.dialog.collectAsStateWithLifecycle()
    val progress by requester.progress.collectAsStateWithLifecycle()
    SnackbarMessages(requester.messages, snackbar)

    // Downloads run whether or not notifications are allowed; the permission only decides
    // whether the progress notification can be seen. So the answer never blocks the download.
    val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requester.confirm(notificationsDenied = !granted)
    }

    dialog?.let {
        DownloadChoiceDialog(
            dialog = it,
            onSelect = requester::select,
            onDismiss = requester::dismiss,
            onConfirm = {
                if (needsNotificationPermission(context)) {
                    askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    requester.confirm()
                }
            },
        )
    }
    progress?.let { BusyDialog(it) }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** The Original / 720p choice. Stateless: [dialog] says what is selected. */
@Composable
fun DownloadChoiceDialog(dialog: DownloadDialogUi, onSelect: (DownloadQuality) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("download-dialog"),
        title = { Text("Download") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (dialog.entries.size == 1) dialog.title else "${dialog.title} · ${dialog.entries.size} episodes",
                    style = MaterialTheme.typography.titleMedium,
                )
                VSpace(4)
                dialog.options.forEach { option ->
                    RadioRow(
                        title = option.quality.label,
                        selected = option.quality == dialog.selected,
                        enabled = option.enabled,
                        onSelect = { onSelect(option.quality) },
                        modifier = Modifier.testTag("quality-${option.quality.name}"),
                    ) {
                        if (option.enabled) {
                            Text(
                                DownloadChoice.sizeLabel(option, dialog.checkingSize),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("size-${option.quality.name}"),
                            )
                        }
                        Text(option.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("download-confirm")) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A small "working…" dialog that cannot be dismissed; shown while downloads are being queued. */
@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
    )
}
