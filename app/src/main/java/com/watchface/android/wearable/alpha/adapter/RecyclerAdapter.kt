package com.watchface.android.wearable.alpha.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.watchface.android.wearable.alpha.databinding.RecyclerViewItemBinding

class RecyclerAdapter(private val context: Context, private val onItemClickListener: OnItemClickListener) : RecyclerView.Adapter<RecyclerAdapter.MyViewHolder>() {

    private lateinit var binding: RecyclerViewItemBinding
    private var days = ArrayList<String>()

    interface OnItemClickListener {
        fun onItemClick(data: String)
    }

    class MyViewHolder(binding: RecyclerViewItemBinding) : RecyclerView.ViewHolder(binding.root) {
        val day = binding.day
        val cardView = binding.cardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MyViewHolder {
        binding = RecyclerViewItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MyViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return days.size
    }

    override fun onBindViewHolder(holder: MyViewHolder, position: Int) {
        holder.day.text = days[position]

        holder.cardView.setOnClickListener {
            onItemClickListener.onItemClick(days[position])
        }
    }

    fun setData(data: ArrayList<String>){
        days = data
        notifyDataSetChanged()
    }
}