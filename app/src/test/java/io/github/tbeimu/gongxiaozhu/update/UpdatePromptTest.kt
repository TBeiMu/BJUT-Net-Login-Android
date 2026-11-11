package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdatePromptTest {
    @get:Rule val temp = TemporaryFolder()
    private val manifest = UpdateManifest(1, "0.5.0", 20, "io.github.tbeimu.gongxiaozhu", 31,
        4096, "0".repeat(64), "虚构更新说明")
    private class Source(var result: UpdateManifest) : UpdateSource {
        var checks = 0
        var downloads = 0
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun check(): UpdateManifest {
            checks++
            gate?.await()
            if (failure) throw UpdateFailure("虚构连接错误")
            return result
        }
        override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
            saveEtag: (String?) -> Unit, progress: (Long) -> Unit) {
            downloads++
            awaitCancellation()
        }
    }
    private fun repo(scope: CoroutineScope, store: UpdateStore = UpdateStore(temp.newFolder()), enabled: Boolean = true) =
        UpdateRepository(scope, store, ApkVerifier(19, manifest.packageName, 36, "1".repeat(64)) { null }, 19, 36, enabled)

    @Test fun automaticCheckWaitsForUsableNetworkAndRunsOncePerOpeningWithoutDownloading() = runTest {
        val source = Source(manifest); val store = UpdateStore(temp.newFolder()); val repo = repo(backgroundScope, store)
        repo.enterForeground(); runCurrent(); assertEquals(0, source.checks)
        repo.networkChanged(UpdateNetwork("wifi", source, validated = false)); runCurrent()
        assertEquals(0, source.checks)
        repo.networkChanged(UpdateNetwork("wifi", source)); runCurrent()
        assertEquals(1, source.checks); assertEquals(manifest, repo.state.value.prompt?.manifest)
        assertNull(store.load()); assertEquals(0, source.downloads)
        repo.dismissPrompt(repo.state.value.prompt!!.id)
        repo.enterForeground(); repo.networkChanged(UpdateNetwork("wifi2", source)); runCurrent()
        assertEquals(1, source.checks); assertNull(repo.state.value.prompt)
        repo.leaveForeground(); repo.enterForeground(); runCurrent()
        assertEquals(2, source.checks); assertNotNull(repo.state.value.prompt)
    }

    @Test fun ignoreIsPersistentForExactVersionManualCheckOverridesItAndNextVersionIsOffered() = runTest {
        val store = UpdateStore(temp.newFolder()); val source = Source(manifest)
        val first = repo(backgroundScope, store)
        first.networkChanged(UpdateNetwork("wifi", source)); first.enterForeground(); runCurrent()
        first.ignorePrompt(first.state.value.prompt!!.id)
        assertEquals(20L, store.ignoredVersion()); assertNull(first.state.value.prompt)
        val restored = repo(backgroundScope, store)
        restored.networkChanged(UpdateNetwork("wifi", source)); restored.enterForeground(); runCurrent()
        assertNull(restored.state.value.prompt)
        restored.checkForPrompt(); runCurrent()
        assertEquals(manifest, restored.state.value.prompt?.manifest)
        restored.dismissPrompt(restored.state.value.prompt!!.id)
        source.result = manifest.copy(versionName = "0.5.1", versionCode = 21)
        restored.leaveForeground(); restored.enterForeground(); runCurrent()
        assertEquals(21L, restored.state.value.prompt?.manifest?.versionCode)
        assertEquals(0, source.downloads)
    }

    @Test fun acceptingPromptSelectsVersionAndMobileDownloadStillRequiresSeparateConsent() = runTest {
        val store = UpdateStore(temp.newFolder()); val source = Source(manifest); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR)); repo.enterForeground(); runCurrent()
        val id = repo.state.value.prompt!!.id
        assertFalse(repo.acceptPrompt("stale")); assertTrue(repo.acceptPrompt(id)); assertFalse(repo.acceptPrompt(id))
        assertEquals(UpdatePhase.AVAILABLE, repo.state.value.phase)
        assertEquals(false, store.load()?.downloadStarted)
        assertEquals(0, source.downloads)
        assertNull(repo.requestDownload()); assertNotNull(repo.state.value.mobileConfirmation)
        assertEquals(0, source.downloads)
        repo.dismissMobileDownload(repo.state.value.mobileConfirmation!!.id)
        assertEquals(0, source.downloads)
    }

    @Test fun cancelAndIgnorePromptPreservePausedDownloadAndOnlyAcceptedNewVersionReplacesIt() = runTest {
        val store = UpdateStore(temp.newFolder())
        val old = manifest.copy(versionName = "0.4.4", versionCode = 20, apkSha256 = "1".repeat(64))
        store.save(SavedUpdate(old, downloadStarted = true, etag = "fake-etag")); store.partial.writeText("fictional-progress")
        val source = Source(manifest.copy(versionCode = 21)); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        assertEquals(0, source.checks) // Opening never disrupts an existing download.
        val progress = store.partial.readBytes()
        repo.checkForPrompt(); runCurrent(); repo.dismissPrompt(repo.state.value.prompt!!.id)
        assertArrayEquals(progress, store.partial.readBytes()); assertEquals(old, store.load()?.manifest)
        repo.checkForPrompt(); runCurrent(); repo.ignorePrompt(repo.state.value.prompt!!.id)
        assertArrayEquals(progress, store.partial.readBytes()); assertEquals(old, store.load()?.manifest)
        repo.checkForPrompt(); runCurrent(); assertTrue(repo.acceptPrompt(repo.state.value.prompt!!.id))
        assertFalse(store.partial.exists()); assertEquals(21L, store.load()?.manifest?.versionCode)
    }

    @Test fun manualCheckingAndUpdatedNotesForSameApkPreservePartialFileAndEtag() = runTest {
        val store = UpdateStore(temp.newFolder())
        store.save(SavedUpdate(manifest, downloadStarted = true, etag = "fake-etag")); store.partial.writeText("fictional-progress")
        val source = Source(manifest.copy(releaseNotes = "虚构更新说明修订")); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        repo.checkForPrompt(); runCurrent(); assertTrue(repo.acceptPrompt(repo.state.value.prompt!!.id))
        assertEquals("fictional-progress", store.partial.readText()); assertEquals("fake-etag", store.load()?.etag)
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
    }

    @Test fun discoverySavedByOlderVersionDoesNotPreventFindingANewerReleaseAndCancelKeepsIgnore() = runTest {
        val store = UpdateStore(temp.newFolder())
        store.save(SavedUpdate(manifest, downloadStarted = false)); store.ignoreVersion(manifest.versionCode)
        val source = Source(manifest.copy(versionName = "0.5.1", versionCode = 21)); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        assertEquals(21L, repo.state.value.prompt?.manifest?.versionCode)
        repo.cancel(); runCurrent()
        assertEquals(20L, store.ignoredVersion()); assertNull(store.load())
    }

    @Test fun automaticFailureIsSilentWhileManualFailureIsReportedAndDisabledDebugDoesNotCheck() = runTest {
        val source = Source(manifest).apply { failure = true }; val repo = repo(backgroundScope)
        val before = repo.state.value.message
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        assertNull(repo.state.value.prompt); assertEquals(before, repo.state.value.message)
        repo.checkForPrompt(); runCurrent()
        assertEquals("虚构连接错误", repo.state.value.message); assertNull(repo.state.value.prompt)
        assertEquals("虚构连接错误", repo.state.value.checkNotice?.message)
        val debug = repo(backgroundScope, enabled = false)
        debug.networkChanged(UpdateNetwork("wifi", source)); debug.enterForeground(); debug.checkForPrompt(); runCurrent()
        assertEquals(2, source.checks)
    }

    @Test fun currentVersionIsSilentAutomaticallyAndManualNoticeIsConsumedOnlyOncePerCheck() = runTest {
        val source = Source(manifest.copy(versionCode = 19)); val repo = repo(backgroundScope)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        assertNull(repo.state.value.prompt); assertNull(repo.state.value.checkNotice)
        repo.checkForPrompt(); runCurrent()
        val first = repo.state.value.checkNotice!!
        assertEquals("已是最新，无需更新", first.message)
        assertFalse(first.canOpenReleasePage)
        assertEquals(first, repo.consumeCheckNotice(first.id)); assertNull(repo.consumeCheckNotice(first.id))
        repo.checkForPrompt(); runCurrent()
        val next = repo.state.value.checkNotice!!
        assertNotEquals(first.id, next.id)
        assertNull(repo.consumeCheckNotice(first.id)); assertEquals(next, repo.state.value.checkNotice)
    }

    @Test fun currentVersionFeedbackPreservesExistingDownloadAndDoesNotDependOnCurrentPhase() = runTest {
        val store = UpdateStore(temp.newFolder())
        store.save(SavedUpdate(manifest, downloadStarted = true)); store.partial.writeText("fictional-progress")
        val source = Source(manifest.copy(versionCode = 19)); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        repo.checkForPrompt(); runCurrent()
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
        assertEquals("已是最新，无需更新", repo.state.value.checkNotice?.message)
        assertEquals("fictional-progress", store.partial.readText()); assertEquals(manifest, store.load()?.manifest)
        repo.networkChanged(null); repo.checkForPrompt(); runCurrent()
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
        assertTrue(repo.state.value.checkNotice!!.canOpenReleasePage)
        assertEquals("fictional-progress", store.partial.readText())
    }

    @Test fun manualNoNetworkAndTimeoutAreReportedButBackgroundCancellationLeavesNoNotice() = runTest {
        val source = Source(manifest.copy(versionCode = 19)); val repo = repo(backgroundScope)
        repo.enterForeground(); repo.checkForPrompt(); runCurrent()
        assertNotNull(repo.state.value.checkNotice)
        repo.networkChanged(UpdateNetwork("wifi", source)); runCurrent()
        assertNull(repo.state.value.checkNotice)
        source.gate = CompletableDeferred()
        repo.checkForPrompt(); runCurrent(); advanceTimeBy(60_001); runCurrent()
        assertEquals("检查更新超时，请稍后重试", repo.state.value.checkNotice?.message)
        repo.checkForPrompt(); runCurrent(); repo.leaveForeground(); runCurrent()
        source.gate!!.complete(Unit); runCurrent()
        assertNull(repo.state.value.checkNotice); assertNull(repo.state.value.prompt)
    }

    @Test fun ignoredChoiceIsNotClaimedSavedWhenWritingFails() = runTest {
        val store = UpdateStore(temp.newFolder()); val source = Source(manifest); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        File(store.directory, "ignored-version.txt").mkdir()
        repo.ignorePrompt(repo.state.value.prompt!!.id)
        assertNotNull(repo.state.value.prompt); assertEquals("未能保存忽略选择，请重试", repo.state.value.prompt?.error)
    }

    @Test fun backgroundCancellationRejectsLateManifestAndFreshReturnChecksAfterOldCleanup() = runTest {
        val cleanup = CompletableDeferred<Unit>(); var calls = 0
        val source = object : UpdateSource {
            override suspend fun check(): UpdateManifest {
                calls++
                if (calls == 1) try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
                return manifest
            }
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?,
                saveEtag: (String?) -> Unit, progress: (Long) -> Unit) = error("Must not download")
        }
        val repo = repo(backgroundScope)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
        repo.leaveForeground(); runCurrent(); repo.enterForeground(); runCurrent()
        assertNull(repo.state.value.prompt); assertEquals(1, calls)
        cleanup.complete(Unit); runCurrent()
        assertEquals(2, calls); assertEquals(manifest, repo.state.value.prompt?.manifest)
    }

    @Test fun optionalNotificationRoundTripCannotRaceAutomaticCheckOrRestoreADownloadClick() = runTest {
        val store = UpdateStore(temp.newFolder()); val source = Source(manifest); val repo = repo(backgroundScope, store)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR)); repo.enterForeground(); runCurrent()
        assertTrue(repo.acceptPrompt(repo.state.value.prompt!!.id))
        assertTrue(repo.awaitNotificationPermission()); assertFalse(repo.awaitNotificationPermission())
        repo.leaveForeground(); repo.enterForeground(); runCurrent()
        assertEquals(1, source.checks); assertNull(repo.requestDownload())
        assertTrue(repo.finishNotificationPermission()); assertFalse(repo.finishNotificationPermission())
        assertNull(repo.requestDownload()); assertNotNull(repo.state.value.mobileConfirmation)
        assertEquals(0, source.downloads)
        val restored = repo(backgroundScope, store)
        assertFalse(restored.finishNotificationPermission())
        repo.cancel(); runCurrent(); assertFalse(repo.finishNotificationPermission())
    }

    @Test fun damagedIgnorePreferenceDoesNotSuppressValidCurrentOrFutureReleases() = runTest {
        for (damaged in listOf("damaged-preference", "9".repeat(64))) {
            val store = UpdateStore(temp.newFolder())
            File(store.directory, "ignored-version.txt").writeText(damaged)
            val source = Source(manifest); val repo = repo(backgroundScope, store)
            repo.networkChanged(UpdateNetwork("wifi", source)); repo.enterForeground(); runCurrent()
            assertEquals(manifest, repo.state.value.prompt?.manifest)
            assertEquals(0, source.downloads); assertNull(store.load())
            repo.dismissPrompt(repo.state.value.prompt!!.id)
            source.result = manifest.copy(versionName = "0.5.1", versionCode = 21)
            val restarted = repo(backgroundScope, store)
            restarted.networkChanged(UpdateNetwork("wifi", source)); restarted.enterForeground(); runCurrent()
            assertEquals(21L, restarted.state.value.prompt?.manifest?.versionCode)
            restarted.ignorePrompt(restarted.state.value.prompt!!.id)
            assertEquals(21L, store.ignoredVersion()); assertNull(restarted.state.value.prompt)
        }
    }
}
