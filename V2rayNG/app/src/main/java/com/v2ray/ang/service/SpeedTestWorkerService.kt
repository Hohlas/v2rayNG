package com.v2ray.ang.service

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonArray
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libv2ray.CoreCallbackHandler
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class SpeedTestWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onDelayResult: (guid: String, delayMillis: Long) -> Unit = { _, _ -> },
    private val onSpeedResult: (guid: String, speedMbps: Double) -> Unit = { _, _ -> },
    private val onProgress: (text: String) -> Unit = {},
    private val onFinish: (status: String) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("SpeedTestBatchWorker"))

    private val runningDelayCount = AtomicInteger(0)
    private val totalDelayCount = AtomicInteger(0)

    fun start() {
        val speedGuids = Collections.synchronizedList(mutableListOf<String>())
        val delayJobs = guids.map { guid ->
            totalDelayCount.incrementAndGet()
            scope.launch {
                runningDelayCount.incrementAndGet()
                try {
                    val delay = startRealPing(guid)
                    onDelayResult(guid, delay)
                    if (delay > 0L) {
                        speedGuids.add(guid)
                    }
                } catch (_: Throwable) {
                    onDelayResult(guid, -1L)
                } finally {
                    val count = totalDelayCount.decrementAndGet()
                    val left = runningDelayCount.decrementAndGet()
                    onProgress("$left / $count")
                }
            }
        }

        scope.launch {
            try {
                joinAll(*delayJobs.toTypedArray())
                runSpeedTests(speedGuids.toList())
                onFinish("0")
            } catch (_: CancellationException) {
                onFinish("-1")
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private suspend fun runSpeedTests(speedGuids: List<String>) {
        val doneCount = AtomicInteger(0)
        val semaphore = Semaphore(SettingsManager.getSpeedTestConcurrency())
        val jobs = speedGuids.map { guid ->
            scope.launch {
                semaphore.withPermit {
                    if (!currentCoroutineContext().isActive) {
                        return@withPermit
                    }

                    val speed = startDownloadSpeedTest(guid)
                    onSpeedResult(guid, speed)
                    val done = doneCount.incrementAndGet()
                    onProgress("$done / ${speedGuids.size}")
                }
            }
        }
        joinAll(*jobs.toTypedArray())
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return -1L
        }
        return CoreNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }

    private suspend fun startDownloadSpeedTest(guid: String): Double {
        return measureServerSpeed(context, guid)
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 50L * 1024L * 1024L
        private const val MIN_DOWNLOAD_BYTES = 256L * 1024L
        private val HTTP_SUCCESS_CODES = HttpURLConnection.HTTP_OK..299

        /**
         * Measures the download speed for a single server.
         * Starts a lightweight core instance with an HTTP inbound and downloads
         * through the local proxy to compute the throughput in Mbps.
         *
         * @return Download speed in Mbps, or 0.0 if the test failed.
         */
        suspend fun measureServerSpeed(context: Context, guid: String): Double {
            val configResult = CoreConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!configResult.status) {
                return 0.0
            }

            val port = findFreePort()
            val config = addHttpInbound(configResult.content, port) ?: return 0.0
            val controller = CoreNativeManager.newCoreController(SpeedTestCoreCallback())
            return try {
                controller.startLoop(config, 0)
                var started = 0
                while (!controller.isRunning && started < 20) {
                    delay(100)
                    started++
                }
                if (!controller.isRunning) {
                    LogUtil.w(AppConfig.TAG, "Speed test core did not start for $guid")
                    return 0.0
                }
                // Retry the download phase once so a transient network hiccup
                // does not report a working server as having no speed.
                repeat(2) {
                    for (url in SettingsManager.getSpeedTestUrls()) {
                        val speed = downloadViaProxy(port, url)
                        if (speed > 0.0) {
                            return speed
                        }
                    }
                }
                0.0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Speed test failed for $guid", e)
                0.0
            } finally {
                try {
                    controller.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to stop speed test core", e)
                }
            }
        }

        private suspend fun downloadViaProxy(port: Int, url: String): Double {
            val timeout = SettingsManager.getSpeedTestTimeoutMillis()
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(AppConfig.LOOPBACK, port))
            val conn = (URL(url).openConnection(proxy) as? HttpURLConnection) ?: return 0.0
            var totalBytes = 0L
            val started = SystemClock.elapsedRealtime()

            return try {
                conn.connectTimeout = timeout
                conn.readTimeout = timeout
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Connection", "close")
                conn.connect()
                val responseCode = conn.responseCode
                if (responseCode !in HTTP_SUCCESS_CODES) {
                    LogUtil.w(AppConfig.TAG, "Speed test URL returned HTTP $responseCode: $url")
                    return 0.0
                }
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                conn.inputStream.use { input ->
                    while (currentCoroutineContext().isActive && input.read(buffer).also { if (it > 0) totalBytes += it } != -1) {
                        val elapsed = SystemClock.elapsedRealtime() - started
                        if (elapsed >= timeout || totalBytes >= MAX_DOWNLOAD_BYTES) {
                            break
                        }
                    }
                }
                if (totalBytes < MIN_DOWNLOAD_BYTES) {
                    LogUtil.w(AppConfig.TAG, "Speed test URL returned only $totalBytes bytes: $url")
                    return 0.0
                }
                val elapsedSeconds = ((SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)) / 1000.0
                (totalBytes * 8.0) / elapsedSeconds / 1_000_000.0
            } catch (e: IOException) {
                LogUtil.e(AppConfig.TAG, "Speed test download failed for $url", e)
                0.0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Speed test download failed for $url", e)
                0.0
            } finally {
                conn.disconnect()
            }
        }

        private fun addHttpInbound(config: String, port: Int): String? {
            val json = JsonUtil.parseString(config) ?: return null
            val inbounds = JsonArray()
            val inbound = JsonUtil.parseString(
                """
                {
                  "tag": "speedtest-http",
                  "listen": "${AppConfig.LOOPBACK}",
                  "port": $port,
                  "protocol": "http",
                  "settings": {
                    "timeout": 0,
                    "userLevel": 8
                  }
                }
                """.trimIndent()
            ) ?: return null
            inbounds.add(inbound)
            json.add("inbounds", inbounds)
            return JsonUtil.toJsonPretty(json)
        }

        private fun findFreePort(): Int {
            ServerSocket(0).use { socket ->
                socket.reuseAddress = true
                return socket.localPort
            }
        }

        private class SpeedTestCoreCallback : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(l: Long, s: String?): Long = 0
        }
    }
}
