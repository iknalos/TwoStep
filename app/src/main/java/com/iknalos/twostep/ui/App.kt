@file:OptIn(ExperimentalMaterial3Api::class)

package com.iknalos.twostep.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.iknalos.twostep.AppModel
import com.iknalos.twostep.MainActivity
import com.iknalos.twostep.PortraitCaptureActivity
import com.iknalos.twostep.Screen
import com.iknalos.twostep.UpdateState
import com.iknalos.twostep.VaultState
import com.iknalos.twostep.data.Account
import com.iknalos.twostep.otp.Base32
import com.iknalos.twostep.otp.OtpAlgorithm
import com.iknalos.twostep.otp.OtpAuthUri
import com.iknalos.twostep.otp.OtpParams
import com.iknalos.twostep.otp.Totp
import com.iknalos.twostep.update.Updater
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Current time, ticking 5x a second: drives every code and countdown on screen. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    return now
}

@Composable
fun TwoStepApp(model: AppModel, activity: MainActivity) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.note) {
        model.note?.let { snackbar.showSnackbar(it.text, withDismissAction = true) }
    }

    if (model.locked) {
        LockScreen(onUnlock = { activity.promptUnlock() })
        return
    }

    BackHandler(enabled = model.screen != Screen.Home) { model.screen = Screen.Home }

    when (val s = model.screen) {
        Screen.Home -> HomeScreen(model, activity, snackbar)
        is Screen.Add -> AddScreen(model, s.prefill, snackbar, onClose = { model.screen = Screen.Home })
        Screen.Settings -> SettingsScreen(model, activity, snackbar, onBack = { model.screen = Screen.Home })
    }
}

// ---------------------------------------------------------------- lock

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("TwoStep is locked", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(model: AppModel, activity: MainActivity, snackbar: SnackbarHostState) {
    val now = rememberNow()
    var chooser by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }

    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        try {
            model.screen = Screen.Add(OtpAuthUri.parse(text))
        } catch (e: IllegalArgumentException) {
            model.say(e.message ?: "That QR code isn't a 2FA setup code")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TwoStep") },
                actions = {
                    IconButton(onClick = { model.screen = Screen.Settings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (model.vaultState == VaultState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = { chooser = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add account") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = pad.calculateTopPadding() + 8.dp,
                bottom = pad.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { UpdateBanner(model, model.update, inSettings = false) }
            model.clockWarning()?.let { w -> item { Notice(w, warning = true) } }
            val state = model.vaultState
            if (state is VaultState.Broken) {
                item {
                    Notice(
                        "TwoStep can't read its saved accounts (${state.reason}). Nothing has been deleted. " +
                            "If you have a backup, import it in Settings; the unreadable file is kept aside.",
                        warning = true,
                    )
                }
            }
            if (model.needsBackup && model.accounts.isNotEmpty()) {
                item {
                    Notice(
                        "Some accounts aren't in a backup yet. If this phone is lost or reset, their codes go with it.",
                        actionLabel = "Back up now",
                        onAction = { model.screen = Screen.Settings },
                    )
                }
            }
            if (state == VaultState.Ready && model.accounts.isEmpty()) item { EmptyState() }
            items(model.accounts, key = { it.id }) { a ->
                AccountCard(
                    a, now,
                    onCopy = { code -> activity.copyCode(code) },
                    onRename = { renaming = a },
                    onDelete = { deleting = a },
                    onMove = { d -> model.move(a.id, d) },
                )
            }
        }
    }

    if (chooser) {
        AlertDialog(
            onDismissRequest = { chooser = false },
            title = { Text("Add an account") },
            text = { Text("Scan the QR code the site shows you, or type in its setup key if you can't scan.") },
            confirmButton = {
                Button(onClick = {
                    chooser = false
                    scan.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point the camera at the site's 2FA QR code")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                            .setCaptureActivity(PortraitCaptureActivity::class.java)
                    )
                }) { Text("Scan QR code") }
            },
            dismissButton = {
                TextButton(onClick = { chooser = false; model.screen = Screen.Add(null) }) { Text("Enter setup key") }
            },
        )
    }

    renaming?.let { a ->
        var issuer by remember(a.id) { mutableStateOf(a.issuer) }
        var name by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(issuer, { issuer = it }, label = { Text("Site") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Account") }, singleLine = true)
                }
            },
            confirmButton = { Button(onClick = { model.rename(a.id, issuer, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${AppModel.label(a)}?") },
            text = {
                Text(
                    "TwoStep won't be able to make codes for it any more. Only do this after turning 2FA off on " +
                        "that site, or if you have its recovery codes, or you could be locked out."
                )
            },
            confirmButton = {
                Button(onClick = { model.delete(a.id); deleting = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun EmptyState() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No accounts yet", style = MaterialTheme.typography.titleLarge)
            Text("To turn on 2FA for GitHub:", fontWeight = FontWeight.SemiBold)
            Text("1. On your laptop, open github.com > Settings > Password and authentication > Enable two-factor authentication.")
            Text("2. Here, tap Add account > Scan QR code and point the phone at the QR code.")
            Text("3. Tap Save, then type the 6-digit code TwoStep shows into GitHub.")
            Text("4. Download GitHub's recovery codes and keep them somewhere other than this phone.")
        }
    }
}

@Composable
private fun AccountCard(
    a: Account,
    now: Long,
    onCopy: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val key = remember(a.secret) { runCatching { Base32.decode(a.secret) }.getOrNull() }
    val step = Totp.counterAt(now / 1000, a.period)
    val code = remember(a.id, a.secret, a.digits, a.algorithm, step) {
        key?.let { Totp.hotp(it, step, a.digits, a.algorithm) } ?: "-".repeat(a.digits)
    }
    val left = Totp.secondsLeft(now, a.period)
    val ending = left <= 5.0
    val next = if (ending && key != null) Totp.hotp(key, step + 1, a.digits, a.algorithm) else null
    var menu by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().clickable { onCopy(code) }) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.issuer.ifEmpty { a.name.ifEmpty { "Account" } }, style = MaterialTheme.typography.titleMedium)
                if (a.issuer.isNotEmpty() && a.name.isNotEmpty()) {
                    Text(a.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    Totp.grouped(code),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (ending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (next != null) {
                    Text("next ${Totp.grouped(next)}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { (left / a.period).toFloat() },
                    modifier = Modifier.size(42.dp),
                    strokeWidth = 4.dp,
                    color = if (ending) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text("${ceil(left).toInt()}", style = MaterialTheme.typography.labelMedium)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Copy code") }, onClick = { menu = false; onCopy(code) })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMove(-1) })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, warning: Boolean = false, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

// ---------------------------------------------------------------- updates

@Composable
private fun UpdateBanner(model: AppModel, state: UpdateState, inSettings: Boolean) {
    when (state) {
        is UpdateState.Available -> BannerCard {
            Text("Update available: version ${state.info.version}", style = MaterialTheme.typography.titleMedium)
            Text("It installs over this app. Your accounts stay.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.downloadUpdate(state.info) }) { Text("Update now") }
                if (!inSettings) TextButton(onClick = { model.later(state.info) }) { Text("Later") }
            }
        }
        is UpdateState.Downloading -> BannerCard {
            Text("Downloading version ${state.info.version}...", style = MaterialTheme.typography.titleMedium)
            if (state.progress >= 0f) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        is UpdateState.NeedsPermission -> BannerCard {
            Text("One-time step", style = MaterialTheme.typography.titleMedium)
            Text("Turn on \"Allow from this source\" for TwoStep, then come back here and the update installs.")
            Button(onClick = { Updater.openInstallPermission(model.getApplication<Application>()) }) { Text("Open setting") }
        }
        is UpdateState.Installing -> BannerCard {
            Text("Installing version ${state.info.version}", style = MaterialTheme.typography.titleMedium)
            Text("Tap Update when Android asks. If you closed it by mistake, try again.")
            TextButton(onClick = { model.downloadUpdate(state.info) }) { Text("Try again") }
        }
        is UpdateState.Failed -> if (state.info != null) {
            BannerCard {
                Text(state.message, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { model.downloadUpdate(state.info) }) { Text("Try again") }
            }
        } else if (inSettings) {
            Text(state.message, color = MaterialTheme.colorScheme.error)
        }
        UpdateState.Checking -> if (inSettings) Text("Checking...")
        UpdateState.UpToDate -> if (inSettings) Text("You have the newest version.")
        UpdateState.Idle -> {}
    }
}

@Composable
private fun BannerCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

// ---------------------------------------------------------------- add

@Composable
private fun AddScreen(model: AppModel, prefill: OtpParams?, snackbar: SnackbarHostState, onClose: () -> Unit) {
    var issuer by remember { mutableStateOf(prefill?.issuer ?: "") }
    var name by remember { mutableStateOf(prefill?.name ?: "") }
    var secret by remember { mutableStateOf(prefill?.secret ?: "") }
    var algorithm by remember { mutableStateOf(prefill?.algorithm ?: OtpAlgorithm.SHA1) }
    var digits by remember { mutableStateOf(prefill?.digits ?: 6) }
    var period by remember { mutableStateOf((prefill?.period ?: 30).toString()) }
    var advanced by remember {
        mutableStateOf(prefill != null && (prefill.algorithm != OtpAlgorithm.SHA1 || prefill.digits != 6 || prefill.period != 30))
    }
    var saving by remember { mutableStateOf(false) }
    val now = rememberNow()

    val keyError = remember(secret) {
        if (secret.isBlank()) null else runCatching { Base32.decode(secret) }.exceptionOrNull()?.message
    }
    val periodValue = period.toIntOrNull()
    val params = if (secret.isNotBlank() && keyError == null && periodValue != null && periodValue in 1..300) {
        OtpParams(issuer.trim(), name.trim(), Base32.normalize(secret), algorithm, digits, periodValue)
    } else null
    val duplicate = params?.let { model.findDuplicate(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (prefill != null) "Check and save" else "Enter setup key") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (prefill == null) {
                Text("On GitHub, tap \"setup key\" under the QR code, then type or paste the key here.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                issuer, { issuer = it },
                label = { Text("Site, e.g. GitHub") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                name, { name = it },
                label = { Text("Account, e.g. your username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                secret, { secret = it },
                label = { Text("Setup key") },
                isError = keyError != null,
                supportingText = { if (keyError != null) Text(keyError) },
                // Password keyboards don't learn or suggest what's typed; the key itself stays visible
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrect = false,
                    keyboardType = KeyboardType.Password,
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide advanced settings" else "Advanced settings (most sites, GitHub included, don't need these)")
            }
            if (advanced) {
                Text("Algorithm", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (alg in OtpAlgorithm.entries) {
                        FilterChip(selected = algorithm == alg, onClick = { algorithm = alg }, label = { Text(alg.name) })
                    }
                }
                Text("Digits", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in 6..8) {
                        FilterChip(selected = digits == d, onClick = { digits = d }, label = { Text("$d") })
                    }
                }
                OutlinedTextField(
                    period, { period = it.filter(Char::isDigit).take(3) },
                    label = { Text("New code every (seconds)") },
                    singleLine = true,
                    isError = periodValue == null || periodValue !in 1..300,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(220.dp),
                )
            }

            if (params != null) {
                val key = Base32.decode(params.secret)
                val step = Totp.counterAt(now / 1000, params.period)
                val preview = Totp.hotp(key, step, params.digits, params.algorithm)
                val left = ceil(Totp.secondsLeft(now, params.period)).toInt()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Preview", style = MaterialTheme.typography.labelLarge)
                        Text(Totp.grouped(preview), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
                        Text("changes in $left s. Tap Save first, then type the code into the site.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (duplicate != null) {
                Text("Already saved as ${AppModel.label(duplicate)}.", color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    val p = params ?: return@Button
                    saving = true
                    model.add(p) { ok -> saving = false; if (ok) onClose() }
                },
                enabled = params != null && duplicate == null && !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (saving) "Saving..." else "Save") }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(model: AppModel, activity: MainActivity, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lockOn by remember { mutableStateOf(model.settings.appLock) }
    var shotsOn by remember { mutableStateOf(model.settings.allowScreenshots) }
    var exportAsk by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pw = exportPassword
        exportPassword = null
        if (uri != null && pw != null) model.exportBackup(uri, pw) else pw?.fill('\u0000')
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = model.readImport(uri)) {
                is AppModel.ImportFile.NeedsPassword -> importText = r.text
                is AppModel.ImportFile.Ready -> model.importAccounts(r.accounts)
                null -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Backup")
            val last = model.settings.lastBackupAt
            Text(
                if (last == 0L) "No backup yet." else "Last backup: ${DateFormat.getDateTimeInstance().format(Date(last))}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "A backup is one file, locked with a password you choose. Keep it somewhere other than this phone " +
                    "(Google Drive, your laptop). It's how your accounts move to a new phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportAsk = true }, enabled = model.accounts.isNotEmpty()) { Text("Export backup") }
                OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Import") }
            }

            HorizontalDivider()
            Section("Security")
            SwitchRow("App lock", "Ask for your fingerprint or screen lock when TwoStep opens", lockOn) { on ->
                if (!on) {
                    model.setAppLock(false); lockOn = false
                } else if (!activity.canUseLock()) {
                    model.say("Set up a screen lock (PIN or fingerprint) in the phone's Settings first")
                } else {
                    // prove it works before relying on it
                    activity.authenticate("Turn on app lock") { model.setAppLock(true); lockOn = true }
                }
            }
            SwitchRow("Allow screenshots", "Off keeps codes out of screenshots and the recent-apps view", shotsOn) { on ->
                model.settings.allowScreenshots = on; shotsOn = on
                activity.applyScreenshotPolicy()
            }

            HorizontalDivider()
            Section("Updates")
            Text("Installed: build ${model.installedBuild}", style = MaterialTheme.typography.bodyMedium)
            UpdateBanner(model, model.update, inSettings = true)
            OutlinedButton(onClick = { model.checkForUpdates(manual = true) }) { Text("Check for updates") }

            HorizontalDivider()
            Section("Clock")
            val drift = model.clockDrift
            Text(
                when {
                    drift == null -> "Checked against GitHub's clock with each update check."
                    model.clockWarning() != null -> model.clockWarning()!!
                    else -> "The phone's clock matches GitHub's (within ${kotlin.math.abs(drift)} s). Codes will be accepted."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (model.clockWarning() != null) MaterialTheme.colorScheme.error else Color.Unspecified,
            )

            HorizontalDivider()
            Section("About")
            Text(
                "TwoStep makes standard 2FA codes (TOTP, RFC 6238), the kind GitHub, Google and Microsoft accept from " +
                    "any authenticator app. Keys are encrypted with a key that stays inside Android's Keystore; they " +
                    "only leave the phone in backups you export. Source: github.com/${Updater.REPO}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (exportAsk) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        val problem = when {
            p1.length < 8 -> "At least 8 characters"
            p1 != p2 -> "The two passwords don't match"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { exportAsk = false },
            title = { Text("Backup password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Without this password the backup can't be opened, by anyone. Write it down.")
                    PasswordField(p1, { p1 = it }, "Password")
                    PasswordField(p2, { p2 = it }, "Same password again")
                    if (p1.isNotEmpty() && problem != null) Text(problem, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(enabled = problem == null, onClick = {
                    exportAsk = false
                    exportPassword = p1.toCharArray()
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    create.launch("TwoStep-backup-$day.json")
                }) { Text("Choose where to save") }
            },
            dismissButton = { TextButton(onClick = { exportAsk = false }) { Text("Cancel") } },
        )
    }

    importText?.let { text ->
        var pw by remember { mutableStateOf("") }
        var wrong by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) importText = null },
            title = { Text("Open backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PasswordField(pw, { pw = it; wrong = false }, "Backup password")
                    if (wrong) Text("Wrong password", color = MaterialTheme.colorScheme.error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(enabled = pw.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        val r = model.decryptImport(text, pw.toCharArray())
                        busy = false
                        when {
                            r == null -> wrong = true
                            else -> { importText = null; if (r.isNotEmpty()) model.importAccounts(r) }
                        }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { importText = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
    )
}
