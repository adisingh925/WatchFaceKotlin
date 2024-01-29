package com.example.android.wearable.alpha.utils

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import java.lang.reflect.Type
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class TimeDeserializer : JsonDeserializer<LocalTime> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): LocalTime {
        val timeString = json?.asString ?: throw JsonParseException("Invalid time format")
        return LocalTime.parse(timeString, DateTimeFormatter.ofPattern("HH:mm"))
    }
}
