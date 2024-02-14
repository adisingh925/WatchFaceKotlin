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
import java.time.LocalTime
import java.util.*

class AlarmHelper(private val context: Context) {

    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    @RequiresApi(Build.VERSION_CODES.S)
    fun setExactLocalTimeAlarm(localTime: LocalTime, vibrateBeforeEndSecs : Int, vibrationPattern : List<Long>) {

        val calendar = Calendar.getInstance()

        calendar.apply {
            set(Calendar.HOUR_OF_DAY, localTime.hour)
            set(Calendar.MINUTE, localTime.minute)
            set(Calendar.SECOND, localTime.second)
            set(Calendar.MILLISECOND, 0)
        }

        if(SharedPreferences.read("day", 0) == 1){
            SharedPreferences.write("day", 0)
            calendar.add(Calendar.DATE, 1)
        }else if(calendar.before(Calendar.getInstance())){
            Log.d("AlarmHelper", "Alarm time is in the past")
            calendar.add(Calendar.DATE, 1)
        }

        val requestCode = SharedPreferences.read("requestCode",0)
        SharedPreferences.write("requestCode", requestCode + 1)

        val intent = Intent(context, AlarmReceiver::class.java)
        intent.putExtra("vibrationPattern", vibrationPattern.toLongArray())
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        if (!alarmManager.canScheduleExactAlarms()) {
            Log.d("AlarmHelper", "Can't schedule exact alarms")
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis - (vibrateBeforeEndSecs * 1000),
                pendingIntent)
        }else{
            Log.d("AlarmHelper", "Can schedule exact alarms")
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis - (vibrateBeforeEndSecs * 1000),
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

    fun cancelAllAlarms(){
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pendingIntent?.let {
            alarmManager.cancel(it)
        }
    }

    fun vibrate(pattern: LongArray) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

