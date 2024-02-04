package com.watchface.android.wearable.alpha

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.GsonBuilder
import com.watchface.android.wearable.alpha.adapter.DayScheduleAdapter
import com.watchface.android.wearable.alpha.databinding.ActivityTimeLineBinding
import com.watchface.android.wearable.alpha.model.InnerScheduleModel
import com.watchface.android.wearable.alpha.model.MainSchedule
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
        readAndParseJsonFile()

        for(scheduleModel in mainSchedule.mainSchedule){
            if(scheduleModel.days.contains(day)){
                daySchedule.addAll(scheduleModel.schedule)
            }
        }

        adapter.setData(daySchedule)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun readJsonFile(resourceId: Int): String {
        val inputStream: InputStream = this.resources.openRawResource(resourceId)
        val size: Int = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        return String(buffer, Charsets.UTF_8)
    }

    private fun readAndParseJsonFile() {
        val jsonString = readJsonFile(R.raw.schedule)

        val gson = GsonBuilder()
            .registerTypeAdapter(LocalTime::class.java, TimeDeserializer())
            .create()

        mainSchedule = gson.fromJson(jsonString, MainSchedule::class.java)

        for (scheduleModel in mainSchedule.mainSchedule){
            scheduleModel.schedule.sortBy { it.endTime }
        }
    }
}
