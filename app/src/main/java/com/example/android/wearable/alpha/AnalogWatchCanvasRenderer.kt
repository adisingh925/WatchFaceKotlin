/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.wearable.alpha

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.rendering.CanvasComplicationDrawable
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSetting
import com.example.android.wearable.alpha.data.watchface.ColorStyleIdAndResourceIds
import com.example.android.wearable.alpha.data.watchface.WatchFaceColorPalette.Companion.convertToWatchFaceColorPalette
import com.example.android.wearable.alpha.data.watchface.WatchFaceData
import com.example.android.wearable.alpha.model.InnerScheduleModel
import com.example.android.wearable.alpha.model.ScheduleModel
import com.example.android.wearable.alpha.utils.COLOR_STYLE_SETTING
import com.example.android.wearable.alpha.utils.DRAW_HOUR_PIPS_STYLE_SETTING
import com.example.android.wearable.alpha.utils.TimeDeserializer
import com.example.android.wearable.alpha.utils.WATCH_HAND_LENGTH_STYLE_SETTING
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.request.OnDataPointListener
import com.google.android.gms.fitness.request.SensorRequest
import com.google.gson.GsonBuilder
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Default for how long each frame is displayed at expected frame rate.
private const val FRAME_PERIOD_MS_DEFAULT: Long = 16L

/**
 * Renders watch face via data in Room database. Also, updates watch face state based on setting
 * changes by user via [userStyleRepository.addUserStyleListener()].
 */
class AnalogWatchCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    private val complicationSlotsManager: ComplicationSlotsManager,
    currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<AnalogWatchCanvasRenderer.AnalogSharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    FRAME_PERIOD_MS_DEFAULT,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
) {
    class AnalogSharedAssets : SharedAssets {
        override fun onDestroy() {
        }
    }

    private lateinit var scheduleModel: ScheduleModel

    private val nameMap = HashMap<String, Int?>()

    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Represents all data needed to render the watch face. All value defaults are constants. Only
    // three values are changeable by the user (color scheme, ticks being rendered, and length of
    // the minute arm). Those dynamic values are saved in the watch face APIs and we update those
    // here (in the renderer) through a Kotlin Flow.
    private var watchFaceData: WatchFaceData = WatchFaceData()

    // Converts resource ids into Colors and ComplicationDrawable.
    private var watchFaceColors = convertToWatchFaceColorPalette(
        context,
        watchFaceData.activeColorStyle,
        watchFaceData.ambientColorStyle
    )

    // Initializes paint object for painting the clock hands with default values.
    private val clockHandPaint = Paint().apply {
        isAntiAlias = true
        strokeWidth =
            context.resources.getDimensionPixelSize(R.dimen.clock_hand_stroke_width).toFloat()
    }

    private val outerElementPaint = Paint().apply {
        isAntiAlias = true
    }

    // Used to paint the main hour hand text with the hour pips, i.e., 3, 6, 9, and 12 o'clock.
    private val textPaint = Paint().apply {
        isAntiAlias = true
        color = Color.WHITE
        textSize = context.resources.getDimensionPixelSize(R.dimen.hour_mark_size).toFloat()
    }

    private lateinit var hourHandFill: Path
    private lateinit var hourHandBorder: Path
    private lateinit var minuteHandFill: Path
    private lateinit var minuteHandBorder: Path
    private lateinit var secondHand: Path

    private var currentHeartRate: Int = 0
    private var progress: Float = 0f
    private var stepCount: Int = 0
    private val MAX_HEART_RATE = 220

    // Changed when setting changes cause a change in the minute hand arm (triggered by user in
    // updateUserStyle() via userStyleRepository.addUserStyleListener()).
    private var armLengthChangedRecalculateClockHands: Boolean = false

    // Default size of watch face drawing area, that is, a no size rectangle. Will be replaced with
    // valid dimensions from the system.
    private var currentWatchFaceSize = Rect(0, 0, 0, 0)

    private val onDataPointListener = OnDataPointListener { dataPoint ->
        // Handle the incoming data point
        dataPoint.dataType.fields.forEach { field ->
            when (field.name) {
                "steps" -> {
                    val stepsField = dataPoint.getValue(field)
                    stepCount = stepsField.asInt()
                    invalidate() // Trigger a redraw when step count is updated
                }

                "heart_rate" -> {
                    val heartRateField = dataPoint.getValue(field)
                    currentHeartRate = heartRateField.asFloat().toInt()
                    progress = currentHeartRate.toFloat() / MAX_HEART_RATE.toFloat()
                    invalidate() // Trigger a redraw when heart rate is updated
                }
            }
        }
    }

    private fun subscribeToStepCount() {
        // Create a sensor request for step count
        val sensorRequest = SensorRequest.Builder()
            .setDataType(DataType.TYPE_STEP_COUNT_CUMULATIVE)
            .setSamplingRate(3, TimeUnit.SECONDS) // Adjust sampling rate as needed
            .build()

        // Subscribe to step count updates
        getGoogleAccount().let {
            Fitness.getSensorsClient(context, it)
                .add(sensorRequest, onDataPointListener)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Successfully subscribed to step count updates!")
                    } else {
                        Log.d(TAG, "There was a problem subscribing to step count updates.")
                    }
                }
        }
    }

    init {
        scope.launch {
            currentUserStyleRepository.userStyle.collect { userStyle ->
                updateWatchFaceData(userStyle)
            }
        }

        readAndParseJsonFile()
    }

    private fun readJsonFile(resourceId: Int): String {
        val inputStream: InputStream = context.resources.openRawResource(resourceId)
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

        scheduleModel = gson.fromJson(jsonString, ScheduleModel::class.java)
        scheduleModel.schedule.sortBy { it.endTime }
    }

    private fun subscribeToHeartRate() {
        // Create a sensor request for heart rate
        val sensorRequest = SensorRequest.Builder()
            .setDataType(DataType.TYPE_HEART_RATE_BPM)
            .setSamplingRate(3, TimeUnit.SECONDS) // Adjust sampling rate as needed
            .build()

        // Register listener for heart rate updates
        getGoogleAccount().let {
            Fitness.getSensorsClient(context, it)
                .add(sensorRequest, onDataPointListener)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Successfully subscribed to heart rate updates!")
                    } else {
                        Log.d(TAG, "There was a problem subscribing to heart rate updates.")
                    }
                }
        }
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
    }

    /*
     * Triggered when the user makes changes to the watch face through the settings activity. The
     * function is called by a flow.
     */
    private fun updateWatchFaceData(userStyle: UserStyle) {
        Log.d(TAG, "updateWatchFace(): $userStyle")

        var newWatchFaceData: WatchFaceData = watchFaceData

        // Loops through user style and applies new values to watchFaceData.
        for (options in userStyle) {
            when (options.key.id.toString()) {
                COLOR_STYLE_SETTING -> {
                    val listOption = options.value as
                        UserStyleSetting.ListUserStyleSetting.ListOption

                    newWatchFaceData = newWatchFaceData.copy(
                        activeColorStyle = ColorStyleIdAndResourceIds.getColorStyleConfig(
                            listOption.id.toString()
                        )
                    )
                }

                DRAW_HOUR_PIPS_STYLE_SETTING -> {
                    val booleanValue = options.value as
                        UserStyleSetting.BooleanUserStyleSetting.BooleanOption

                    newWatchFaceData = newWatchFaceData.copy(
                        drawHourPips = booleanValue.value
                    )
                }

                WATCH_HAND_LENGTH_STYLE_SETTING -> {
                    val doubleValue = options.value as
                        UserStyleSetting.DoubleRangeUserStyleSetting.DoubleRangeOption

                    // The arm lengths are usually only calculated the first time the watch face is
                    // loaded to reduce the ops in the onDraw(). Because we updated the minute hand
                    // watch length, we need to trigger a recalculation.
                    armLengthChangedRecalculateClockHands = true

                    // Updates length of minute hand based on edits from user.
                    val newMinuteHandDimensions = newWatchFaceData.minuteHandDimensions.copy(
                        lengthFraction = doubleValue.value.toFloat()
                    )

                    newWatchFaceData = newWatchFaceData.copy(
                        minuteHandDimensions = newMinuteHandDimensions
                    )
                }
            }
        }

        // Only updates if something changed.
        if (watchFaceData != newWatchFaceData) {
            watchFaceData = newWatchFaceData

            // Recreates Color and ComplicationDrawable from resource ids.
            watchFaceColors = convertToWatchFaceColorPalette(
                context,
                watchFaceData.activeColorStyle,
                watchFaceData.ambientColorStyle
            )

            // Applies the user chosen complication color scheme changes. ComplicationDrawables for
            // each of the styles are defined in XML so we need to replace the complication's
            // drawables.
            for ((_, complication) in complicationSlotsManager.complicationSlots) {
                ComplicationDrawable.getDrawable(
                    context,
                    watchFaceColors.complicationStyleDrawableId
                )?.let {
                    (complication.renderer as CanvasComplicationDrawable).drawable = it
                }
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        scope.cancel("DigitalWatchCanvasRenderer scope clear() request")
        super.onDestroy()
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)

        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    override fun render(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: AnalogSharedAssets
    ) {
        /**
         * This will clear the canvas with the background color
         */
        val backgroundColor = if (renderParameters.drawMode == DrawMode.AMBIENT) {
            watchFaceColors.ambientBackgroundColor
        } else {
            watchFaceColors.activeBackgroundColor
        }

        canvas.drawColor(backgroundColor)
//
//        // CanvasComplicationDrawable already obeys rendererParameters.
//        drawComplications(canvas, zonedDateTime)
//
//        if (renderParameters.watchFaceLayers.contains(WatchFaceLayer.COMPLICATIONS_OVERLAY)) {
//            drawClockHands(canvas, bounds, zonedDateTime)
//        }
//
//        if (renderParameters.drawMode == DrawMode.INTERACTIVE &&
//            renderParameters.watchFaceLayers.contains(WatchFaceLayer.BASE) &&
//            watchFaceData.drawHourPips
//        ) {
//            drawNumberStyleOuterElement(
//                canvas,
//                bounds,
//                watchFaceData.numberRadiusFraction,
//                watchFaceData.numberStyleOuterCircleRadiusFraction,
//                watchFaceColors.activeOuterElementColor,
//                watchFaceData.numberStyleOuterCircleRadiusFraction,
//                watchFaceData.gapBetweenOuterCircleAndBorderFraction
//            )
//        }

        Log.d(TAG, "render()")

        val currentTime = LocalTime.now()

        /**
         * displaying time in 24h format in the canvas
         */
        drawTimeIn24HourFormat(canvas, bounds)

        /**
         * displaying the day of week, date and the month in the canvas
         */
        drawDate(canvas, bounds)

        /**
         * display the heartbeat and the logo in the canvas
         */
        displayHeartbeatAndLogo(canvas, bounds, "999")

        if (scheduleModel.days.contains(getCurrentDayShortForm())) {
            for (i in scheduleModel.schedule) {
                if (currentTime.isAfter(i.startTime) && currentTime.isBefore(i.endTime)) {
                    if(Duration.between(currentTime,i.endTime).seconds.toInt() == i.vibrateBeforeEndSecs){
                        if(nameMap[i.name+"e"] == null) {
                            Log.d("Vibrate", "end Vibrate")
                            nameMap[i.name+"e"] = 0
                            for(j in nameMap) {
                                if(j.key != i.name+"e") {
                                    nameMap[j.key] = null
                                }

                                if(j.key != i.name+"s") {
                                    nameMap[j.key] = null
                                }
                            }
                            vibrate(i.vibrateBeforeEnd.toLongArray())
                        }
                    }

                    if(nameMap[i.name+"s"] == null) {
                        Log.d("Vibrate", "start Vibrate")
                        nameMap[i.name+"s"] = 0
                        for(j in nameMap) {
                            if(j.key != i.name+"s") {
                                nameMap[j.key] = null
                            }

                            if(j.key != i.name+"e") {
                                nameMap[j.key] = null
                            }
                        }
                        vibrate(i.vibrateOnStart.toLongArray())
                    }

                    val time = "${
                        getDifferenceOfLocalTime(
                            i.startTime,
                            i.endTime
                        )
                    } | ${convertLocalTimeTo24HourFormat(i.startTime)} - ${
                        convertLocalTimeTo24HourFormat(i.endTime)
                    }"
                    drawProgressArc(
                        canvas,
                        bounds,
                        (getLocalTimeDifferenceInMinutes(
                            i.startTime,
                            currentTime
                        ) * (60F / getLocalTimeDifferenceInMinutes(
                            i.startTime,
                            i.endTime
                        ).toFloat())),
                        getDifferenceOfLocalTime(currentTime, i.startTime),
                        getDifferenceOfLocalTime(currentTime, i.endTime),
                        (getNumberOf15MinIntervalBetweenLocalTime(
                            i.startTime,
                            i.endTime
                        ) + 1).toInt()
                    )
                    displayCurrentScheduleWithTime(canvas, bounds, i.name, time)

                    drawCurrentScheduleHabits(canvas, i.habits)
                }

                val nextGreatest = findNextGreatest(currentTime)

                if (nextGreatest != null) {
                    drawNextSchedule(
                        canvas,
                        nextGreatest.name,
                        "${convertLocalTimeTo24HourFormat(nextGreatest.startTime)} - ${
                            convertLocalTimeTo24HourFormat(nextGreatest.endTime)
                        }"
                    )
                }
            }
        }

        /**
         * display the battery percentage in the canvas
         */
        drawBatteryPercentage(canvas, bounds, getWatchBatteryLevel(context).toString())

        /**
         * display number of steps in the canvas
         */
        drawNumberOfSteps(canvas, bounds, "37,565")
    }

    private fun findNextGreatest(currentTime: LocalTime): InnerScheduleModel? {
        var nextGreaterValue: InnerScheduleModel? = null

        for (element in scheduleModel.schedule) {
            if (element.startTime > currentTime) {
                nextGreaterValue = element
                break
            }
        }

        return nextGreaterValue
    }

    private fun vibrate(pattern: LongArray) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun getNumberOf15MinIntervalBetweenLocalTime(time1: LocalTime, time2: LocalTime): Long {
        return Duration.between(time1, time2).toMinutes() / 15
    }

    private fun getLocalTimeDifferenceInMinutes(time1: LocalTime, time2: LocalTime): Long {
        return Duration.between(time1, time2).seconds
    }

    private fun getDifferenceOfLocalTime(time1: LocalTime, time2: LocalTime): String {
        val duration = Duration.between(time1, time2)

        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val seconds = duration.seconds

        return if (hours != 0L && minutes != 0L) {
            "${hours}h ${minutes}m"
        } else if (hours == 0L && minutes != 0L) {
            "${minutes}m"
        } else if (hours != 0L) {
            "${hours}h"
        } else {
            "${seconds}s"
        }
    }

    private fun convertLocalTimeTo24HourFormat(time: LocalTime): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return time.format(formatter)
    }

    private fun getCurrentDayShortForm(): String {
        val formatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        return LocalDate.now().format(formatter)
    }

    private fun getTextPaint(fontSize : Float, alignment: Paint.Align, textColor: Int) : Paint{
        return Paint().apply {
            color = textColor
            textAlign = alignment
            textSize = fontSize
        }
    }

    private fun displayCurrentScheduleWithTime(
        canvas: Canvas,
        bounds: Rect,
        scheduleName: String,
        scheduleTime: String
    ) {
        val heartBeatPaint = getTextPaint(12f, Paint.Align.CENTER, Color.WHITE)
        val text1Y = bounds.top + 40f
        val text2Y = bounds.top + 55f

        canvas.drawText(scheduleName, bounds.exactCenterX(), text1Y, heartBeatPaint)
        canvas.drawText(scheduleTime, bounds.exactCenterX(), text2Y, heartBeatPaint)
    }

    private fun getWatchBatteryLevel(context: Context): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level != -1 && scale != -1) {
            // Calculate battery percentage
            (level.toFloat() / scale.toFloat() * 100).toInt()
        } else {
            // Unable to retrieve battery level
            -1
        }
    }

    private fun drawNumberOfSteps(canvas: Canvas, bounds: Rect, s: String) {
        val stepsPaint = getTextPaint(15f, Paint.Align.LEFT, Color.WHITE)
        val logoDrawable = getLogoDrawable(R.drawable.walk)
        val logoWidth = 20
        val logoHeight = 20

        val logoLeft = bounds.right.toFloat() - logoWidth - 60f
        val logoTop = bounds.centerY() - logoHeight / 2 - 17

        logoDrawable.setBounds(
            logoLeft.toInt(),
            logoTop,
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight)
        )

        logoDrawable.draw(canvas)

        val additionalTextX = logoLeft + logoWidth // Adjust the horizontal position
        val additionalTextY = bounds.centerY() + stepsPaint.textSize / 2 - 20 // Center the additional text vertically

        // Draw the additional text
        canvas.drawText(s, additionalTextX, additionalTextY, stepsPaint)
    }

    private fun drawBatteryPercentage(
        canvas: Canvas,
        bounds: Rect,
        batteryPercentage: String
    ) {
        val batteryPaint = getTextPaint(15f, Paint.Align.LEFT, Color.WHITE)
        val text = "$batteryPercentage%"
        val textX = 30f // Adjust the horizontal position
        val textY = centerY - 14f // Adjust the vertical position
        canvas.drawText(text, textX, textY, batteryPaint)

        // Draw an image on the left side of the text
        val logoDrawable = getLogoDrawable(R.drawable.battery) // Replace this with your method to get the logo drawable
        val logoWidth = 20
        val logoHeight = 20
        val logoLeft = 10f // Adjust the horizontal position
        val logoTop = bounds.exactCenterY() - 30f

        logoDrawable.setBounds(
            logoLeft.toInt(),
            logoTop.toInt(),
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight).toInt()
        )

        logoDrawable.draw(canvas)
    }

    private fun drawCurrentScheduleHabits(
        canvas: Canvas,
        habits: ArrayList<String>
    ) {
        val currentSchedulePaint = getTextPaint(15f, Paint.Align.CENTER, Color.WHITE)
        val textOffset = 20f // Adjust vertical spacing between texts

        when (habits.size) {
            1 -> {
                canvas.drawText(
                    habits[0],
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )
            }
            2 -> {
                canvas.drawText(
                    habits[0],
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )

                canvas.drawText(
                    habits[1],
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 3,
                    currentSchedulePaint
                )
            }
            3 -> {
                canvas.drawText(
                    "${habits[0]} | ${habits[1]}",
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )

                canvas.drawText(
                    habits[2],
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 3,
                    currentSchedulePaint
                )
            }
            4 -> {
                canvas.drawText(
                    "${habits[0]} | ${habits[1]}",
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )

                canvas.drawText(
                    "${habits[2]} | ${habits[3]}",
                    (canvas.width / 2).toFloat(),
                    canvas.height - textPaint.textSize - textOffset * 3,
                    currentSchedulePaint
                )
            }
        }
    }

    private fun drawNextSchedule(
        canvas: Canvas,
        nextScheduleName: String,
        nextScheduleTime: String
    ) {
        val nextSchedulePaint = getTextPaint(12f, Paint.Align.CENTER, Color.WHITE)

        canvas.drawText(
            nextScheduleName,
            (canvas.width / 2).toFloat(),
            canvas.height - nextSchedulePaint.textSize - 15,
            nextSchedulePaint
        )

        canvas.drawText(
            nextScheduleTime,
            (canvas.width / 2).toFloat(),
            canvas.height - nextSchedulePaint.textSize,
            nextSchedulePaint
        )
    }

    /**
     * This function will display the heartbeat and the logo in the canvas at the left
     */
    private fun displayHeartbeatAndLogo(canvas: Canvas, bounds: Rect, heartBeat: String) {
        val heartBeatPaint = getTextPaint(15f, Paint.Align.LEFT, Color.WHITE)
        val logoDrawable = getLogoDrawable(R.drawable.heartbeat) // Replace this with your method to get the logo drawable
        val logoWidth = 20
        val logoHeight = 20

        val logoLeft = bounds.right.toFloat() - logoWidth - 50f // Adjust the horizontal position to the right
        val logoTop = bounds.centerY() - logoHeight / 2 - 40 // Center the image vertically

        logoDrawable.setBounds(
            logoLeft.toInt(),
            logoTop,
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight)
        )

        logoDrawable.draw(canvas)

        val additionalTextX = logoLeft + logoWidth + 5f // Adjust the horizontal position
        val additionalTextY = bounds.centerY() + heartBeatPaint.textSize / 2 - 42 // Center the additional text vertically

        // Draw the additional text
        canvas.drawText(heartBeat, additionalTextX, additionalTextY, heartBeatPaint)
    }

    /**
     * This function will draw the progress arc in the canvas at the top
     */
    private fun drawProgressArc(
        canvas: Canvas,
        bounds: Rect,
        progress: Float,
        timePassed: String,
        timeLeft: String,
        interval: Int
    ) {
        val paint = Paint().apply {
            color = Color.GREEN // Customize the arc color
            style = Paint.Style.STROKE // Use STROKE to create an outline
            strokeWidth = 10f // Adjust stroke width as needed
            strokeCap = Paint.Cap.ROUND // Use ROUND for rounded ends
        }

        val totalPathPaint = Paint().apply {
            color = Color.argb(128, 128, 128, 128) // Adjust alpha for desired transparency
            style = Paint.Style.STROKE
            strokeWidth = 15f // Adjust stroke width as needed
            strokeCap = Paint.Cap.ROUND
        }

        val linePaint = Paint().apply {
            color = Color.argb(128, 128, 128, 128) // Color of the radial lines
            style = Paint.Style.STROKE
            strokeWidth = 2f // Adjust line stroke width as needed
            strokeCap = Paint.Cap.ROUND
        }

        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        val progressRadius = bounds.width() / 2 - 10f // Adjust the radius as needed

        // Draw a hollow arc at the top with outlines and curved corners
        val arcRect = RectF(
            centerX - progressRadius,
            centerY - progressRadius,
            centerX + progressRadius,
            centerY + progressRadius
        )

        canvas.drawArc(arcRect, -120f, 60f, false, totalPathPaint)
        canvas.drawArc(arcRect, -120f, progress, false, paint) // -90f for top-aligned arc

        // Draw thin radial lines inside the arc
        for (i in 0 until interval) {
            val angle = -120f + (i.toFloat() / (interval - 1)) * 60f // Distribute lines evenly inside the arc
            val startX = centerX + (progressRadius + 6f) * cos(Math.toRadians(angle.toDouble())).toFloat()
            val startY = centerY + (progressRadius + 6f) * sin(Math.toRadians(angle.toDouble())).toFloat()
            val endX = centerX + (progressRadius - 7f) * cos(Math.toRadians(angle.toDouble())).toFloat()
            val endY = centerY + (progressRadius - 7f) * sin(Math.toRadians(angle.toDouble())).toFloat()

            canvas.drawLine(startX, startY, endX, endY, linePaint)
        }

        val textStartX = centerX - progressRadius * cos(Math.toRadians(120.0)).toFloat() // Adjust horizontal position
        val textStartY = centerY - progressRadius * sin(Math.toRadians(120.0)).toFloat() + 25f // Adjust vertical position

        val textEndX = centerX + progressRadius * cos(Math.toRadians(-120.0)).toFloat() // Adjust horizontal position
        val textEndY = centerY + progressRadius * sin(Math.toRadians(-60.0)).toFloat() + 25f // Adjust vertical position

        val textPaint = Paint().apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 10f
        }

        canvas.drawText(timeLeft, textStartX, textStartY, textPaint)
        canvas.drawText(timePassed, textEndX, textEndY, textPaint)
    }

    private fun getLogoDrawable(itemName: Int): Drawable {
        return ContextCompat.getDrawable(context, itemName)!!
    }

    private fun drawDate(canvas: Canvas, bounds: Rect) {
        val text = SimpleDateFormat("E d MMM", Locale.getDefault()).format(Date())
        val datePaint = getTextPaint(20f, Paint.Align.CENTER, Color.WHITE)
        val centerYDate = bounds.exactCenterY() + 30
        canvas.drawText(text, bounds.exactCenterX(), centerYDate, datePaint)
    }

    /**
     * This function will draw the time in 24h format in the canvas
     */
    private fun drawTimeIn24HourFormat(canvas: Canvas, bounds: Rect) {
        val text = convertMillisTo24HourFormat(System.currentTimeMillis())
        val timePaint = getTextPaint(80f, Paint.Align.CENTER, Color.WHITE)
        canvas.drawText(text, bounds.exactCenterX(), bounds.exactCenterY(), timePaint)
    }

    /**
     * This function will convert the time in millis to 24h format
     */
    private fun convertMillisTo24HourFormat(millis: Long): String {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val date = Date(millis)
        return dateFormat.format(date)
    }

    // ----- All drawing functions -----
    private fun drawComplications(canvas: Canvas, zonedDateTime: ZonedDateTime) {
        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.render(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    private fun drawClockHands(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime
    ) {
        // Only recalculate bounds (watch face size/surface) has changed or the arm of one of the
        // clock hands has changed (via user input in the settings).
        // NOTE: Watch face surface usually only updates one time (when the size of the device is
        // initially broadcast).
        if (currentWatchFaceSize != bounds || armLengthChangedRecalculateClockHands) {
            armLengthChangedRecalculateClockHands = false
            currentWatchFaceSize = bounds
            recalculateClockHands(bounds)
        }

        // Retrieve current time to calculate location/rotation of watch arms.
        val secondOfDay = zonedDateTime.toLocalTime().toSecondOfDay()

        // Determine the rotation of the hour and minute hand.

        // Determine how many seconds it takes to make a complete rotation for each hand
        // It takes the hour hand 12 hours to make a complete rotation
        val secondsPerHourHandRotation = Duration.ofHours(12).seconds
        // It takes the minute hand 1 hour to make a complete rotation
        val secondsPerMinuteHandRotation = Duration.ofHours(1).seconds

        // Determine the angle to draw each hand expressed as an angle in degrees from 0 to 360
        // Since each hand does more than one cycle a day, we are only interested in the remainder
        // of the secondOfDay modulo the hand interval
        val hourRotation = secondOfDay.rem(secondsPerHourHandRotation) * 360.0f /
            secondsPerHourHandRotation
        val minuteRotation = secondOfDay.rem(secondsPerMinuteHandRotation) * 360.0f /
            secondsPerMinuteHandRotation

        canvas.withScale(
            x = WATCH_HAND_SCALE,
            y = WATCH_HAND_SCALE,
            pivotX = bounds.exactCenterX(),
            pivotY = bounds.exactCenterY()
        ) {
            val drawAmbient = renderParameters.drawMode == DrawMode.AMBIENT

            clockHandPaint.style = if (drawAmbient) Paint.Style.STROKE else Paint.Style.FILL
            clockHandPaint.color = if (drawAmbient) {
                watchFaceColors.ambientPrimaryColor
            } else {
                watchFaceColors.activePrimaryColor
            }

            // Draw hour hand.
            withRotation(hourRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                drawPath(hourHandBorder, clockHandPaint)
            }

            // Draw minute hand.
            withRotation(minuteRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                drawPath(minuteHandBorder, clockHandPaint)
            }

            // Draw second hand if not in ambient mode
            if (!drawAmbient) {
                clockHandPaint.color = watchFaceColors.activeSecondaryColor

                // Second hand has a different color style (secondary color) and is only drawn in
                // active mode, so we calculate it here (not above with others).
                val secondsPerSecondHandRotation = Duration.ofMinutes(1).seconds
                val secondsRotation = secondOfDay.rem(secondsPerSecondHandRotation) * 360.0f /
                    secondsPerSecondHandRotation
                clockHandPaint.color = watchFaceColors.activeSecondaryColor

                withRotation(secondsRotation, bounds.exactCenterX(), bounds.exactCenterY()) {
                    drawPath(secondHand, clockHandPaint)
                }
            }
        }
    }

    /*
     * Rarely called (only when watch face surface changes; usually only once) from the
     * drawClockHands() method.
     */
    private fun recalculateClockHands(bounds: Rect) {
        Log.d(TAG, "recalculateClockHands()")
        hourHandBorder =
            createClockHand(
                bounds,
                watchFaceData.hourHandDimensions.lengthFraction,
                watchFaceData.hourHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.hourHandDimensions.xRadiusRoundedCorners,
                watchFaceData.hourHandDimensions.yRadiusRoundedCorners
            )
        hourHandFill = hourHandBorder

        minuteHandBorder =
            createClockHand(
                bounds,
                watchFaceData.minuteHandDimensions.lengthFraction,
                watchFaceData.minuteHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.minuteHandDimensions.xRadiusRoundedCorners,
                watchFaceData.minuteHandDimensions.yRadiusRoundedCorners
            )
        minuteHandFill = minuteHandBorder

        secondHand =
            createClockHand(
                bounds,
                watchFaceData.secondHandDimensions.lengthFraction,
                watchFaceData.secondHandDimensions.widthFraction,
                watchFaceData.gapBetweenHandAndCenterFraction,
                watchFaceData.secondHandDimensions.xRadiusRoundedCorners,
                watchFaceData.secondHandDimensions.yRadiusRoundedCorners
            )
    }

    /**
     * Returns a round rect clock hand if {@code rx} and {@code ry} equals to 0, otherwise return a
     * rect clock hand.
     *
     * @param bounds The bounds use to determine the coordinate of the clock hand.
     * @param length Clock hand's length, in fraction of {@code bounds.width()}.
     * @param thickness Clock hand's thickness, in fraction of {@code bounds.width()}.
     * @param gapBetweenHandAndCenter Gap between inner side of arm and center.
     * @param roundedCornerXRadius The x-radius of the rounded corners on the round-rectangle.
     * @param roundedCornerYRadius The y-radius of the rounded corners on the round-rectangle.
     */
    private fun createClockHand(
        bounds: Rect,
        length: Float,
        thickness: Float,
        gapBetweenHandAndCenter: Float,
        roundedCornerXRadius: Float,
        roundedCornerYRadius: Float
    ): Path {
        val width = bounds.width()
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val left = centerX - thickness / 2 * width
        val top = centerY - (gapBetweenHandAndCenter + length) * width
        val right = centerX + thickness / 2 * width
        val bottom = centerY - gapBetweenHandAndCenter * width
        val path = Path()

        if (roundedCornerXRadius != 0.0f || roundedCornerYRadius != 0.0f) {
            path.addRoundRect(
                left,
                top,
                right,
                bottom,
                roundedCornerXRadius,
                roundedCornerYRadius,
                Path.Direction.CW
            )
        } else {
            path.addRect(
                left,
                top,
                right,
                bottom,
                Path.Direction.CW
            )
        }
        return path
    }

    private fun drawNumberStyleOuterElement(
        canvas: Canvas,
        bounds: Rect,
        numberRadiusFraction: Float,
        outerCircleStokeWidthFraction: Float,
        outerElementColor: Int,
        numberStyleOuterCircleRadiusFraction: Float,
        gapBetweenOuterCircleAndBorderFraction: Float
    ) {
        // Draws text hour indicators (12, 3, 6, and 9).
        val textBounds = Rect()
        textPaint.color = outerElementColor
        for (i in 0 until 4) {
            val rotation = 0.5f * (i + 1).toFloat() * Math.PI
            val dx = sin(rotation).toFloat() * numberRadiusFraction * bounds.width().toFloat()
            val dy = -cos(rotation).toFloat() * numberRadiusFraction * bounds.width().toFloat()
            textPaint.getTextBounds(HOUR_MARKS[i], 0, HOUR_MARKS[i].length, textBounds)
            canvas.drawText(
                HOUR_MARKS[i],
                bounds.exactCenterX() + dx - textBounds.width() / 2.0f,
                bounds.exactCenterY() + dy + textBounds.height() / 2.0f,
                textPaint
            )
        }

        // Draws dots for the remain hour indicators between the numbers above.
        outerElementPaint.strokeWidth = outerCircleStokeWidthFraction * bounds.width()
        outerElementPaint.color = outerElementColor
        canvas.save()
        for (i in 0 until 12) {
            if (i % 3 != 0) {
                drawTopMiddleCircle(
                    canvas,
                    bounds,
                    numberStyleOuterCircleRadiusFraction,
                    gapBetweenOuterCircleAndBorderFraction
                )
            }
            canvas.rotate(360.0f / 12.0f, bounds.exactCenterX(), bounds.exactCenterY())
        }
        canvas.restore()
    }

    /** Draws the outer circle on the top middle of the given bounds. */
    private fun drawTopMiddleCircle(
        canvas: Canvas,
        bounds: Rect,
        radiusFraction: Float,
        gapBetweenOuterCircleAndBorderFraction: Float
    ) {
        outerElementPaint.style = Paint.Style.FILL_AND_STROKE

        // X and Y coordinates of the center of the circle.
        val centerX = 0.5f * bounds.width().toFloat()
        val centerY = bounds.width() * (gapBetweenOuterCircleAndBorderFraction + radiusFraction)

        canvas.drawCircle(
            centerX,
            centerY,
            radiusFraction * bounds.width(),
            outerElementPaint
        )
    }

    private val fitnessOptions = FitnessOptions.builder()
        .addDataType(DataType.TYPE_HEART_RATE_BPM, FitnessOptions.ACCESS_READ)
        .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
        .build()

    private fun getGoogleAccount(): GoogleSignInAccount {
        return GoogleSignIn.getAccountForExtension(context, fitnessOptions)
    }

    companion object {
        private const val TAG = "AnalogWatchCanvasRenderer"

        // Painted between pips on watch face for hour marks.
        private val HOUR_MARKS = arrayOf("3", "6", "9", "12")

        // Used to canvas.scale() to scale watch hands in proper bounds. This will always be 1.0.
        private const val WATCH_HAND_SCALE = 1.0f
    }
}
