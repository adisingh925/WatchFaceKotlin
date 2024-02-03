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
        readAndParseJsonFile()
        for (scheduleModel in mainSchedule.mainSchedule){
            daysList.addAll(scheduleModel.days)
        }
        adapter.setData(daysList)
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

    override fun onItemClick(data: String) {
        Log.d("MainActivity", "Clicked on $data")
        val intent = Intent(this, TimeLine::class.java)
        intent.putExtra("day", data)

        startActivity(intent)
    }
}
