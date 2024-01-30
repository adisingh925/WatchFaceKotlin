package com.watchface.android.wearable.alpha.model

import java.time.LocalTime

data class InnerScheduleModel (
    val name : String,
    val startTime : LocalTime,
    val endTime : LocalTime,
    val habits : ArrayList<String>,
    val vibrateOnStart : ArrayList<Long>,
    val vibrateBeforeEnd : ArrayList<Long>,
    val vibrateBeforeEndSecs : Int,
)
