package com.watchface.android.wearable.alpha.model

data class ScheduleModel (
    val days : ArrayList<String>,
    val schedule : ArrayList<InnerScheduleModel>
)
