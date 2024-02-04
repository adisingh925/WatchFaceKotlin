package com.watchface.android.wearable.alpha.utils

import android.content.Context
import com.google.gson.GsonBuilder
import com.watchface.android.wearable.alpha.R
import com.watchface.android.wearable.alpha.model.MainSchedule
import java.io.InputStream
import java.time.LocalTime

class JsonParser(private val context: Context) {

    private fun readJsonFile(resourceId: Int): String {
        val inputStream: InputStream = context.resources.openRawResource(resourceId)
        val size: Int = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        return String(buffer, Charsets.UTF_8)
    }

    fun readAndParseJsonFile(): MainSchedule {
        val jsonString = readJsonFile(R.raw.schedule)

        val gson = GsonBuilder()
            .registerTypeAdapter(LocalTime::class.java, TimeDeserializer())
            .create()

        val mainSchedule = gson.fromJson(jsonString, MainSchedule::class.java)
        for (scheduleModel in mainSchedule.mainSchedule) {
            scheduleModel.schedule.sortBy { it.startTime }
        }

        return mainSchedule
    }
}
