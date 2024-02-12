package com.watchface.android.wearable.alpha.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Handle the alarm trigger here
        if (context != null) {
            // Vibrate the device
            val alarmHelper = AlarmHelper(context)
            alarmHelper.vibrate()
        }
    }
}

