package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateConsentTest {
    @get:Rule val temp = TemporaryFolder()
    private val manifest = UpdateManifest(1, "0.3.2", 14, "io.github.tbeimu.gongxiaozhu", 31,
        4_000_000, "1".repeat(64), "虚构更新")
    private val verifier = ApkVerifier(13, manifest.packageName, 31, "2".repeat(64)) { null }
    private fun repository(scope: CoroutineScope, store: UpdateStore = UpdateStore(temp.newFolder())) =
        UpdateRepository(scope, store, verifier, 13, 31)
    private inner class Source : UpdateSource {
        var checks = 0
        var downloads = 0
        override suspend fun check(): UpdateManifest { checks++; return manifest }
        override suspend fun download(manifest: UpdateManifest, file: File, etag: String?, saveEtag: (String?) -> Unit, progress: (Long) -> Unit) {
            downloads++; awaitCancellation()
        }
    }

    @Test fun mobileCheckIsAllowedButDownloadNeedsExplicitConsent() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent()
        assertEquals(1, source.checks); assertEquals(0, source.downloads)
        assertNull(repo.state.value.mobileConfirmation)
        assertNull(repo.requestDownload())
        val prompt = repo.state.value.mobileConfirmation!!
        assertEquals(manifest.apkSize, prompt.apkBytes); assertEquals(manifest.versionName, prompt.versionName)
        assertFalse(repo.download(null)); assertFalse(repo.download(prompt.id))
        runCurrent(); assertEquals(0, source.downloads)
    }

    @Test fun rejectingMobileConsentRetainsVersionAndDownloadsNothing() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent(); repo.requestDownload()
        val first = repo.state.value.mobileConfirmation!!.id
        repo.dismissMobileDownload(first)
        assertNull(repo.state.value.mobileConfirmation); assertEquals(manifest, repo.state.value.manifest)
        assertNull(repo.confirmMobileDownload(first)); assertEquals(0, source.downloads)
        repo.requestDownload()
        assertNotEquals(first, repo.state.value.mobileConfirmation!!.id)
    }

    @Test fun mobilePermitIsSingleUseAndRetryAsksAgain() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent(); repo.requestDownload()
        val permit = repo.confirmMobileDownload(repo.state.value.mobileConfirmation!!.id)!!
        assertTrue(repo.state.value.downloadStarting)
        assertTrue(repo.download(permit)); runCurrent()
        assertEquals(1, source.downloads); assertNull(repo.requestDownload())
        repo.pause(); runCurrent()
        assertFalse(repo.download(permit))
        assertNull(repo.requestDownload()); assertNotNull(repo.state.value.mobileConfirmation)
        assertEquals(1, source.downloads)
        repo.cancel(); runCurrent()
    }

    @Test fun routeChangeRevokesBothPendingConsentAndUnusedWifiPermit() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("same-key", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent(); repo.requestDownload()
        val prompt = repo.state.value.mobileConfirmation!!.id
        repo.networkChanged(UpdateNetwork("same-key", source, UpdateNetworkKind.WIFI))
        assertNull(repo.state.value.mobileConfirmation); assertNull(repo.confirmMobileDownload(prompt))
        val wifiPermit = repo.requestDownload()!!
        repo.networkChanged(UpdateNetwork("same-key", source, UpdateNetworkKind.CELLULAR))
        assertFalse(repo.download(wifiPermit)); assertFalse(repo.state.value.downloadStarting)
        assertNull(repo.requestDownload()); assertNotNull(repo.state.value.mobileConfirmation)
        assertEquals(0, source.downloads)
    }

    @Test fun wifiLossNeverAutomaticallyStartsOrOffersMobileDownload() = runTest {
        val wifi = Source(); val mobile = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("wifi", wifi)); repo.check(); runCurrent()
        assertTrue(repo.download(repo.requestDownload())); runCurrent()
        repo.networkChanged(UpdateNetwork("cell", mobile, UpdateNetworkKind.CELLULAR)); runCurrent()
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
        assertEquals(1, wifi.downloads); assertEquals(0, mobile.downloads)
        assertNull(repo.state.value.mobileConfirmation)
        repo.requestDownload(); assertNotNull(repo.state.value.mobileConfirmation)
    }

    @Test fun leavingAppCancelsUnansweredPromptButKeepsApprovedDownloadRunning() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent(); repo.requestDownload()
        val prompt = repo.state.value.mobileConfirmation!!.id
        repo.leaveForeground()
        assertNull(repo.confirmMobileDownload(prompt)); assertNull(repo.state.value.mobileConfirmation)
        repo.requestDownload()
        val permit = repo.confirmMobileDownload(repo.state.value.mobileConfirmation!!.id)!!
        repo.leaveForeground() // The user has already approved this queued foreground-service start.
        assertTrue(repo.download(permit)); runCurrent(); repo.leaveForeground()
        assertEquals(UpdatePhase.DOWNLOADING, repo.state.value.phase)
        assertEquals(1, source.downloads)
        repo.cancel(); runCurrent()
    }

    @Test fun processRestoreCannotReuseMobileConsent() = runTest {
        val store = UpdateStore(temp.newFolder()); val source = Source(); val repo = repository(this, store)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent(); repo.requestDownload()
        val permit = repo.confirmMobileDownload(repo.state.value.mobileConfirmation!!.id)!!
        val restored = repository(this, store)
        restored.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        assertFalse(restored.download(permit)); assertNull(restored.requestDownload())
        assertNotNull(restored.state.value.mobileConfirmation); assertEquals(0, source.downloads)
    }

    @Test fun failedServiceStartOrCancelRevokesPermit() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("cell", source, UpdateNetworkKind.CELLULAR))
        repo.check(); runCurrent(); repo.requestDownload()
        val first = repo.confirmMobileDownload(repo.state.value.mobileConfirmation!!.id)!!
        repo.downloadStartFailed(first)
        assertFalse(repo.download(first)); assertFalse(repo.state.value.busy)
        repo.requestDownload()
        val second = repo.confirmMobileDownload(repo.state.value.mobileConfirmation!!.id)!!
        repo.cancel(); runCurrent()
        assertFalse(repo.download(second)); assertEquals(0, source.downloads)
    }

    @Test fun staleServiceCannotCancelAnotherApprovedAttempt() = runTest {
        val source = Source(); val repo = repository(this)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.check(); runCurrent()
        val permit = repo.requestDownload()!!
        assertTrue(repo.download(permit)); runCurrent()
        repo.serviceStopped("expired-permit"); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repo.state.value.phase)
        repo.serviceStopped(permit); runCurrent()
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
    }

    @Test fun workingWifiHasPriorityEvenWhenMobileIsDefault() {
        val routes = listOf(UpdateRoute("cell", UpdateNetworkKind.CELLULAR, true), UpdateRoute("wifi", UpdateNetworkKind.WIFI, true))
        assertEquals("wifi", selectUpdateRoute(routes, "cell"))
    }
    @Test fun workingMobileCanCheckWhileCampusWifiIsUnvalidated() {
        val routes = listOf(UpdateRoute("wifi", UpdateNetworkKind.WIFI, false), UpdateRoute("cell", UpdateNetworkKind.CELLULAR, true))
        assertEquals("cell", selectUpdateRoute(routes, "wifi"))
        assertEquals("wifi", selectUpdateRoute(routes.take(1), null))
    }
    @Test fun currentSimWinsAmongEquivalentMobileNetworks() {
        val routes = listOf(UpdateRoute("sim1", UpdateNetworkKind.CELLULAR, true), UpdateRoute("sim2", UpdateNetworkKind.CELLULAR, true))
        assertEquals("sim2", selectUpdateRoute(routes, "sim2"))
        assertNull(selectUpdateRoute(emptyList<UpdateRoute<String>>(), null))
    }
}
