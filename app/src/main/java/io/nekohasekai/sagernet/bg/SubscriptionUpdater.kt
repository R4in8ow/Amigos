package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.r4in8ow.amigos.R
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.free.FreeServerManager
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val FREE_SERVERS_UPDATE_DELAY_MIN = 360L

    suspend fun reconfigureUpdater() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        val hasFreeServers = SagerDatabase.groupDao.allGroups()
            .any { it.type == GroupType.BASIC && it.name == FreeServerManager.GROUP_NAME }
        if (subscriptions.isEmpty() && !hasFreeServers) return

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        var minDelay = if (subscriptions.isNotEmpty()) {
            subscriptions.minByOrNull { it.subscription!!.autoUpdateDelay }!!.subscription!!.autoUpdateDelay.toLong()
        } else {
            FREE_SERVERS_UPDATE_DELAY_MIN
        }
        if (hasFreeServers) minDelay = minOf(minDelay, FREE_SERVERS_UPDATE_DELAY_MIN)
        val now = System.currentTimeMillis() / 1000L
        var minInitDelay = if (subscriptions.isNotEmpty()) {
            subscriptions.minOf { now - it.subscription!!.lastUpdated - (minDelay * 60) }
        } else {
            0L
        }
        if (minDelay < 15) minDelay = 15
        if (minInitDelay > 60) minInitDelay = 60

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                .apply {
                    if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                }
                .build()
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            if (subscriptions.isNotEmpty()) for (profile in subscriptions) {
                val subscription = profile.subscription!!

                if (((System.currentTimeMillis() / 1000).toInt() - subscription.lastUpdated) < subscription.autoUpdateDelay * 60) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, profile.displayName()
                    )
                )
                nm.notify(2, notification.build())

                GroupUpdater.executeUpdate(profile, false)
            }

            val hasFreeServers = SagerDatabase.groupDao.allGroups()
                .any { it.type == GroupType.BASIC && it.name == FreeServerManager.GROUP_NAME }
            if (hasFreeServers) {
                try {
                    FreeServerManager.refresh()
                    Logs.d("work: free servers refreshed")
                } catch (e: Exception) {
                    Logs.w("work: free servers refresh failed: ${e.message}")
                }
            }

            nm.cancel(2)

            return Result.success()
        }
    }

}