package com.watchface.android.wearable.alpha

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.GsonBuilder
import com.watchface.android.wearable.alpha.adapter.DayScheduleAdapter
import com.watchface.android.wearable.alpha.databinding.ActivityTimeLineBinding
import com.watchface.android.wearable.alpha.model.InnerScheduleModel
import com.watchface.android.wearable.alpha.model.MainSchedule
import com.watchface.android.wearable.alpha.utils.JsonParser
import com.watchface.android.wearable.alpha.utils.TimeDeserializer
import java.io.InputStream
import java.time.LocalTime

class TimeLine : AppCompatActivity() {

    private val binding by lazy{
        ActivityTimeLineBinding.inflate(layoutInflater)
    }

    private val adapter by lazy {
        DayScheduleAdapter()
    }

    private val recyclerView by lazy {
        binding.recyclerView
    }

    private val daySchedule = ArrayList<InnerScheduleModel>()

    private lateinit var mainSchedule: MainSchedule

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val day = intent.getStringExtra("day")

        if (day != null) {
            initRecyclerView(day)
        }
    }

    private fun initRecyclerView(day : String) {
        mainSchedule = JsonParser(this).readAndParseJsonFile()

        for(scheduleModel in mainSchedule.mainSchedule){
            if(scheduleModel.days.contains(day)){
                daySchedule.addAll(scheduleModel.schedule)
            }
        }

        adapter.setData(daySchedule)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }
}
