package app.aafin.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aafin.R
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.playback.QualityTier
import app.aafin.data.profiles.CarProfile
import app.aafin.phone.CarsViewModel
import app.aafin.phone.DownloadsUiState
import app.aafin.phone.DownloadsViewModel
import app.aafin.phone.Format
import app.aafin.phone.SettingsViewModel
import app.aafin.phone.describeCarKey
import app.aafin.phone.downloadStatusText

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SnackbarMessages(viewModel.messages, snackbar)
    DownloadsList(state, viewModel::pause, viewModel::resume, viewModel::retry, viewModel::delete, modifier)
}

/** The downloads list. Stateless, so it can be shown with any list in tests. */
@Composable
fun DownloadsList(
    state: DownloadsUiState,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (DownloadedItem) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<DownloadedItem?>(null) }

    LazyColumn(
        modifier.fillMaxSize().testTag("downloads-list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("storage"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text(
                        state.storage?.let { "Downloads use ${Format.bytes(it.usedBytes)} · ${Format.bytes(it.freeBytes)} free on this phone" }
                            ?: "Measuring…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.items.isEmpty()) {
            item {
                EmptyBox(
                    "No downloads yet",
                    "Find a film or episode in Browse and tap Download. Downloads play in the car with no signal.",
                    Modifier.padding(top = 32.dp),
                    icon = painterResource(R.drawable.ic_car_download),
                )
            }
        }
        items(state.items, key = { it.itemId }) { item ->
            DownloadRow(item, onPause, onResume, onRetry, onDelete = { deleting = item })
        }
    }

    deleting?.let { item ->
        ConfirmDialog(
            title = "Delete download?",
            text = "“${item.metadata.entry.displayTitle}” will be removed from this phone. It stays on your Jellyfin server.",
            confirmLabel = "Delete",
            onConfirm = {
                onDelete(item.itemId)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRow(
    item: DownloadedItem,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (DownloadedItem) -> Unit,
    onDelete: () -> Unit,
) {
    val title = item.metadata.entry.displayTitle
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.testTag("download-row-${item.itemId}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(item.posterFile?.takeIf { it.isFile }, Modifier.width(56.dp).aspectRatio(2f / 3f))
                HSpace(12)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.metadata.quality.label} · ${item.metadata.serverName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        downloadStatusText(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("download-status-${item.itemId}"),
                    )
                }
            }
            when (item.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED ->
                    LinearProgressIndicator(progress = { item.percent / 100f }, modifier = Modifier.fillMaxWidth())
                DownloadStatus.QUEUED, DownloadStatus.REMOVING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                DownloadStatus.COMPLETED, DownloadStatus.FAILED -> Unit
            }
            if (!item.metadata.playableOnThisPhone) {
                Notice("This original file uses a format this phone may not be able to play. If it won't play in the car, download it as 720p instead.", warning = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (item.status) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> OutlinedButton(
                        onClick = { onPause(item.itemId) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("pause-${item.itemId}"),
                    ) {
                        Icon(painterResource(R.drawable.ic_car_pause), contentDescription = null, modifier = Modifier.size(18.dp))
                        HSpace(8)
                        Text("Pause")
                    }
                    DownloadStatus.PAUSED -> OutlinedButton(
                        onClick = { onResume(item.itemId) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("resume-${item.itemId}"),
                    ) {
                        Icon(painterResource(R.drawable.ic_car_play), contentDescription = null, modifier = Modifier.size(18.dp))
                        HSpace(8)
                        Text("Resume")
                    }
                    DownloadStatus.FAILED -> OutlinedButton(
                        onClick = { onRetry(item) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("retry-${item.itemId}"),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        HSpace(8)
                        Text("Retry")
                    }
                    DownloadStatus.COMPLETED, DownloadStatus.REMOVING -> Unit
                }
                if (item.status != DownloadStatus.REMOVING) {
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp).testTag("delete-${item.itemId}")) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        HSpace(8)
                        // The title makes the button unambiguous for screen readers in a long list.
                        Text("Delete", modifier = Modifier.semantics { contentDescription = "Delete $title" })
                    }
                }
            }
        }
    }
}

/** The Cars tab: every car AAFin has run in, with its quality. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarsScreen(viewModel: CarsViewModel, modifier: Modifier = Modifier) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<CarProfile?>(null) }
    var choosingQuality by remember { mutableStateOf<CarProfile?>(null) }
    var deleting by remember { mutableStateOf<CarProfile?>(null) }

    val cars = profiles ?: return LoadingBox(modifier)
    if (cars.isEmpty()) {
        EmptyBox(
            "No cars yet",
            "Each car appears here the first time you open AAFin on its screen. You can then name it and choose its picture quality.",
            modifier,
            icon = painterResource(R.drawable.ic_phone_car),
        )
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Streaming quality is remembered for each car. Downloads always play at the quality they were downloaded in.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(cars, key = { it.key }) { car ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.testTag("car-${car.key}"),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(car.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text("Quality: ${car.quality.label}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            car.lastUsedAtMs?.let { "Last used ${Format.dateTime(it)}" } ?: "Last used: not recorded",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(describeCarKey(car.key), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { choosingQuality = car }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(painterResource(R.drawable.ic_car_tune), contentDescription = null, modifier = Modifier.size(18.dp))
                                HSpace(8)
                                Text("Quality")
                            }
                            OutlinedButton(onClick = { renaming = car }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                HSpace(8)
                                Text("Rename")
                            }
                            TextButton(onClick = { deleting = car }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                                HSpace(8)
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { car ->
        RenameDialog(car.name, onDismiss = { renaming = null }) { name ->
            viewModel.rename(car.key, name)
            renaming = null
        }
    }
    choosingQuality?.let { car ->
        QualityDialog("Quality in ${car.name}", car.quality, onDismiss = { choosingQuality = null }) { quality ->
            viewModel.setQuality(car.key, quality)
            choosingQuality = null
        }
    }
    deleting?.let { car ->
        ConfirmDialog(
            title = "Delete ${car.name}?",
            text = "Its name and quality setting are forgotten. If the car connects again it comes back as a new car with the default quality.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(car.key)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RenameDialog(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename car") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun qualityNote(tier: QualityTier): String = when (tier) {
    QualityTier.P480_2M -> "Lightest on mobile data."
    QualityTier.P720_4M -> "Recommended: sharp on a car screen, modest data use."
    QualityTier.P1080_8M -> "Full HD. About twice the data of 720p."
    QualityTier.P1080_15M -> "Full HD at high quality. Needs a strong connection."
    QualityTier.ORIGINAL -> "No limit: the file as it is. Can use a lot of data."
}

@Composable
private fun QualityDialog(title: String, selected: QualityTier, onDismiss: () -> Unit, onSelect: (QualityTier) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                QualityTier.entries.forEach { tier ->
                    RadioRow(tier.label, selected = tier == selected, onSelect = { onSelect(tier) }, modifier = Modifier.testTag("tier-${tier.name}")) {
                        Text(qualityNote(tier), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A tappable settings row: title, value underneath, optional chevron. */
@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (value.isNotEmpty()) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) Icon(rememberVectorPainter(Icons.AutoMirrored.Filled.KeyboardArrowRight), contentDescription = null)
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    activeAccountId: String?,
    onManageAccounts: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var choosingQuality by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings")) {
        SectionTitle("Cars")
        SettingRow(
            "Default quality for new cars",
            state.defaultQuality.label,
            onClick = { choosingQuality = true },
            modifier = Modifier.testTag("default-quality"),
        )
        HorizontalDivider()

        SectionTitle("Accounts")
        state.accounts.forEach { item ->
            SettingRow(
                "${item.account.userName} · ${item.account.serverName}" + if (item.account.id == activeAccountId) " (active)" else "",
                item.serverVersion,
                onClick = null,
            )
        }
        SettingRow("Manage accounts", "Add, switch or remove", onClick = onManageAccounts, modifier = Modifier.testTag("manage-accounts"))
        HorizontalDivider()

        SectionTitle("Help")
        SettingRow("Open setup checklist", "The five steps to get AAFin on the car screen", onClick = onOpenSetup, modifier = Modifier.testTag("open-setup"))
        HorizontalDivider()

        SectionTitle("About")
        SettingRow("AAFin version", state.appVersion, onClick = null)
        SettingRow("Licences and credits", "The open-source software AAFin is built with", onClick = onOpenLicences)
        Text(
            "AAFin is a personal app. It is not made by, or connected with, the Jellyfin project or Google.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }

    if (choosingQuality) {
        QualityDialog("Default quality for new cars", state.defaultQuality, onDismiss = { choosingQuality = false }) { quality ->
            viewModel.setDefaultQuality(quality)
            choosingQuality = false
        }
    }
}

private val Licences = listOf(
    "Jellyfin Kotlin SDK" to "Jellyfin contributors · LGPL-3.0",
    "AndroidX: Compose, Media3 (ExoPlayer), Car App Library, Navigation, DataStore, WorkManager" to "The Android Open Source Project · Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "JetBrains · Apache-2.0",
    "OkHttp and Okio" to "Square · Apache-2.0",
    "Coil" to "Coil contributors · Apache-2.0",
    "SLF4J" to "QOS.ch · MIT",
)

@Composable
fun LicencesScreen(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Text(
            "AAFin is built with this open-source software. Jellyfin is a trademark of the Jellyfin project; Android Auto is a " +
                "trademark of Google LLC. The AAFin logo is original artwork.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        Licences.forEach { (name, licence) -> SettingRow(name, licence, onClick = null) }
    }
}
