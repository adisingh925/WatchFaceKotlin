
package com.watchface.android.wearable.alpha

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.google.gson.GsonBuilder
import com.watchface.android.wearable.alpha.model.InnerScheduleModel
import com.watchface.android.wearable.alpha.model.MainSchedule
import com.watchface.android.wearable.alpha.sharedpreferences.SharedPreferences
import com.watchface.android.wearable.alpha.utils.TimeDeserializer
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

// Default for how long each frame is displayed at expected frame rate.
private const val FRAME_PERIOD_MS_DEFAULT: Long = 16L

/**
 * Renders the watch face on the canvas.
 */
class AnalogWatchCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository,
    canvasType: Int
) : Renderer.CanvasRenderer2<AnalogWatchCanvasRenderer.AnalogSharedAssets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    FRAME_PERIOD_MS_DEFAULT,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false
), SensorEventListener {
    class AnalogSharedAssets : SharedAssets {
        override fun onDestroy() {
        }
    }

    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val stepCounterSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    private val heartRateSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    private lateinit var mainSchedule: MainSchedule
    private val nameMap = HashMap<String, Int?>()
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartRateValue = 0
    private var stepCount = 0

    init {
        readAndParseJsonFile()
        sensorManager.registerListener(this, stepCounterSensor, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
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

        mainSchedule = gson.fromJson(jsonString, MainSchedule::class.java)
        for (scheduleModel in mainSchedule.mainSchedule){
            scheduleModel.schedule.sortBy { it.endTime }
        }
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
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
        TODO("Not yet implemented")
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
        canvas.drawColor(Color.BLACK)

        val currentTime = LocalTime.now()

        val primaryColor: Int = try {
            Color.parseColor(SharedPreferences.read("primaryColor", "#d5f7e4"))
        }catch (e : Exception){
            Log.d(TAG, "Invalid primary color code")
            Color.parseColor("#d5f7e4")
        }

        val secondaryColor: Int = try {
            Color.parseColor(SharedPreferences.read("secondaryColor", "#68c4af"))
        }catch (e : Exception) {
            Log.d(TAG, "Invalid secondary color code")
            Color.parseColor("#68c4af")
        }

        /**
         * displaying time in 24h format in the canvas
         */
        drawTimeIn24HourFormat(canvas, bounds, primaryColor)

        /**
         * displaying the day of week, date and the month in the canvas
         */
        drawDate(canvas, bounds, primaryColor)

        /**
         * display the heartbeat and the logo in the canvas
         */

        // Check for permission before accessing the sensor
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED) {
            /**
             * display number of steps and heartbeat in the canvas
             */
            drawNumberOfSteps(canvas, bounds, stepCount.toString(), primaryColor)
            displayHeartbeatAndLogo(canvas, bounds, heartRateValue.toString(), primaryColor)
        } else {
            Log.d(TAG, "Permission not granted")
        }

        if(SharedPreferences.read("schedule", 1) == 1) {
            for(scheduleModel in mainSchedule.mainSchedule){
                if (scheduleModel.days.contains(getCurrentDayShortForm())) {
                    for (i in scheduleModel.schedule) {
                        if (currentTime.isAfter(i.startTime) && currentTime.isBefore(i.endTime)) {
                            if(SharedPreferences.read("vibration", 1) == 1) {
                                if(Duration.between(currentTime,i.endTime).seconds.toInt() == i.vibrateBeforeEndSecs){
                                    if(nameMap[i.name] == 0) {
                                        nameMap[i.name] = null
                                        vibrate(i.vibrateBeforeEnd.toLongArray())
                                    }
                                }

                                if(Duration.between(i.startTime, currentTime).seconds.toInt() <= 1){
                                    if(nameMap[i.name] == null) {
                                        nameMap[i.name] = 0
                                        vibrate(i.vibrateOnStart.toLongArray())
                                    }
                                }
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
                                getDifferenceOfLocalTime(i.startTime, currentTime),
                                getDifferenceOfLocalTime(currentTime, i.endTime),
                                (getNumberOf15MinIntervalBetweenLocalTime(
                                    i.startTime,
                                    i.endTime
                                ) + 1).toInt(),
                                primaryColor,
                                secondaryColor
                            )
                            displayCurrentScheduleWithTime(canvas, bounds, i.name, time, primaryColor)

                            drawCurrentScheduleHabits(canvas, i.habits, secondaryColor)
                        }

                        val nextGreatest = findNextGreatest(currentTime)

                        if (nextGreatest != null) {
                            drawNextSchedule(
                                canvas,
                                nextGreatest.name,
                                "${convertLocalTimeTo24HourFormat(nextGreatest.startTime)} - ${
                                    convertLocalTimeTo24HourFormat(nextGreatest.endTime)
                                }",
                                primaryColor
                            )
                        }
                    }
                }
            }
        }

        /**
         * display the battery percentage in the canvas
         */
        drawBatteryPercentage(canvas, bounds, getWatchBatteryLevel(context), primaryColor)
    }

    private fun findNextGreatest(currentTime: LocalTime): InnerScheduleModel? {
        var nextGreaterValue: InnerScheduleModel? = null

        for(scheduleModel in mainSchedule.mainSchedule){
            for (element in scheduleModel.schedule) {
                if (element.startTime > currentTime) {
                    nextGreaterValue = element
                    break
                }
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

        Log.d(TAG, "getDifferenceOfLocalTime() duration: $duration")

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
            isAntiAlias = true
            color = textColor
            textAlign = alignment
            textSize = fontSize
        }
    }

    private fun displayCurrentScheduleWithTime(
        canvas: Canvas,
        bounds: Rect,
        scheduleName: String,
        scheduleTime: String,
        primaryColor: Int
    ) {
        val heartBeatPaint = getTextPaint(12f, Paint.Align.CENTER, primaryColor)
        val text1Y = bounds.top + 40f
        val text2Y = bounds.top + 55f

        canvas.drawText(scheduleName, bounds.exactCenterX(), text1Y, heartBeatPaint)
        canvas.drawText(scheduleTime, bounds.exactCenterX(), text2Y, heartBeatPaint)
    }

    private fun getWatchBatteryLevel(context: Context): Pair<Int, Boolean> {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return if (level != -1 && scale != -1) {
            // Calculate battery percentage
            Pair((level.toFloat() / scale.toFloat() * 100).toInt(), isCharging)
        } else {
            // Unable to retrieve battery level
            Pair(-1, isCharging)
        }
    }

    private fun drawNumberOfSteps(canvas: Canvas, bounds: Rect, s: String, primaryColor: Int) {
        val stepsPaint = getTextPaint(15f, Paint.Align.LEFT, primaryColor)
        val logoDrawable = getLogoDrawable(R.drawable.foot)
        val logoWidth = 20
        val logoHeight = 20

        val logoLeft = bounds.right.toFloat() - logoWidth - 60f
        val logoTop = bounds.centerY() - logoHeight / 2 - 10

        logoDrawable.setBounds(
            logoLeft.toInt(),
            logoTop,
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight)
        )

        logoDrawable.setTint(Color.WHITE)
        logoDrawable.draw(canvas)

        val additionalTextX = logoLeft + logoWidth + 4 // Adjust the horizontal position
        val additionalTextY = bounds.centerY() + stepsPaint.textSize / 2 - 13 // Center the additional text vertically

        // Draw the additional text
        canvas.drawText(s, additionalTextX, additionalTextY, stepsPaint)
    }

    private fun drawBatteryPercentage(
        canvas: Canvas,
        bounds: Rect,
        batteryPercentage: Pair<Int, Boolean>,
        primaryColor: Int
    ) {
        val batteryPaint = getTextPaint(15f, Paint.Align.LEFT, primaryColor)
        val text = "${batteryPercentage.first}%"
        val textX = 30f // Adjust the horizontal position
        val textY = centerY - 14f // Adjust the vertical position
        canvas.drawText(text, textX, textY, batteryPaint)

        // Draw an image on the left side of the text
        var batteryDrawable = 0

        if(batteryPercentage.second){
            batteryDrawable = R.drawable.battery_charging
        }else{
            when {
                batteryPercentage.first <= 5 -> {
                    batteryDrawable = R.drawable.battery_alert
                }
                batteryPercentage.first <= 10 -> {
                    batteryDrawable = R.drawable.battery_0
                }
                batteryPercentage.first <= 20 -> {
                    batteryDrawable = R.drawable.battery_1
                }
                batteryPercentage.first <= 30 -> {
                    batteryDrawable = R.drawable.battery_2
                }
                batteryPercentage.first < 50 -> {
                    batteryDrawable = R.drawable.battery_3
                }
                batteryPercentage.first == 50 -> {
                    batteryDrawable = R.drawable.battery_4
                }
                batteryPercentage.first <= 60 -> {
                    batteryDrawable = R.drawable.battery_5
                }
                batteryPercentage.first <= 70 -> {
                    batteryDrawable = R.drawable.battery_6
                }
                batteryPercentage.first <= 80 -> {
                    batteryDrawable = R.drawable.battery_6
                }
                batteryPercentage.first <= 90 -> {
                    batteryDrawable = R.drawable.battery_full
                }
                batteryPercentage.first <= 100 -> {
                    batteryDrawable = R.drawable.battery_full
                }
            }
        }

        val logoDrawable = getLogoDrawable(batteryDrawable) // Replace this with your method to get the logo drawable
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

        if(batteryPercentage.second) {
            logoDrawable.setTint(Color.GREEN)
        }else{
            if(batteryPercentage.first <= 15){
                logoDrawable.setTint(Color.RED)
            }else if(batteryPercentage.first <= 50) {
                logoDrawable.setTint(Color.YELLOW)
            }else if(batteryPercentage.first <= 100) {
                logoDrawable.setTint(Color.GREEN)
            }
        }

        logoDrawable.draw(canvas)
    }

    private fun drawCurrentScheduleHabits(
        canvas: Canvas,
        habits: ArrayList<String>,
        secondaryColor: Int
    ) {
        val currentSchedulePaint = getTextPaint(15f, Paint.Align.CENTER, secondaryColor)
        val textOffset = 20f // Adjust vertical spacing between texts

        when (habits.size) {
            1 -> {
                canvas.drawText(
                    habits[0],
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )
            }
            2 -> {
                canvas.drawText(
                    habits[0],
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )

                canvas.drawText(
                    habits[1],
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 3,
                    currentSchedulePaint
                )
            }
            3 -> {
                canvas.drawText(
                    "${habits[0]} | ${habits[1]}",
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )

                canvas.drawText(
                    habits[2],
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 3,
                    currentSchedulePaint
                )
            }
            4 -> {
                canvas.drawText(
                    "${habits[0]} | ${habits[1]}",
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 4,
                    currentSchedulePaint
                )

                canvas.drawText(
                    "${habits[2]} | ${habits[3]}",
                    (canvas.width / 2).toFloat(),
                    canvas.height - currentSchedulePaint.textSize - textOffset * 3,
                    currentSchedulePaint
                )
            }
        }
    }

    private fun drawNextSchedule(
        canvas: Canvas,
        nextScheduleName: String,
        nextScheduleTime: String,
        primaryColor: Int
    ) {
        val nextSchedulePaint = getTextPaint(12f, Paint.Align.CENTER, primaryColor)

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
    private fun displayHeartbeatAndLogo(canvas: Canvas, bounds: Rect, heartBeat: String, primaryColor: Int) {
        val heartBeatPaint = getTextPaint(15f, Paint.Align.LEFT, primaryColor)
        val logoDrawable = getLogoDrawable(R.drawable.heartbeat) // Replace this with your method to get the logo drawable
        val logoWidth = 20
        val logoHeight = 20

        val logoLeft = bounds.right.toFloat() - logoWidth - 50f // Adjust the horizontal position to the right
        val logoTop = bounds.centerY() - logoHeight / 2 - 40 // Center the image vertically

        logoDrawable.setTint(Color.RED)
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
        interval: Int,
        primaryColor: Int,
        secondaryColor: Int
    ) {
        val paint = Paint().apply {
            isAntiAlias = true
            color = secondaryColor // Customize the arc color
            style = Paint.Style.STROKE // Use STROKE to create an outline
            strokeWidth = 10f // Adjust stroke width as needed
            strokeCap = Paint.Cap.ROUND // Use ROUND for rounded ends
        }

        val totalPathPaint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(128, 128, 128, 128) // Adjust alpha for desired transparency
            style = Paint.Style.STROKE
            strokeWidth = 15f // Adjust stroke width as needed
            strokeCap = Paint.Cap.ROUND
        }

        val linePaint = Paint().apply {
            isAntiAlias = true
            color = Color.argb(128, 128, 128, 128) // Color of the radial lines
            style = Paint.Style.STROKE
            strokeWidth = 1f // Adjust line stroke width as needed
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

        val textPaint = getTextPaint(10f, Paint.Align.CENTER, primaryColor)
        canvas.drawText(timeLeft, textStartX, textStartY, textPaint)
        canvas.drawText(timePassed, textEndX, textEndY, textPaint)
    }

    private fun getLogoDrawable(itemName: Int): Drawable {
        return ContextCompat.getDrawable(context, itemName)!!
    }

    private fun drawDate(canvas: Canvas, bounds: Rect, primaryColor: Int) {
        val text = SimpleDateFormat("E d MMM", Locale.getDefault()).format(Date())
        val datePaint = getTextPaint(20f, Paint.Align.CENTER, primaryColor)
        val centerYDate = bounds.exactCenterY() + 30
        canvas.drawText(text, bounds.exactCenterX(), centerYDate, datePaint)
    }

    /**
     * This function will draw the time in 24h format in the canvas
     */
    private fun drawTimeIn24HourFormat(canvas: Canvas, bounds: Rect, primaryColor: Int) {
        val text = convertMillisTo24HourFormat(System.currentTimeMillis())
        val timePaint = getTextPaint(80f, Paint.Align.CENTER, primaryColor)
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

    companion object {
        private const val TAG = "DigitalWatchCanvasRenderer"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        Log.d(TAG, "onSensorChanged()")
        if (event!!.sensor.type == Sensor.TYPE_HEART_RATE) {
            heartRateValue = event.values[0].toInt()
        } else if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            stepCount = event.values[0].toInt()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "onAccuracyChanged()")
    }
}
