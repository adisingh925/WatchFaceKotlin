package com.watchface.android.wearable.alpha

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.watchface.android.wearable.alpha.AnalogWatchFaceService.Companion.TAG
import com.watchface.android.wearable.alpha.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        requestLocationPermission()
    }

    private fun updatePermissionStatus(granted: Boolean) {
        // Update the TextView based on the permission status
        binding.permissionStatusText.text = if (granted) {
            "Location permission granted"
        } else {
            "Location permission denied"
        }
    }

    private fun requestLocationPermission() {
        // Check if foreground location permission is granted
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Request foreground location permission
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            // Permission is already granted
            Log.d(TAG, "Foreground location permission is already granted")
            updatePermissionStatus(true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Foreground location permission granted
                    Log.d(TAG, "Foreground location permission granted")
                    updatePermissionStatus(true)
                } else {
                    // Foreground location permission denied
                    Log.d(TAG, "Foreground location permission denied")
                    updatePermissionStatus(false)
                }
            }
        }
    }
}
