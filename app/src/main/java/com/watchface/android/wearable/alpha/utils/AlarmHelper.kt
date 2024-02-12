package com.watchface.android.wearable.alpha.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import com.watchface.android.wearable.alpha.sharedpreferences.SharedPreferences
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.*

class AlarmHelper(private val context: Context) {

    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @RequiresApi(Build.VERSION_CODES.S)
    fun setExactLocalTimeAlarm(localTime: LocalTime, currentDateTime: ZonedDateTime) {

        val utcTimeInMillis = Duration.between(
            currentDateTime.toLocalTime(),
            localTime
        ).toMillis() + System.currentTimeMillis()

        Log.d("AlarmHelper", "Setting alarm for $localTime, $utcTimeInMillis")

        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (!alarmManager.canScheduleExactAlarms()) {
            Log.d("AlarmHelper", "Can't schedule exact alarms")
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                utcTimeInMillis,
                pendingIntent)
        }else{
            Log.d("AlarmHelper", "Can schedule exact alarms")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                utcTimeInMillis,
                pendingIntent
            )
        }
    }

    fun cancelAlarm() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
        }
    }

    fun vibrate() {
        vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

