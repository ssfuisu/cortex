package org.cortex.terminal.update

import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class UpdateManagerTest {

    @Test
    fun testIsVersionUpgradeRejectsDowngradeAndSameVersion() {
        assertTrue(UpdateManager.isVersionUpgrade(12525L, 12526L))
        assertFalse(UpdateManager.isVersionUpgrade(12526L, 12526L))
        assertFalse(UpdateManager.isVersionUpgrade(12526L, 12525L))
    }

    @Test
    fun testIsAuthorizedSignerSingleSignerAndKeyRotation() {
        // Identical active signer
        assertTrue(
            UpdateManager.isAuthorizedSigner(
                currentActiveSigners = setOf("CERT_V1"),
                archiveActiveSigners = setOf("CERT_V1"),
                archiveLineageSigners = listOf("CERT_V1"),
                hasMultipleSigners = false
            )
        )

        // Forward key rotation: installed has CERT_V1, archive rotated to CERT_V2 with lineage [CERT_V1, CERT_V2]
        assertTrue(
            UpdateManager.isAuthorizedSigner(
                currentActiveSigners = setOf("CERT_V1"),
                archiveActiveSigners = setOf("CERT_V2"),
                archiveLineageSigners = listOf("CERT_V1", "CERT_V2"),
                hasMultipleSigners = false
            )
        )

        // Rollback attack: installed has CERT_V2, archive is signed with older rotated-out CERT_V1
        assertFalse(
            UpdateManager.isAuthorizedSigner(
                currentActiveSigners = setOf("CERT_V2"),
                archiveActiveSigners = setOf("CERT_V1"),
                archiveLineageSigners = listOf("CERT_V1", "CERT_V2"),
                hasMultipleSigners = false
            )
        )

        // Unrelated signer
        assertFalse(
            UpdateManager.isAuthorizedSigner(
                currentActiveSigners = setOf("CERT_V1"),
                archiveActiveSigners = setOf("CERT_ATTACKER"),
                archiveLineageSigners = listOf("CERT_ATTACKER"),
                hasMultipleSigners = false
            )
        )
    }

    @Test
    fun testIsAuthorizedSignerMultipleSignersRequiresExactMatch() {
        assertTrue(
            UpdateManager.isAuthorizedSigner(
                currentActiveSigners = setOf("CERT_A", "CERT_B"),
                archiveActiveSigners = setOf("CERT_A", "CERT_B"),
                archiveLineageSigners = emptyList(),
                hasMultipleSigners = true
            )
        )

        // Subset / partial intersection must be rejected for multi-signer APKs
        assertFalse(
            UpdateManager.isAuthorizedSigner(
                currentActiveSigners = setOf("CERT_A", "CERT_B"),
                archiveActiveSigners = setOf("CERT_A"),
                archiveLineageSigners = emptyList(),
                hasMultipleSigners = true
            )
        )
    }

    @Test
    fun testCleanUpdatesSkipsDeletionWhenDownloadInProgress() {
        val tempCache = File(System.getProperty("java.io.tmpdir"), "cortex_test_cache_${System.nanoTime()}")
        val updatesDir = File(tempCache, "updates")
        updatesDir.mkdirs()
        val stagedApk = File(updatesDir, "cortex_update_123.apk")
        stagedApk.writeText("dummy-apk")

        val testContext = object : ContextWrapper(null) {
            override fun getCacheDir(): File = tempCache
        }

        val field = UpdateManager::class.java.getDeclaredField("isDownloading")
        field.isAccessible = true
        val isDownloading = field.get(UpdateManager) as AtomicBoolean

        try {
            isDownloading.set(true)
            UpdateManager.cleanUpdates(testContext)
            assertTrue("Staged APK must not be deleted while download is active", stagedApk.exists())

            isDownloading.set(false)
            UpdateManager.cleanUpdates(testContext)
            assertFalse("Updates directory should be deleted when no download is active", updatesDir.exists())
        } finally {
            isDownloading.set(false)
            tempCache.deleteRecursively()
        }
    }

    @Test
    fun testFinalizeDownloadAttemptHoldsLockUntilCleanupCompletes() {
        val tempCache = File(System.getProperty("java.io.tmpdir"), "cortex_test_finalize_${System.nanoTime()}")
        val updatesDir = File(tempCache, "updates")
        updatesDir.mkdirs()
        val stagedApk = File(updatesDir, "cortex_update_failed.apk")
        stagedApk.writeText("partial-apk")

        val testContext = object : ContextWrapper(null) {
            override fun getCacheDir(): File = tempCache
        }

        val field = UpdateManager::class.java.getDeclaredField("isDownloading")
        field.isAccessible = true
        val isDownloading = field.get(UpdateManager) as AtomicBoolean

        try {
            isDownloading.set(true)
            var lockHeldAfterCleanup = false
            var dirDeletedBeforeUnlock = false
            UpdateManager.finalizeDownloadAttempt(
                context = testContext,
                targetApk = stagedApk,
                downloadSucceeded = false,
                cancelled = false
            ) {
                lockHeldAfterCleanup = isDownloading.get()
                dirDeletedBeforeUnlock = !updatesDir.exists() && !stagedApk.exists()
            }
            assertTrue("isDownloading must remain true until cleanup finishes", lockHeldAfterCleanup)
            assertTrue("targetApk and updates directory must be deleted before isDownloading is released", dirDeletedBeforeUnlock)
            assertFalse("isDownloading must be false after finalizeDownloadAttempt returns", isDownloading.get())
        } finally {
            isDownloading.set(false)
            tempCache.deleteRecursively()
        }
    }
}
