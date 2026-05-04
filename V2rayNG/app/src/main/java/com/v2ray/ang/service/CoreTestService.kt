package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.NotificationHelper
import java.util.Collections

class CoreTestService : Service() {

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())
    private val activeSpeedWorkers = Collections.synchronizedList(mutableListOf<SpeedTestWorkerService>())

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService is being destroyed, cancelling ${activeWorkers.size} active workers")
        // cancel any active workers
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        val speedSnapshot = ArrayList(activeSpeedWorkers)
        speedSnapshot.forEach { it.cancel() }
        activeSpeedWorkers.clear()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(message, startId)
            AppConfig.MSG_MEASURE_CONFIG_SPEED -> handleSpeedTestStart(message, startId)
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel()
            else -> {
                NotificationHelper.stopForeground(this); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage, startId: Int) {
        LogUtil.i(AppConfig.TAG, "CoreTestService starting worker   subscription ${message.subscriptionId}")

        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(R.string.title_real_ping_all_server)
        )

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }

        if (guidsList.isNotEmpty()) {
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                guids = guidsList,
                onEvent = { event -> handleWorkerEvent(event) { activeWorkers.remove(worker) } }
            )
            activeWorkers.add(worker)
            worker.start()
        } else {
            NotificationHelper.stopForeground(this)
            stopSelf(startId)
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                NotificationHelper.updateNotification(
                    channelType = NotificationChannelType.CORE_TEST,
                    context = this,
                    content = getString(R.string.connection_runing_task_left, event.text)
                )
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, event.text)
            }

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, event.guid)
            }

            is RealPingEvent.Finish -> {
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, event.status)
                onWorkerDone()
                if (activeWorkers.isEmpty() && activeSpeedWorkers.isEmpty()) {
                    NotificationHelper.stopForeground(this)
                    stopSelf()
                }
            }
        }
    }

    private fun handleSpeedTestStart(message: TestServiceMessage, startId: Int) {
        LogUtil.i(AppConfig.TAG, "CoreTestService starting speed worker subscription ${message.subscriptionId}")

        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            getString(R.string.title_speed_test_all_server)
        )

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }

        if (guidsList.isNotEmpty()) {
            lateinit var worker: SpeedTestWorkerService
            worker = SpeedTestWorkerService(
                context = this,
                guids = guidsList,
                onDelayResult = { guid, delay ->
                    MmkvManager.encodeServerTestDelayMillis(guid, delay)
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, guid)
                },
                onSpeedResult = { guid, speed ->
                    MmkvManager.encodeServerTestSpeedMbps(guid, speed)
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_SPEED_SUCCESS, guid)
                },
                onProgress = { text ->
                    NotificationHelper.updateNotification(
                        channelType = NotificationChannelType.CORE_TEST,
                        context = this,
                        content = getString(R.string.connection_runing_task_left, text)
                    )
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, text)
                },
                onFinish = { status ->
                    MessageUtil.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
                    activeSpeedWorkers.remove(worker)
                    if (activeWorkers.isEmpty() && activeSpeedWorkers.isEmpty()) {
                        NotificationHelper.stopForeground(this)
                        stopSelf()
                    }
                }
            )
            activeSpeedWorkers.add(worker)
            worker.start()
        } else {
            NotificationHelper.stopForeground(this)
            stopSelf(startId)
        }
    }

    private fun handleMeasureCancel() {
        LogUtil.i(AppConfig.TAG, "CoreTestService received cancel message, cancelling ${activeWorkers.size} active workers")
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
        val speedSnapshot = ArrayList(activeSpeedWorkers)
        speedSnapshot.forEach { it.cancel() }
        activeSpeedWorkers.clear()
        NotificationHelper.stopForeground(this)
        stopSelf()
    }
}
