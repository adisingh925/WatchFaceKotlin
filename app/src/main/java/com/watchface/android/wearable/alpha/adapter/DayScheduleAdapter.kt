package com.watchface.android.wearable.alpha.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.watchface.android.wearable.alpha.databinding.ScheduleItemBinding
import com.watchface.android.wearable.alpha.model.InnerScheduleModel

class DayScheduleAdapter : RecyclerView.Adapter<DayScheduleAdapter.MyViewHolder>() {

    private lateinit var binding: ScheduleItemBinding
    private var daySchedule = ArrayList<InnerScheduleModel>()

    class MyViewHolder(binding: ScheduleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        val time = binding.time
        val schedule = binding.schedule
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        binding = ScheduleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MyViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return daySchedule.size
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.time.text = daySchedule[position].startTime.toString() + " - " + daySchedule[position].endTime.toString() + " -- " + daySchedule[position].name
        holder.schedule.text = daySchedule[position].habits.joinToString(", ")
    }

    fun setData(data: ArrayList<InnerScheduleModel>) {
        daySchedule = data
        notifyDataSetChanged()
    }
}
