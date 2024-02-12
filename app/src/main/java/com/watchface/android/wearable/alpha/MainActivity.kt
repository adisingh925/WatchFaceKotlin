package com.watchface.android.wearable.alpha

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Field
import com.watchface.android.wearable.alpha.AnalogWatchFaceService.Companion.TAG
import com.watchface.android.wearable.alpha.adapter.RecyclerAdapter
import com.watchface.android.wearable.alpha.databinding.ActivityMainBinding
import com.watchface.android.wearable.alpha.model.MainSchedule
import com.watchface.android.wearable.alpha.utils.JsonParser

class MainActivity : AppCompatActivity(), RecyclerAdapter.OnItemClickListener {

    private val binding by lazy{
        ActivityMainBinding.inflate(layoutInflater)
    }

    private val recyclerView by lazy{
        binding.recyclerView
    }

    private val adapter by lazy{
        RecyclerAdapter(this, this)
    }

    private lateinit var mainSchedule: MainSchedule
    private val daysList = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        initRecyclerView()
    }

    private fun initRecyclerView() {
        mainSchedule = JsonParser(this).readAndParseJsonFile()
        for (scheduleModel in mainSchedule.mainSchedule){
            daysList.addAll(scheduleModel.days)
        }
        adapter.setData(daysList)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    override fun onItemClick(data: String) {
        val intent = Intent(this, TimeLine::class.java)
        intent.putExtra("day", data)
        startActivity(intent)
    }
}
