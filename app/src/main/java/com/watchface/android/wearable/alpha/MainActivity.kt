package com.watchface.android.wearable.alpha

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.GsonBuilder
import com.watchface.android.wearable.alpha.adapter.RecyclerAdapter
import com.watchface.android.wearable.alpha.databinding.ActivityMainBinding
import com.watchface.android.wearable.alpha.model.MainSchedule
import com.watchface.android.wearable.alpha.model.ScheduleModel
import com.watchface.android.wearable.alpha.utils.JsonParser
import com.watchface.android.wearable.alpha.utils.TimeDeserializer
import java.io.InputStream
import java.time.LocalTime

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
