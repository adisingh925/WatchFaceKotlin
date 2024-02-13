package com.watchface.android.wearable.alpha.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("AlarmReceiver", "Alarm received")
        val vibrationPattern = intent?.getLongArrayExtra("vibrationPattern")
        if (context != null) {
            Log.d("AlarmReceiver", "Vibrating")
            val alarmHelper = AlarmHelper(context)
            if (vibrationPattern != null) {
                alarmHelper.vibrate(vibrationPattern)
            }
        }
    }
}

