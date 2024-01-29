package com.example.android.wearable.alpha.model

import java.time.LocalTime
import java.util.Date

data class InnerScheduleModel (
    val name : String,
    val startTime : LocalTime,
    val endTime : LocalTime,
    val habits : ArrayList<String>,
    val vibrateOnStart : ArrayList<Long>,
    val vibrateBeforeEnd : ArrayList<Int>,
    val vibrateBeforeEndSecs : Int,
)
