package com.watchface.android.wearable.alpha

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.watchface.android.wearable.alpha.adapter.DayScheduleAdapter
import com.watchface.android.wearable.alpha.databinding.ActivityTimeLineBinding
import com.watchface.android.wearable.alpha.model.InnerScheduleModel
import com.watchface.android.wearable.alpha.model.MainSchedule
import com.watchface.android.wearable.alpha.utils.JsonParser
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale

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

            if(scheduleModel.days.contains(intent.getStringExtra("day")
                    ?.let { getNextDayOfWeekShortForm(it) })){
                for(innerScheduleModel in scheduleModel.schedule){
                    if(innerScheduleModel.startTime <= LocalTime.of(3,0,0)){
                        daySchedule.add(innerScheduleModel)
                    }
                }
            }
        }

        adapter.setData(daySchedule)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun getNextDayOfWeekShortForm(currentDayShortForm: String): String {
        val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val calendar = Calendar.getInstance()
        val currentDate = dateFormat.parse(currentDayShortForm)

        // Set the calendar to the given day of the week
        if (currentDate != null) {
            calendar.time = currentDate
        }

        // Add one day to get the next day of the week
        calendar.add(Calendar.DAY_OF_WEEK, 1)

        // Get the short form of the next day of the week
        return dateFormat.format(calendar.time)
    }
}
