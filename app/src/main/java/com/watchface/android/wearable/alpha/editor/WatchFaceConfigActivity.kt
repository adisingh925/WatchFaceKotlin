package com.watchface.android.wearable.alpha.editor

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.watchface.android.wearable.alpha.databinding.ActivityWatchFaceConfigBinding
import com.watchface.android.wearable.alpha.sharedpreferences.SharedPreferences

class WatchFaceConfigActivity : ComponentActivity() {

    private lateinit var binding: ActivityWatchFaceConfigBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate()")

        binding = ActivityWatchFaceConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SharedPreferences.init(this)

        // Disable widgets until data loads and values are set.
        binding.colorStylePickerButton.isEnabled = true
        binding.vibrationSwitch.isEnabled = true
        binding.scheduleSwitch.isEnabled = true

        binding.vibrationSwitch.isChecked = SharedPreferences.read("vibration", 0) == 1
        binding.scheduleSwitch.isChecked = SharedPreferences.read("schedule", 0) == 1

        binding.vibrationSwitch.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) {
                SharedPreferences.write("vibration", 1)
            } else {
                SharedPreferences.write("vibration", 0)
            }
        }

        binding.scheduleSwitch.setOnCheckedChangeListener { _, isChecked ->
            if(isChecked) {
                SharedPreferences.write("schedule", 1)
            } else {
                SharedPreferences.write("schedule", 0)
            }
        }
    }

    companion object {
        const val TAG = "WatchFaceConfigActivity"
    }
}
