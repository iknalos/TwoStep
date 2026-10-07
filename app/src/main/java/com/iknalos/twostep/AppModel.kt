package com.iknalos.twostep

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.iknalos.twostep.data.Account
import com.iknalos.twostep.data.AccountCodec
import com.iknalos.twostep.data.AppSettings
import com.iknalos.twostep.data.BackupCrypto
import com.iknalos.twostep.data.Vault
import com.iknalos.twostep.otp.OtpAuthUri
import com.iknalos.twostep.otp.OtpParams
import com.iknalos.twostep.update.UpdateInfo
import com.iknalos.twostep.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

sealed interface VaultState {
    object Loading : VaultState
    object Ready : VaultState
    data class Broken(val reason: String) : VaultState
}

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class NeedsPermission(val info: UpdateInfo, val apk: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateState
}

sealed interface Screen {
    object Home : Screen
    /** [prefill] comes from a scanned QR code; null = typing a setup key in. */
    data class Add(val prefill: OtpParams?) : Screen
    object Settings : Screen
}

/** A snackbar line; [id] lets the same text show twice in a row. */
data class Note(val text: String, val id: Long = System.nanoTime())

class AppModel(app: Application) : AndroidViewModel(app) {

    private val vault = Vault(app)
    val settings = AppSettings(app)
    val installedBuild = Updater.installedBuild(app)
    private val saveLock = Mutex()

    var vaultState by mutableStateOf<VaultState>(VaultState.Loading); private set
    var accounts by mutableStateOf<List<Account>>(emptyList()); private set
    var update by mutableStateOf<UpdateState>(UpdateState.Idle); private set
    /** Phone clock minus GitHub's clock, in seconds; null until the first update check. */
    var clockDrift by mutableStateOf<Long?>(null); private set
    var note by mutableStateOf<Note?>(null); private set
    /** Lives here, not in the UI, so turning the phone doesn't lose the screen you're on. */
    var screen by mutableStateOf<Screen>(Screen.Home)
    var needsBackup by mutableStateOf(settings.needsBackup); private set

    // app lock: locked at process start, and again after 30 s in the background
    var locked by mutableStateOf(settings.appLock); private set
    private var backgroundedAt = 0L

    init {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.IO) { vault.load() }) {
                is Vault.Loaded.Ok -> { accounts = r.accounts; vaultState = VaultState.Ready }
                Vault.Loaded.Empty -> vaultState = VaultState.Ready
                is Vault.Loaded.Broken -> vaultState = VaultState.Broken(r.reason)
            }
        }
        checkForUpdates(manual = false)
    }

    fun say(text: String) { note = Note(text) }

    // ---------- accounts ----------

    fun findDuplicate(p: OtpParams): Account? {
        val probe = Account.from(p)
        return accounts.firstOrNull { it.sameKeyAs(probe) }
    }

    /** [onResult] gets true once the account is on disk and read back. */
    fun add(p: OtpParams, onResult: (Boolean) -> Unit) {
        val account = Account.from(p)
        mutate("Saved ${label(account)}. Now type its code into the site to finish turning on 2FA.") { it + account }
            .invokeOnCompletion { onResult(accounts.any { a -> a.id == account.id }) }
    }

    fun rename(id: String, issuer: String, name: String) =
        mutate("Renamed") { list -> list.map { if (it.id == id) it.copy(issuer = issuer.trim(), name = name.trim()) else it } }

    fun delete(id: String) {
        val gone = accounts.firstOrNull { it.id == id } ?: return
        mutate("Deleted ${label(gone)}") { list -> list.filterNot { it.id == id } }
    }

    fun move(id: String, delta: Int) = mutate(null) { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Every change goes through here: saved and verified on disk first, shown second. */
    private fun mutate(done: String?, change: (List<Account>) -> List<Account>) = viewModelScope.launch {
        saveLock.withLock {
            if (vaultState != VaultState.Ready) { say("Accounts can't be changed until the vault is readable"); return@withLock }
            val next = change(accounts)
            if (next == accounts) return@withLock
            try {
                withContext(Dispatchers.IO) { vault.save(next) }
                accounts = next
                settings.lastChangeAt = System.currentTimeMillis()
                needsBackup = settings.needsBackup
                if (done != null) say(done)
            } catch (e: Exception) {
                say("Not saved: ${e.message}. Nothing changed.")
            }
        }
    }

    // ---------- backups ----------

    fun exportBackup(uri: Uri, password: CharArray) = viewModelScope.launch {
        val snapshot = accounts
        try {
            withContext(Dispatchers.Default) {
                val plain = AccountCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
                val text = BackupCrypto.encrypt(plain, password)
                val cr = getApplication<Application>().contentResolver
                (cr.openOutputStream(uri, "wt") ?: error("Couldn't open the file")).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                // read it back from wherever it went (Drive, Downloads, USB) and decrypt it again
                val back = (cr.openInputStream(uri) ?: error("Couldn't reopen the file")).use { it.readBytes() }
                check(BackupCrypto.decrypt(String(back, Charsets.UTF_8), password).contentEquals(plain)) { "the saved copy doesn't match" }
            }
            settings.lastBackupAt = System.currentTimeMillis()
            needsBackup = settings.needsBackup
            say("Backup saved and checked: ${snapshot.size} account${if (snapshot.size == 1) "" else "s"}")
        } catch (e: Exception) {
            say("Backup failed: ${e.message}")
        } finally {
            password.fill('\u0000')
        }
    }

    sealed interface ImportFile {
        data class NeedsPassword(val text: String) : ImportFile
        data class Ready(val accounts: List<Account>) : ImportFile
    }

    /** Reads a picked file: a TwoStep backup (asks for its password) or plain otpauth:// lines. */
    suspend fun readImport(uri: Uri): ImportFile? = withContext(Dispatchers.IO) {
        try {
            val cr = getApplication<Application>().contentResolver
            val text = (cr.openInputStream(uri) ?: error("Couldn't open the file")).use { it.readBytes() }
                .also { require(it.size < 5_000_000) { "That file is too big to be a backup" } }
                .toString(Charsets.UTF_8)
            when {
                BackupCrypto.isBackup(text) -> ImportFile.NeedsPassword(text)
                text.contains("otpauth://") -> {
                    val found = text.lineSequence().map { it.trim() }.filter { it.startsWith("otpauth://") }
                        .map { Account.from(OtpAuthUri.parse(it)) }.toList()
                    ImportFile.Ready(found)
                }
                else -> ImportFile.Ready(AccountCodec.decode(text))
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { say("Can't import: ${e.message}") }
            null
        }
    }

    /** null = wrong password (the dialog stays open); empty = unusable file, already reported. */
    suspend fun decryptImport(text: String, password: CharArray): List<Account>? = withContext(Dispatchers.Default) {
        try {
            AccountCodec.decode(String(BackupCrypto.decrypt(text, password), Charsets.UTF_8))
        } catch (e: BackupCrypto.WrongPassword) {
            null
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { say("Can't import: ${e.message}") }
            emptyList()
        } finally {
            password.fill('\u0000')
        }
    }

    /** Adds the ones not already here. A broken vault is set aside (kept) and replaced. */
    fun importAccounts(incoming: List<Account>) = viewModelScope.launch {
        if (incoming.isEmpty()) { say("No accounts found in that file"); return@launch }
        saveLock.withLock {
            val wasBroken = vaultState is VaultState.Broken
            val base = if (wasBroken) emptyList() else accounts
            val fresh = ArrayList<Account>()
            for (a in incoming) if ((base + fresh).none { it.sameKeyAs(a) }) fresh += a
            val skipped = incoming.size - fresh.size
            if (fresh.isEmpty()) { say("All ${incoming.size} accounts are already here"); return@withLock }
            try {
                withContext(Dispatchers.IO) {
                    if (wasBroken) vault.quarantine()
                    vault.save(base + fresh)
                }
                accounts = base + fresh
                vaultState = VaultState.Ready
                settings.lastChangeAt = System.currentTimeMillis()
                needsBackup = settings.needsBackup
                say("Imported ${fresh.size}" + if (skipped > 0) ", skipped $skipped already here" else "")
            } catch (e: Exception) {
                say("Import not saved: ${e.message}")
            }
        }
    }

    // ---------- updates ----------

    fun checkForUpdates(manual: Boolean) {
        if (update is UpdateState.Checking || update is UpdateState.Downloading) return
        update = UpdateState.Checking
        viewModelScope.launch {
            update = try {
                val c = withContext(Dispatchers.IO) { Updater.check() }
                if (c.serverTimeMs > 0) clockDrift = (c.localTimeMs - c.serverTimeMs) / 1000
                val info = c.info
                when {
                    info == null || info.build <= installedBuild -> UpdateState.UpToDate
                    !manual && info.build <= settings.skipUpdateBuild -> UpdateState.UpToDate
                    else -> UpdateState.Available(info)
                }
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(null, "Couldn't check for updates: ${e.message}") else UpdateState.Idle
            }
        }
    }

    fun later(info: UpdateInfo) {
        settings.skipUpdateBuild = info.build
        update = UpdateState.UpToDate
    }

    fun downloadUpdate(info: UpdateInfo) {
        if (update is UpdateState.Downloading) return
        update = UpdateState.Downloading(info, 0f)
        viewModelScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(getApplication(), info) { p ->
                        viewModelScope.launch(Dispatchers.Main) {
                            if (update is UpdateState.Downloading) update = UpdateState.Downloading(info, p)
                        }
                    }
                }
                install(info, apk)
            } catch (e: Exception) {
                update = UpdateState.Failed(info, e.message ?: "Download failed")
            }
        }
    }

    private fun install(info: UpdateInfo, apk: File) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            update = UpdateState.NeedsPermission(info, apk)
            Updater.openInstallPermission(ctx)
            return
        }
        update = UpdateState.Installing(info)
        Updater.install(ctx, apk)
    }

    // ---------- lifecycle, from MainActivity ----------

    fun onStart() {
        if (settings.appLock && backgroundedAt > 0 && System.currentTimeMillis() - backgroundedAt > 30_000) locked = true
        backgroundedAt = 0
    }

    fun onStop() { backgroundedAt = System.currentTimeMillis() }

    fun onResume() {
        // back from "allow installs from this app": carry on installing
        val u = update
        if (u is UpdateState.NeedsPermission && Updater.canInstall(getApplication())) install(u.info, u.apk)
    }

    fun unlocked() { locked = false }

    fun setAppLock(on: Boolean) { settings.appLock = on; if (!on) locked = false }

    fun clockWarning(): String? {
        val d = clockDrift ?: return null
        if (abs(d) < 15) return null
        return "This phone's clock is ${abs(d)} s ${if (d > 0) "ahead" else "behind"}, so sites may reject its codes. " +
            "Turn on automatic date and time in the phone's Settings (Samsung: General management > Date and time)."
    }

    companion object {
        fun label(a: Account) = when {
            a.issuer.isNotEmpty() && a.name.isNotEmpty() -> "${a.issuer} (${a.name})"
            a.issuer.isNotEmpty() -> a.issuer
            a.name.isNotEmpty() -> a.name
            else -> "account"
        }
    }
}
