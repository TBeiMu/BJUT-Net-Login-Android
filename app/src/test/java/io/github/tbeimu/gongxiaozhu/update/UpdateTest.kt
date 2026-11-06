package io.github.tbeimu.gongxiaozhu.update

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private val bytes = "fictional-apk-content".toByteArray()
private val pin = "1".repeat(64)
private val manifest = UpdateManifest(1, "0.3.0", 12, "io.github.tbeimu.gongxiaozhu", 31,
    bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).hex(), "虚构更新说明")
private val identity = ApkIdentity(manifest.packageName, 12, "0.3.0", 31, setOf(pin))

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun verifier(info: ApkIdentity = identity, version: Long = 11) = ApkVerifier(version, manifest.packageName, 31, pin) { info }

    @Test fun metadataRoundTripAndUnknownFields() {
        assertEquals(manifest, parseUpdateManifest(updateJson.encodeToString(manifest).dropLast(1) + ",\"future\":true}"))
    }
    @Test fun malformedMetadataIsRejectedWithoutServerText() {
        listOf("<html>fictional password</html>", "{}", "null").forEach {
            assertFalse(assertThrows(UpdateFailure::class.java) { parseUpdateManifest(it) }.message!!.contains("password"))
        }
    }
    @Test fun invalidManifestFieldsAreRejected() {
        listOf(manifest.copy(schemaVersion = 2), manifest.copy(versionName = "../evil"), manifest.copy(versionCode = 0),
            manifest.copy(packageName = "other"), manifest.copy(minSdk = 0), manifest.copy(apkSize = Long.MAX_VALUE),
            manifest.copy(apkSha256 = "no"), manifest.copy(releaseNotes = "x".repeat(32_769))).forEach {
            assertThrows(UpdateFailure::class.java) { it.validate() }
        }
    }
    @Test fun redirectPolicyRejectsCrossSiteAndDowngrades() {
        val initial = UPDATE_URL.toHttpUrl()
        assertTrue(allowedUpdateUrl(initial, initial))
        assertTrue(allowedUpdateUrl("$RELEASES_URL/download/v0.3.0/update.json".toHttpUrl(), initial))
        assertTrue(allowedUpdateUrl("https://release-assets.githubusercontent.com/example?token=fake".toHttpUrl(), initial))
        listOf("http://github.com/TBeiMu/BJUT-Net-Login-Android/releases/latest/download/update.json",
            "https://github.com.evil.example/file", "https://github.com/other/repo/releases/download/v0.3.0/update.json",
            "https://user@release-assets.githubusercontent.com/file", "https://release-assets.githubusercontent.com:444/file",
            "https://github.com/TBeiMu/BJUT-Net-Login-Android/releases/download/v0.3.0/update.json?q=x").forEach {
            assertFalse(it, allowedUpdateUrl(it.toHttpUrl(), initial))
        }
        assertFalse(allowedUpdateUrl(manifest.copy(versionName = "0.4.0").apkUrl.toHttpUrl(), manifest.apkUrl.toHttpUrl()))
    }

    private suspend fun http(responses: List<MockResponse>, test: suspend (HttpUpdateSource, MockWebServer) -> Unit) {
        MockWebServer().use { server ->
            responses.forEach(server::enqueue)
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            // Transport substitution exists only in test sources. Production URL policy stays active.
            val client = updateClientBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)
                    .newBuilder().host("127.0.0.1").build()).build())
            }.build()
            try { test(HttpUpdateSource(client, UpdateDns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }), server) }
            finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
    @Test fun downloadsWholeFileAndSendsNoCookies() = runTest {
        val file = temp.newFile()
        http(listOf(MockResponse().setBody(String(bytes)).setHeader("ETag", "\"one\""))) { source, server ->
            var tag: String? = null
            source.download(manifest, file, null, { tag = it }, {})
            assertArrayEquals(bytes, file.readBytes()); assertEquals("\"one\"", tag)
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun checkingAndDownloadingReuseClientWithoutClosingNextExchange() = runTest {
        http(listOf(MockResponse().setBody(updateJson.encodeToString(manifest)), MockResponse().setBody(String(bytes)))) { source, server ->
            val checked = source.check()
            val file = temp.newFile()
            source.download(checked, file, null, {}, {})
            assertArrayEquals(bytes, file.readBytes()); assertEquals(2, server.requestCount)
        }
    }
    @Test fun resumesOnlyWithMatchingRange() = runTest {
        val file = temp.newFile().apply { writeBytes(bytes.take(5).toByteArray()) }
        http(listOf(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 5-${bytes.size - 1}/${bytes.size}")
            .setHeader("ETag", "\"one\"").setBody(String(bytes.drop(5).toByteArray())))) { source, server ->
            source.download(manifest, file, "\"one\"", {}, {})
            assertArrayEquals(bytes, file.readBytes())
            val request = server.takeRequest()
            assertEquals("bytes=5-", request.getHeader("Range")); assertEquals("\"one\"", request.getHeader("If-Range"))
        }
    }
    @Test fun unsupportedRangeRestartsInsteadOfAppending() = runTest {
        val file = temp.newFile().apply { writeText("old") }
        http(listOf(MockResponse().setBody(String(bytes)).setHeader("ETag", "\"two\""))) { source, _ ->
            source.download(manifest, file, "\"one\"", {}, {})
            assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun malformedRangeNeverAppends() = runTest {
        val file = temp.newFile().apply { writeText("old") }
        http(listOf(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-2/3").setBody("bad"))) { source, _ ->
            try { source.download(manifest, file, "\"one\"", {}, {}); fail() } catch (_: UpdateFailure) { }
            assertEquals("old", file.readText())
        }
    }
    @Test fun missingEtagStartsFresh() = runTest {
        val file = temp.newFile().apply { writeText("old") }
        http(listOf(MockResponse().setBody(String(bytes)))) { source, server ->
            source.download(manifest, file, null, {}, {})
            assertNull(server.takeRequest().getHeader("Range")); assertArrayEquals(bytes, file.readBytes())
        }
    }
    @Test fun truncatedAndOversizedResponsesFail() = runTest {
        for (data in listOf("short", "x".repeat(100))) {
            http(listOf(MockResponse().setBody(data))) { source, _ ->
                try { source.download(manifest, temp.newFile(), null, {}, {}); fail() } catch (_: UpdateFailure) { }
            }
        }
    }
    @Test fun metadataResponseLimitAndRedirectChecks() = runTest {
        for (response in listOf(MockResponse().setBody("x".repeat(131_073)),
            MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/private"))) {
            http(listOf(response)) { source, server ->
                try { source.check(); fail() } catch (_: UpdateFailure) { }
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun verifiesHashAndArchiveIdentity() = runTest {
        val file = temp.newFile().apply { writeBytes(bytes) }
        verifier().verify(file, manifest)
        for (bad in listOf(identity.copy(packageName = "wrong"), identity.copy(versionCode = 11),
            identity.copy(versionName = "wrong"), identity.copy(minSdk = 32), identity.copy(certificates = setOf("2".repeat(64))))) {
            try { verifier(bad).verify(file, manifest); fail() } catch (_: UpdateFailure) { }
        }
        try { verifier(version = 12).verify(file, manifest); fail() } catch (_: UpdateFailure) { }
        file.writeBytes(ByteArray(bytes.size))
        try { verifier().verify(file, manifest); fail() } catch (_: UpdateFailure) { }
    }

    private class Source : UpdateSource {
        var checks = 0
        var downloads = 0
        var block = false
        var checkBlock = false
        var result = manifest
        var lateProgress: ((Long) -> Unit)? = null
        override suspend fun check(): UpdateManifest { checks++; if (checkBlock) awaitCancellation(); return result }
        override suspend fun download(manifest: UpdateManifest, file: File, etag: String?, saveEtag: (String?) -> Unit, progress: (Long) -> Unit) {
            downloads++; lateProgress = progress; file.writeBytes(bytes.take(5).toByteArray()); saveEtag("\"test\""); progress(5)
            if (block) awaitCancellation()
            file.writeBytes(bytes); progress(bytes.size.toLong())
        }
    }
    @Test fun manualCheckAndSingleDownloadThenReverifyBeforeInstall() = kotlinx.coroutines.runBlocking {
        val store = UpdateStore(temp.newFolder())
        val repo = UpdateRepository(this, store, verifier(), 11, 31)
        val source = Source()
        repo.networkChanged(UpdateNetwork("wifi1", source)); kotlinx.coroutines.yield(); assertEquals(0, source.checks)
        repo.check(); repo.check(); kotlinx.coroutines.yield(); assertEquals(1, source.checks)
        assertTrue(repo.download(repo.requestDownload())); assertFalse(repo.download(null))
        // IO verification completes on the real IO dispatcher; join the scope's children.
        coroutineContext[ kotlinx.coroutines.Job ]!!.children.toList().forEach { it.join() }
        assertEquals(UpdatePhase.READY, repo.state.value.phase)
        assertEquals(store.verified, repo.prepareInstall())
        store.verified.writeText("corrupt")
        assertNull(repo.prepareInstall()); assertEquals(UpdatePhase.PAUSED, repo.state.value.phase)
    }
    @Test fun networkSwitchPausesAndNeverAutoResumes() = runTest {
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), 11, 31)
        val source = Source().apply { block = true }
        repo.networkChanged(UpdateNetwork("one", source)); repo.check(); runCurrent()
        repo.download(repo.requestDownload()); runCurrent(); repo.leaveForeground(); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, repo.state.value.phase)
        repo.networkChanged(UpdateNetwork("two", source)); runCurrent()
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase); assertEquals(1, source.downloads)
        repo.networkChanged(UpdateNetwork("three", source)); runCurrent(); assertEquals(1, source.downloads)
        repo.cancel(); runCurrent()
        source.lateProgress?.invoke(999)
        assertEquals(0, repo.state.value.bytes); assertEquals(UpdatePhase.IDLE, repo.state.value.phase)
        repo.check(); runCurrent(); assertEquals(2, source.checks)
    }
    @Test fun restoresPausedTaskAndCleansAfterUpgrade() = runTest {
        val store = UpdateStore(temp.newFolder())
        store.save(SavedUpdate(manifest, etag = "\"one\"")); store.partial.writeText("saved")
        val repo = UpdateRepository(this, store, verifier(), 11, 31)
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase); assertEquals(5, repo.state.value.bytes)
        val upgraded = UpdateRepository(this, store, verifier(), 12, 31)
        assertEquals(UpdatePhase.CURRENT, upgraded.state.value.phase); assertFalse(store.partial.exists())
    }
    @Test fun checkingWithoutDownloadingRestoresAvailable() = runTest {
        val store = UpdateStore(temp.newFolder())
        val source = Source()
        val repo = UpdateRepository(this, store, verifier(), 11, 31)
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.check(); runCurrent()
        val restored = UpdateRepository(this, store, verifier(), 11, 31)
        assertEquals(UpdatePhase.AVAILABLE, restored.state.value.phase)
        assertEquals(false, store.load()!!.downloadStarted)
        assertFalse(store.partial.exists()); assertEquals(0, source.downloads)
    }
    @Test fun legacyDiscoveryWithoutDownloadEvidenceRestoresAvailable() = runTest {
        val store = UpdateStore(temp.newFolder())
        store.save(SavedUpdate(manifest))
        assertFalse(File(store.directory, "state.json").readText().contains("downloadStarted"))
        assertEquals(UpdatePhase.AVAILABLE, UpdateRepository(this, store, verifier(), 11, 31).state.value.phase)
    }
    @Test fun failureBeforeFirstByteRemainsPausedAfterRestart() = runTest {
        val store = UpdateStore(temp.newFolder())
        val repo = UpdateRepository(this, store, verifier(), 11, 31)
        repo.networkChanged(UpdateNetwork("wifi", object : UpdateSource {
            override suspend fun check() = manifest
            override suspend fun download(manifest: UpdateManifest, file: File, etag: String?, saveEtag: (String?) -> Unit, progress: (Long) -> Unit) {
                throw UpdateFailure("虚构的连接失败")
            }
        }))
        repo.check(); runCurrent(); repo.download(repo.requestDownload()); runCurrent()
        assertTrue(store.load()!!.downloadStarted == true)
        assertFalse(store.partial.exists())
        assertEquals(UpdatePhase.PAUSED, UpdateRepository(this, store, verifier(), 11, 31).state.value.phase)
    }
    @Test fun failedRevalidationOfLegacyReadyApkRetainsStartedState() = kotlinx.coroutines.runBlocking {
        val store = UpdateStore(temp.newFolder())
        store.save(SavedUpdate(manifest, ready = true))
        store.verified.parentFile!!.mkdirs()
        store.verified.writeText("corrupt")
        val repo = UpdateRepository(this, store, verifier(), 11, 31)
        assertEquals(UpdatePhase.READY, repo.state.value.phase)
        assertNull(repo.prepareInstall())
        assertEquals(true, store.load()!!.downloadStarted)
        assertEquals(UpdatePhase.PAUSED, UpdateRepository(this, store, verifier(), 11, 31).state.value.phase)
    }
    @Test fun cancellingWhileWifiPauseIsPendingStillDeletesTheTask() = runTest {
        val store = UpdateStore(temp.newFolder())
        val repo = UpdateRepository(this, store, verifier(), 11, 31)
        repo.networkChanged(UpdateNetwork("wifi", Source().apply { block = true }))
        repo.check(); runCurrent(); repo.download(repo.requestDownload()); runCurrent()
        repo.networkChanged(null)
        repo.cancel(); repo.cancel(); runCurrent()
        assertEquals(UpdatePhase.IDLE, repo.state.value.phase)
        assertFalse(store.partial.exists()); assertNull(store.load())
    }
    @Test fun noWifiAndDebugModeDoNotStartRequests() = runTest {
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), 11, 31)
        repo.check(); assertEquals(UpdatePhase.ERROR, repo.state.value.phase)
        val debug = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), 11, 31, false)
        val source = Source()
        debug.networkChanged(UpdateNetwork("one", source)); debug.check(); assertFalse(debug.download(null))
        runCurrent(); assertEquals(0, source.checks)
    }
    @Test fun equalOrOlderVersionDoesNotOfferDowngrade() = runTest {
        for (version in listOf(12L, 13L)) {
            val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), version, 31)
            repo.networkChanged(UpdateNetwork("wifi", Source())); repo.check(); runCurrent()
            assertEquals(UpdatePhase.CURRENT, repo.state.value.phase); assertNull(repo.state.value.manifest)
        }
    }
    @Test fun incompatibleAndroidVersionDoesNotOfferDownload() = runTest {
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), 11, 31)
        repo.networkChanged(UpdateNetwork("wifi", Source().apply { result = manifest.copy(minSdk = 36) }))
        repo.check(); runCurrent(); assertEquals(UpdatePhase.ERROR, repo.state.value.phase); assertFalse(repo.download(null))
    }
    @Test fun leavingForegroundCancelsCheckButAllowsManualRetry() = runTest {
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), 11, 31)
        val source = Source().apply { checkBlock = true }
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.check(); runCurrent()
        repo.leaveForeground(); runCurrent(); assertEquals(UpdatePhase.ERROR, repo.state.value.phase)
        source.checkBlock = false; repo.check(); runCurrent(); assertEquals(UpdatePhase.AVAILABLE, repo.state.value.phase)
    }
    @Test fun thirtyMinuteLimitPausesWithoutRetrying() = runTest {
        val repo = UpdateRepository(this, UpdateStore(temp.newFolder()), verifier(), 11, 31)
        val source = Source().apply { block = true }
        repo.networkChanged(UpdateNetwork("wifi", source)); repo.check(); runCurrent(); repo.download(repo.requestDownload()); runCurrent()
        testScheduler.advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(UpdatePhase.PAUSED, repo.state.value.phase); assertTrue(repo.state.value.message.contains("超时"))
        assertEquals(1, source.downloads)
    }
    @Test fun redirectLoopStopsAfterBoundedRequests() = runTest {
        http(List(6) { MockResponse().setResponseCode(302).setHeader("Location", UPDATE_URL) }) { source, server ->
            try { source.check(); fail() } catch (_: UpdateFailure) { }
            assertEquals(6, server.requestCount)
        }
    }
    @Test fun corruptTaskMetadataDoesNotBreakAccountIndependentUpdates() = runTest {
        val dir = temp.newFolder()
        File(dir, "state.json").writeText("{corrupt")
        val repo = UpdateRepository(this, UpdateStore(dir), verifier(), 11, 31)
        assertEquals(UpdatePhase.ERROR, repo.state.value.phase)
        repo.networkChanged(UpdateNetwork("wifi", Source())); repo.check(); runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, repo.state.value.phase)
    }
}
