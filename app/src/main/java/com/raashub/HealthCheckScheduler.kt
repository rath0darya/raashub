package com.raashub

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Daily local-time scheduler. Two alarms are always armed: the next 05:00 and
 * the next 17:00 occurrence in Asia/Kolkata. Android may defer inexact alarms;
 * the receiver re-arms both after every trigger and after device boot/time changes.
 */
object HealthCheckScheduler {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val hours = listOf(5, 17)

    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = ZonedDateTime.now(zone)
        hours.forEach { hour ->
            var target = now.toLocalDate().atTime(hour, 0).atZone(zone)
            if (!target.isAfter(now)) target = target.plusDays(1)
            val requestCode = if (hour == 5) 5005 else 5017
            val intent = Intent(context, ScheduledHealthCheckReceiver::class.java)
                .setAction(ACTION_RUN).putExtra(EXTRA_HOUR, hour)
            val pending = PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = target.toInstant().toEpochMilli()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } else {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                }
            } catch (_: SecurityException) {
                runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
            }
        }
    }

    fun nextRunLabel(): String {
        val now = ZonedDateTime.now(zone)
        val next = hours.map { hour ->
            var value = now.toLocalDate().atTime(hour, 0).atZone(zone)
            if (!value.isAfter(now)) value = value.plusDays(1)
            value
        }.minOrNull() ?: return "05:00 and 17:00 IST"
        return next.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM, hh:mm a")) + " IST"
    }

    const val ACTION_RUN = "com.raashub.SCHEDULED_HEALTH_CHECK"
    const val EXTRA_HOUR = "scheduled_hour"
}

class ScheduledHealthCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        HealthCheckScheduler.schedule(context)
        val service = Intent(context, HealthCheckService::class.java)
            .setAction(HealthCheckScheduler.ACTION_RUN)
            .putExtra(HealthCheckScheduler.EXTRA_HOUR, intent?.getIntExtra(HealthCheckScheduler.EXTRA_HOUR, -1) ?: -1)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service)
            else context.startService(service)
        } catch (_: Exception) {
            // The next alarm remains scheduled; the app also retries overdue work on launch.
        }
    }
}

class HealthCheckBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        HealthCheckScheduler.schedule(context)
    }
}
