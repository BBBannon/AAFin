package app.aafin.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aafin.data.accounts.Account
import app.aafin.data.jellyfin.ServerInfo
import app.aafin.phone.AddAccountStep
import app.aafin.phone.AddAccountUiState
import app.aafin.phone.AddAccountViewModel
import app.aafin.phone.QuickConnectUi
import app.aafin.phone.serverWarnings

/**
 * Add a Jellyfin account: server address, then Quick Connect or username and password.
 *
 * @param onDone called after a successful sign-in (the account is already saved and active).
 * @param onLeave called when Back is pressed at the first stage; null if there is nowhere to go back to.
 */
@Composable
fun AddAccountScreen(viewModel: AddAccountViewModel, onDone: (Account) -> Unit, onLeave: (() -> Unit)?, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.signedIn.collect(onDone)
    }
    BackHandler(enabled = state.step != AddAccountStep.SERVER || onLeave != null) {
        if (!viewModel.back()) onLeave?.invoke()
    }

    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state.step) {
                AddAccountStep.SERVER -> ServerStep(state, viewModel::checkServer)
                AddAccountStep.METHOD -> state.server?.let { server ->
                    MethodStep(server, viewModel::startQuickConnect, viewModel::usePassword, onChangeServer = { viewModel.back() })
                }
                AddAccountStep.QUICK_CONNECT -> state.server?.let { server ->
                    QuickConnectStep(server, state.quickConnect, viewModel::startQuickConnect, viewModel::cancelQuickConnect, viewModel::usePassword)
                }
                AddAccountStep.PASSWORD -> state.server?.let { server ->
                    PasswordStep(server, state, viewModel::signIn, onBack = { viewModel.back() })
                }
            }
        }
    }
}

@Composable
private fun StepHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ServerStep(state: AddAccountUiState, onCheck: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }

    StepHeading("Add your Jellyfin server", "Type the address you use to open Jellyfin in a web browser.")
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Server address") },
        placeholder = { Text("jellyfin.example.com") },
        singleLine = true,
        enabled = !state.checkingServer,
        isError = state.serverError != null,
        supportingText = {
            Text(state.serverError ?: "No need to type https:// — AAFin adds it.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onCheck(address) }),
        modifier = Modifier.fillMaxWidth().testTag("server-address"),
    )
    Button(
        onClick = { onCheck(address) },
        enabled = !state.checkingServer && address.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("server-continue"),
    ) {
        if (state.checkingServer) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            HSpace(12)
            Text("Looking for the server…")
        } else {
            Text("Find server")
        }
    }
}

@Composable
private fun ServerCard(server: ServerInfo) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.testTag("server-card")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Server found", tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(server.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Jellyfin ${server.version ?: "(version not reported)"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(server.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    serverWarnings(server).forEach { Notice(it, warning = true) }
}

@Composable
private fun MethodStep(server: ServerInfo, onQuickConnect: () -> Unit, onPassword: () -> Unit, onChangeServer: () -> Unit) {
    StepHeading("Sign in", "Choose how to sign in to this server.")
    ServerCard(server)
    Button(onClick = onQuickConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("use-quick-connect")) {
        Text("Sign in with Quick Connect")
    }
    OutlinedButton(onClick = onPassword, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("use-password")) {
        Text("Use username and password")
    }
    TextButton(onClick = onChangeServer, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use a different server") }
}

@Composable
private fun QuickConnectStep(
    server: ServerInfo,
    quickConnect: QuickConnectUi,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onPassword: () -> Unit,
) {
    StepHeading("Quick Connect", "Signing in to ${server.name}.")

    when (quickConnect) {
        QuickConnectUi.Starting -> BusyLine("Asking the server for a code…")

        is QuickConnectUi.Code -> {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(vertical = 24.dp, horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Your code", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(
                        quickConnect.code,
                        fontSize = 56.sp,
                        lineHeight = 64.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .testTag("quick-connect-code")
                            // Read out digit by digit rather than as one large number.
                            .semantics { contentDescription = "Code: " + quickConnect.code.toList().joinToString(" ") },
                    )
                }
            }
            Text(
                "On a phone, TV or browser that is already signed in to Jellyfin, open Settings, choose Quick Connect and type in this code.",
                style = MaterialTheme.typography.bodyLarge,
            )
            BusyLine("Waiting for you to approve the code…")
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("quick-connect-cancel")) {
                Text("Cancel")
            }
            TextButton(onClick = onPassword, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use username and password instead") }
        }

        QuickConnectUi.Authenticating -> BusyLine("Code approved. Signing in…")

        QuickConnectUi.TimedOut -> {
            Notice("The code wasn't approved in time, so it has expired. Get a new code and try again.", warning = true)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("quick-connect-retry")) {
                Text("Get a new code")
            }
            OutlinedButton(onClick = onPassword, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("Use username and password")
            }
        }

        is QuickConnectUi.Failed -> {
            Notice(quickConnect.message, warning = true, modifier = Modifier.testTag("quick-connect-error"))
            if (quickConnect.offerPassword) {
                Button(onClick = onPassword, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Use username and password") }
            } else {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("quick-connect-retry")) {
                    Text("Try again")
                }
                OutlinedButton(onClick = onPassword, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Use username and password")
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
        }
    }
}

@Composable
private fun BusyLine(text: String) {
    Row(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("quick-connect-status"))
    }
}

@Composable
private fun PasswordStep(server: ServerInfo, state: AddAccountUiState, onSignIn: (String, String) -> Unit, onBack: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    // Deliberately not saved across process death: a password should not sit in saved state.
    var password by remember { mutableStateOf("") }

    StepHeading("Sign in to ${server.name}", "Use your Jellyfin username and password.")
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("Username") },
        singleLine = true,
        enabled = !state.signingIn,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().testTag("username"),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        enabled = !state.signingIn,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSignIn(username, password) }),
        modifier = Modifier.fillMaxWidth().testTag("password"),
    )
    state.passwordError?.let { Notice(it, warning = true, modifier = Modifier.testTag("password-error")) }
    Button(
        onClick = { onSignIn(username, password) },
        enabled = !state.signingIn,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sign-in"),
    ) {
        if (state.signingIn) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            HSpace(12)
            Text("Signing in…")
        } else {
            Text("Sign in")
        }
    }
    TextButton(onClick = onBack, enabled = !state.signingIn, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
}

/** The stored accounts: tap one to make it active, remove with the bin, add another. */
@Composable
fun AccountsScreen(
    accounts: List<Account>,
    activeId: String?,
    onSwitch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var removing by remember { mutableStateOf<Account?>(null) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(
                "The active account is the one AAFin browses and plays as, on the phone and in the car.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(accounts, key = { it.id }) { account ->
            val active = account.id == activeId
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = active, role = Role.RadioButton, onClick = { onSwitch(account.id) })
                        .testTag("account-${account.userName}")
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = active, onClick = null)
                    HSpace(16)
                    Column(Modifier.weight(1f)) {
                        Text(account.userName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            account.serverName + if (active) " · Active" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { removing = account }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove ${account.userName} on ${account.serverName}")
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("add-account")) {
                Icon(Icons.Default.Add, contentDescription = null)
                HSpace(8)
                Text("Add another account")
            }
        }
    }

    removing?.let { account ->
        ConfirmDialog(
            title = "Remove ${account.userName}?",
            text = "AAFin will forget this account on ${account.serverName}. Anything it downloaded stays on the phone and still " +
                "plays in the car; delete those in Downloads if you want the space back.",
            confirmLabel = "Remove",
            onConfirm = {
                onRemove(account.id)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}
