package com.iknalos.twostep.data

import android.content.Context

/**
 * Plain preferences: nothing secret lives here. Written with commit(), not apply(): a tiny
 * file, and a security switch must still be set if the app is killed a moment later.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) { prefs.edit().putBoolean("app_lock", v).commit() }

    /** Off by default: codes stay out of screenshots and the recent-apps preview. */
    var allowScreenshots: Boolean
        get() = prefs.getBoolean("allow_screenshots", false)
        set(v) { prefs.edit().putBoolean("allow_screenshots", v).commit() }

    var lastBackupAt: Long
        get() = prefs.getLong("last_backup_at", 0)
        set(v) { prefs.edit().putLong("last_backup_at", v).commit() }

    var lastChangeAt: Long
        get() = prefs.getLong("last_change_at", 0)
        set(v) { prefs.edit().putLong("last_change_at", v).commit() }

    /** "Later" on the update banner hides it until a build newer than this one is out. */
    var skipUpdateBuild: Long
        get() = prefs.getLong("skip_update_build", 0)
        set(v) { prefs.edit().putLong("skip_update_build", v).commit() }

    val needsBackup get() = lastChangeAt > lastBackupAt
}
