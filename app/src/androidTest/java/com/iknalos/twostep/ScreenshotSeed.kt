package com.iknalos.twostep

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.iknalos.twostep.data.Account
import com.iknalos.twostep.data.AppSettings
import com.iknalos.twostep.data.Vault
import androidx.core.content.pm.PackageInfoCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Used by ci/device-test.sh, skipped in the normal test run:
 *  - seed (argument seed=1) fills the emulator's app with demo accounts for screenshots;
 *  - verify (argument verify=1) runs after a newer build was installed over the app, and checks
 *    the accounts are all still there and readable: what the in-app updater promises.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotSeed {
    @Test fun seed() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("seed") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Vault(context).save(
            listOf(
                // demo keys only (RFC 6238 test seed and the usual example key), never real accounts
                Account("d1", "GitHub", "iknalos", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", addedAt = 1),
                Account("d2", "Google", "someone@gmail.com", "JBSWY3DPEHPK3PXP", addedAt = 2),
                Account("d3", "Microsoft", "someone@outlook.com", "HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", addedAt = 3),
            )
        )
        AppSettings(context).apply {
            allowScreenshots = true            // FLAG_SECURE would make the screenshot black
            lastChangeAt = 2
            lastBackupAt = 1                   // shows the "not backed up yet" card
        }
    }

    @Test fun verify() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("verify") == "1")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val build = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        assertEquals("the newer build is installed", args.getString("build")!!.toLong(), build)
        val loaded = Vault(context).load()
        assertTrue("vault after update: $loaded", loaded is Vault.Loaded.Ok)
        assertEquals(listOf("d1", "d2", "d3"), (loaded as Vault.Loaded.Ok).accounts.map { it.id })
    }
}
