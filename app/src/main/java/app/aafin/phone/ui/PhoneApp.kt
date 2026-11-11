package app.aafin.phone.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.aafin.R
import app.aafin.data.accounts.Account
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.phone.AddAccountViewModel
import app.aafin.phone.AppUiState
import app.aafin.phone.AppViewModel
import app.aafin.phone.CarsViewModel
import app.aafin.phone.DownloadsViewModel
import app.aafin.phone.GridViewModel
import app.aafin.phone.ItemViewModel
import app.aafin.phone.LibrariesViewModel
import app.aafin.phone.PhoneBackend
import app.aafin.phone.SearchViewModel
import app.aafin.phone.SeriesViewModel
import app.aafin.phone.SettingsViewModel
import kotlinx.serialization.Serializable

// --- Destinations ------------------------------------------------------------------------------

@Serializable data object BrowseRoute

/** A library ([libraryType] set) or the inside of a collection or folder ([libraryType] null). */
@Serializable data class GridRoute(val id: String, val name: String, val libraryType: String? = null)

@Serializable data class SeriesRoute(val id: String, val name: String)

@Serializable data class ItemRoute(val id: String, val name: String)

@Serializable data object SearchRoute

@Serializable data object DownloadsRoute

@Serializable data object CarsRoute

@Serializable data object SettingsRoute

@Serializable data object AccountsRoute

@Serializable data object AddAccountRoute

@Serializable data object SetupGuideRoute

@Serializable data object LicencesRoute

private enum class Tab(val label: String) { BROWSE("Browse"), DOWNLOADS("Downloads"), CARS("Cars"), SETTINGS("Settings") }

/** What the top bar and bottom bar need to know about the screen in front. */
private data class ScreenInfo(val title: String, val tab: Tab, val isTabRoot: Boolean)

private fun screenInfo(entry: NavBackStackEntry?): ScreenInfo {
    val destination = entry?.destination
    return when {
        destination == null || destination.hasRoute<BrowseRoute>() -> ScreenInfo("Browse", Tab.BROWSE, true)
        destination.hasRoute<GridRoute>() -> ScreenInfo(entry.toRoute<GridRoute>().name, Tab.BROWSE, false)
        destination.hasRoute<SeriesRoute>() -> ScreenInfo(entry.toRoute<SeriesRoute>().name, Tab.BROWSE, false)
        destination.hasRoute<ItemRoute>() -> ScreenInfo(entry.toRoute<ItemRoute>().name, Tab.BROWSE, false)
        destination.hasRoute<SearchRoute>() -> ScreenInfo("Search", Tab.BROWSE, false)
        destination.hasRoute<DownloadsRoute>() -> ScreenInfo("Downloads", Tab.DOWNLOADS, true)
        destination.hasRoute<CarsRoute>() -> ScreenInfo("Cars", Tab.CARS, true)
        destination.hasRoute<SettingsRoute>() -> ScreenInfo("Settings", Tab.SETTINGS, true)
        destination.hasRoute<AccountsRoute>() -> ScreenInfo("Accounts", Tab.SETTINGS, false)
        destination.hasRoute<AddAccountRoute>() -> ScreenInfo("Add account", Tab.SETTINGS, false)
        destination.hasRoute<SetupGuideRoute>() -> ScreenInfo("Setup checklist", Tab.SETTINGS, false)
        destination.hasRoute<LicencesRoute>() -> ScreenInfo("Licences and credits", Tab.SETTINGS, false)
        else -> ScreenInfo("AAFin", Tab.BROWSE, false)
    }
}

// --- Root --------------------------------------------------------------------------------------

/**
 * The whole phone app. First run shows the setup checklist; with no account it shows
 * "Add account"; otherwise the four tabs.
 */
@Composable
fun PhoneApp(backend: PhoneBackend) {
    val app: AppViewModel = viewModel { AppViewModel(backend) }
    val state by app.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val active = state.active
        when {
            state.loading -> LoadingBox(label = "Starting")
            !state.setupSeen -> SetupScreen(onContinue = app::finishSetup)
            active == null -> SignedOutScreen(backend)
            else -> MainScreen(backend, app, state, active)
        }
    }
}

/** Signed out: there is nothing to do but add an account, so there are no tabs. */
@Composable
private fun SignedOutScreen(backend: PhoneBackend) {
    val viewModel: AddAccountViewModel = viewModel(key = "add-first-account") { AddAccountViewModel(backend) }
    // Nothing to do on success: the saved account makes PhoneApp switch to the tabs.
    AddAccountScreen(
        viewModel,
        onDone = {},
        onLeave = null,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).testTag("signed-out"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(backend: PhoneBackend, app: AppViewModel, state: AppUiState, active: Account) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val screen = screenInfo(entry)
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(app.messages, snackbar)

    // A different account means a different library: start Browse afresh. Also the moment to
    // check for downloads left stranded by a new sign-in.
    var shownAccount by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (shownAccount.id != active.id) {
            nav.navigate(BrowseRoute) { popUpTo(nav.graph.id) { inclusive = true } }
        }
        shownAccount = active
        app.checkDownloadsAfterSignIn()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!screen.isTabRoot) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    AccountSwitcher(
                        accounts = state.accounts,
                        active = active,
                        onSwitch = app::switchAccount,
                        onAdd = { nav.navigate(AddAccountRoute) { launchSingleTop = true } },
                        onManage = { nav.navigate(AccountsRoute) { launchSingleTop = true } },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = screen.tab == tab,
                        onClick = { nav.openTab(tab) },
                        icon = {
                            Icon(
                                when (tab) {
                                    Tab.BROWSE -> rememberVectorPainter(Icons.Default.Home)
                                    Tab.DOWNLOADS -> painterResource(R.drawable.ic_car_download)
                                    Tab.CARS -> painterResource(R.drawable.ic_phone_car)
                                    Tab.SETTINGS -> rememberVectorPainter(Icons.Default.Settings)
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(tab.label, maxLines = 1) },
                        modifier = Modifier.testTag("tab-${tab.label}"),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        // Wide screens (landscape, tablets): keep content to a readable width, centred.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            MainNavHost(backend, app, state, active, nav, snackbar, Modifier.widthIn(max = 840.dp))
        }
    }
}

/** Switches tab. Browse is the home tab: Back from any other tab returns to it. */
private fun NavHostController.openTab(tab: Tab) {
    val route: Any = when (tab) {
        Tab.BROWSE -> BrowseRoute
        Tab.DOWNLOADS -> DownloadsRoute
        Tab.CARS -> CarsRoute
        Tab.SETTINGS -> SettingsRoute
    }
    navigate(route) {
        popUpTo<BrowseRoute>()
        launchSingleTop = true
    }
}

@Composable
private fun AccountSwitcher(
    accounts: List<Account>,
    active: Account,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.testTag("account-switcher")) {
            Icon(Icons.Default.AccountCircle, contentDescription = "Account:", modifier = Modifier.size(24.dp))
            HSpace(8)
            Text(active.userName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            accounts.forEach { account ->
                val isActive = account.id == active.id
                DropdownMenuItem(
                    text = {
                        Text("${account.userName} · ${account.serverName}" + if (isActive) " (active)" else "")
                    },
                    leadingIcon = {
                        if (isActive) Icon(Icons.Default.Check, contentDescription = null) else Box(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        if (!isActive) onSwitch(account.id)
                    },
                    modifier = Modifier.testTag("switch-to-${account.userName}"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Add account") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    open = false
                    onAdd()
                },
            )
            DropdownMenuItem(
                text = { Text("Manage accounts") },
                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                onClick = {
                    open = false
                    onManage()
                },
            )
        }
    }
}

@Composable
private fun MainNavHost(
    backend: PhoneBackend,
    app: AppViewModel,
    state: AppUiState,
    active: Account,
    nav: NavHostController,
    snackbar: SnackbarHostState,
    modifier: Modifier,
) {
    fun open(entry: MediaEntry) {
        when (entry.kind) {
            ItemKind.SERIES -> nav.navigate(SeriesRoute(entry.id, entry.name))
            // A season or episode found by search opens its series, where the episodes are listed.
            ItemKind.SEASON, ItemKind.EPISODE ->
                nav.navigate(SeriesRoute(entry.seriesId ?: entry.id, entry.seriesName ?: entry.name))
            ItemKind.BOX_SET, ItemKind.FOLDER -> nav.navigate(GridRoute(entry.id, entry.name))
            ItemKind.MOVIE, ItemKind.VIDEO, ItemKind.OTHER -> nav.navigate(ItemRoute(entry.id, entry.name))
        }
    }

    NavHost(nav, startDestination = BrowseRoute, modifier = modifier) {
        composable<BrowseRoute> {
            val viewModel: LibrariesViewModel = viewModel { LibrariesViewModel(backend) }
            LibrariesScreen(
                viewModel,
                onOpenLibrary = { nav.navigate(GridRoute(it.id, it.name, it.type.name)) },
                onSearch = { nav.navigate(SearchRoute) },
            )
        }
        composable<GridRoute> { entry ->
            val route = entry.toRoute<GridRoute>()
            val viewModel: GridViewModel = viewModel {
                GridViewModel(backend, route.id, route.name, route.libraryType?.let(LibraryType::valueOf))
            }
            GridScreen(viewModel, onOpen = ::open)
        }
        composable<SeriesRoute> { entry ->
            val route = entry.toRoute<SeriesRoute>()
            val viewModel: SeriesViewModel = viewModel { SeriesViewModel(backend, route.id) }
            SeriesScreen(viewModel, snackbar)
        }
        composable<ItemRoute> { entry ->
            val route = entry.toRoute<ItemRoute>()
            val viewModel: ItemViewModel = viewModel { ItemViewModel(backend, route.id) }
            ItemScreen(viewModel, snackbar)
        }
        composable<SearchRoute> {
            val viewModel: SearchViewModel = viewModel { SearchViewModel(backend) }
            SearchScreen(viewModel, onOpen = ::open)
        }
        composable<DownloadsRoute> {
            val viewModel: DownloadsViewModel = viewModel { DownloadsViewModel(backend) }
            DownloadsScreen(viewModel, snackbar)
        }
        composable<CarsRoute> {
            val viewModel: CarsViewModel = viewModel { CarsViewModel(backend) }
            CarsScreen(viewModel)
        }
        composable<SettingsRoute> {
            val viewModel: SettingsViewModel = viewModel { SettingsViewModel(backend) }
            SettingsScreen(
                viewModel,
                activeAccountId = active.id,
                onManageAccounts = { nav.navigate(AccountsRoute) },
                onOpenSetup = { nav.navigate(SetupGuideRoute) },
                onOpenLicences = { nav.navigate(LicencesRoute) },
            )
        }
        composable<AccountsRoute> {
            AccountsScreen(
                accounts = state.accounts,
                activeId = active.id,
                onSwitch = app::switchAccount,
                onRemove = app::removeAccount,
                onAdd = { nav.navigate(AddAccountRoute) { launchSingleTop = true } },
            )
        }
        composable<AddAccountRoute> {
            val viewModel: AddAccountViewModel = viewModel { AddAccountViewModel(backend) }
            // On success the new account becomes active, which sends the app back to Browse.
            // (Signing in again as an account that is already active changes nothing there, so leave by hand.)
            AddAccountScreen(
                viewModel,
                onDone = { nav.popBackStack<AddAccountRoute>(inclusive = true) },
                onLeave = { nav.popBackStack() },
            )
        }
        composable<SetupGuideRoute> { SetupScreen(onContinue = null) }
        composable<LicencesRoute> { LicencesScreen() }
    }
}
