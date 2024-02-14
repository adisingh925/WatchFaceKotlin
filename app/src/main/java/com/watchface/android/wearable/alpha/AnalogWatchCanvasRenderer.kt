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
import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.watchface.android.wearable.alpha.model.InnerScheduleModel
import com.watchface.android.wearable.alpha.model.MainSchedule
import com.watchface.android.wearable.alpha.sharedpreferences.SharedPreferences
import com.watchface.android.wearable.alpha.utils.AlarmHelper
import com.watchface.android.wearable.alpha.utils.Constants
import com.watchface.android.wearable.alpha.utils.JsonParser
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
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
), SensorEventListener {
    class AnalogSharedAssets : SharedAssets {
        override fun onDestroy() {
            Log.d(TAG, "AnalogSharedAssets.onDestroy()")
        }
    }

    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val heartRateSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    private var mainSchedule: MainSchedule = JsonParser(context).readAndParseJsonFile()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var heartRateValue = 0

    init {
        sensorManager.registerListener(this, heartRateSensor, Constants.HEART_SENSOR_SPEED)
    }

    override suspend fun createSharedAssets(): AnalogSharedAssets {
        return AnalogSharedAssets()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        sensorManager.unregisterListener(this, heartRateSensor)
        AlarmHelper(context).cancelAllAlarms()
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

    private fun drawComplications(canvas: Canvas, zonedDateTime: ZonedDateTime) {
        for ((_, complication) in complicationSlotsManager.complicationSlots) {
            if (complication.enabled) {
                complication.render(canvas, zonedDateTime, renderParameters)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
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

        /**
         * displaying step count complication
         */
        drawComplications(canvas, zonedDateTime)

        // Specify the desired timezone, for example "America/New_York"
        val desiredTimeZone = ZoneId.of(Constants.TIMEZONE)

        // Get the current time in the desired timezone
        val currentTime = zonedDateTime.withZoneSameInstant(desiredTimeZone).toLocalTime()
        val currentDate = zonedDateTime.withZoneSameInstant(desiredTimeZone)

        // I am setting the primary color in here, if the color is not valid then I will set the default color
        val primaryColor = Color.parseColor(Constants.DEFAULT_PRIMARY_COLOR)

        // I am setting the secondary color in here, if the color is not valid then I will set the default color
        val secondaryColor = Color.parseColor(Constants.DEFAULT_SECONDARY_COLOR)

        /**
         * displaying time in 24h format in the canvas
         */
        drawTimeIn24HourFormat(canvas, bounds, primaryColor, currentTime)

        /**
         * displaying the day of week, date and the month in the canvas
         */
        drawDate(canvas, bounds, primaryColor, currentDate)

        /**
         * check if the permission is granted or not
         * if the permission is granted then display the number of steps and heartbeat in the canvas
         */
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            displayHeartbeatAndLogo(canvas, bounds, heartRateValue.toString(), primaryColor)
        }

        if (SharedPreferences.read("schedule", 1) == 1) {
            for (scheduleModel in mainSchedule.mainSchedule) {
                if (scheduleModel.days.contains(getCurrentDayShortForm(currentDate))) {
                    for (i in scheduleModel.schedule) {
                        val startTime = i.startTime
                        val endTime = i.endTime

                        val spansOverMidnight = endTime.isBefore(startTime)

                        if ((currentTime.isAfter(startTime) && currentTime.isBefore(endTime)) || (spansOverMidnight && (currentTime.isAfter(startTime) || currentTime.isBefore(endTime)))) {

                            val time = "${
                                getDifferenceOfLocalTime(
                                    startTime,
                                    endTime
                                )
                            } | ${convertLocalTimeTo24HourFormat(startTime)} - ${
                                convertLocalTimeTo24HourFormat(endTime)
                            }"
                            drawProgressArc(
                                canvas,
                                bounds,
                                (getLocalTimeDifferenceInMinutes(
                                    startTime,
                                    currentTime
                                ) * (60F / getLocalTimeDifferenceInMinutes(
                                    startTime,
                                    endTime
                                ).toFloat())),
                                getDifferenceOfLocalTime(startTime, currentTime),
                                getDifferenceOfLocalTime(currentTime, endTime),
                                (getNumberOf15MinIntervalBetweenLocalTime(
                                    startTime,
                                    endTime
                                ) + 1).toInt(),
                                primaryColor,
                                secondaryColor
                            )
                            displayCurrentScheduleWithTime(
                                canvas,
                                bounds,
                                i.name,
                                time,
                                primaryColor
                            )

                            drawCurrentScheduleHabits(canvas, i.habits, secondaryColor)
                        }

                        val nextGreatest = findNextGreatest(currentTime, currentDate)

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

    @RequiresApi(Build.VERSION_CODES.S)
    private fun findNextGreatest(currentTime: LocalTime, currentDateTime: ZonedDateTime): InnerScheduleModel? {
        var nextGreaterValue: InnerScheduleModel? = null
        var temp = 0

        for (scheduleModel in mainSchedule.mainSchedule) {
            if(scheduleModel.days.contains(getCurrentDayShortForm(currentDateTime))){
                for (element in scheduleModel.schedule) {
                    if (element.startTime > currentTime) {
                        temp++
                        nextGreaterValue = element
                        if (SharedPreferences.read("vibration", 1) == 1) {
                            if(SharedPreferences.read("startScheduledHour", 0) != element.startTime.hour || SharedPreferences.read("startScheduledMinute", 0) != element.startTime.minute){
                                AlarmHelper(context).setExactLocalTimeAlarm(element.startTime, 0, element.vibrateOnStart)
                                SharedPreferences.write("startScheduledHour", element.startTime.hour)
                                SharedPreferences.write("startScheduledMinute", element.startTime.minute)
                            }

                            if(SharedPreferences.read("endScheduledHour", 0) != element.endTime.hour || SharedPreferences.read("endScheduledMinute", 0) != element.endTime.minute){
                                AlarmHelper(context).setExactLocalTimeAlarm(element.endTime, element.vibrateBeforeEndSecs, element.vibrateBeforeEnd)
                                SharedPreferences.write("endScheduledHour", element.endTime.hour)
                                SharedPreferences.write("endScheduledMinute", element.endTime.minute)
                            }
                        }

                        break
                    }
                }
            }
        }

        if(temp == 0){
            for (scheduleModel in mainSchedule.mainSchedule) {
                if(scheduleModel.days.contains(getNextDay())){
                    for (element in scheduleModel.schedule) {
                        if (SharedPreferences.read("vibration", 1) == 1) {
                            if(SharedPreferences.read("startScheduledHour", 0) != element.startTime.hour || SharedPreferences.read("startScheduledMinute", 0) != element.startTime.minute){
                                SharedPreferences.write("day",1)
                                AlarmHelper(context).setExactLocalTimeAlarm(element.startTime, 0, element.vibrateOnStart)
                                SharedPreferences.write("startScheduledHour", element.startTime.hour)
                                SharedPreferences.write("startScheduledMinute", element.startTime.minute)
                            }

                            if(SharedPreferences.read("endScheduledHour", 0) != element.endTime.hour || SharedPreferences.read("endScheduledMinute", 0) != element.endTime.minute){
                                SharedPreferences.write("day",1)
                                AlarmHelper(context).setExactLocalTimeAlarm(element.endTime, element.vibrateBeforeEndSecs, element.vibrateBeforeEnd)
                                SharedPreferences.write("endScheduledHour", element.endTime.hour)
                                SharedPreferences.write("endScheduledMinute", element.endTime.minute)
                            }
                        }
                        nextGreaterValue = element
                        break
                    }
                }
            }
        }

        return nextGreaterValue
    }

    private fun getNextDay(): String {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val dateFormat = SimpleDateFormat("EEE", Locale.getDefault())
        return dateFormat.format(calendar.time)
    }

    private fun getNumberOf15MinIntervalBetweenLocalTime(time1: LocalTime, time2: LocalTime): Long {
        if(time1.isAfter(time2)){
            return ((1440 - Duration.between(LocalTime.MIDNIGHT, time1).toMinutes()) + Duration.between(LocalTime.MIDNIGHT, time2).toMinutes()) / 15
        }

        return Duration.between(time1, time2).toMinutes() / 15
    }

    private fun getLocalTimeDifferenceInMinutes(time1: LocalTime, time2: LocalTime): Long {
        if(time1.isAfter(time2)) {
            return (86400 - Duration.between(LocalTime.MIDNIGHT, time1).seconds) + Duration.between(
                LocalTime.MIDNIGHT,
                time2
            ).seconds
        }

        return Duration.between(time1, time2).seconds
    }

    private fun getDifferenceOfLocalTime(time1: LocalTime, time2: LocalTime): String {
        if(time1.isAfter(time2)){
            val duration = Duration.ofSeconds((86400 - Duration.between(LocalTime.MIDNIGHT, time1).seconds) + Duration.between(LocalTime.MIDNIGHT, time2).seconds)

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
        }else if(time1.isBefore(time2)) {
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

        return ""
    }

    private fun convertLocalTimeTo24HourFormat(time: LocalTime): String {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        return time.format(formatter)
    }

    private fun getCurrentDayShortForm(currentDateTime: ZonedDateTime): String {
        val formatter = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
        return currentDateTime.format(formatter)
    }

    private fun getTextPaint(fontSize: Float, alignment: Paint.Align, textColor: Int): Paint {
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

        val logoWidth = 20
        val logoHeight = 20
        val logoLeft = bounds.right.toFloat() - logoWidth - 60f
        val logoTop = bounds.centerY() - logoHeight / 2 - 15


        val logoDrawable = getLogoDrawable(
            getLogoDrawable(R.drawable.foot),
            Constants.BATTERY_HEART_FOOT_COLOR,
            logoLeft.toInt(),
            logoTop,
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight)
        )

        logoDrawable.draw(canvas)

        val additionalTextX = logoLeft + logoWidth + 4 // Adjust the horizontal position
        val additionalTextY = bounds.centerY() + stepsPaint.textSize / 2 - 18 // Center the additional text vertically

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
        val textY = centerY - 19f // Adjust the vertical position
        canvas.drawText(text, textX, textY, batteryPaint)

        val batteryDrawable: Int

        if (batteryPercentage.second) {
            batteryDrawable = R.drawable.battery_charging
        } else {
            batteryDrawable = when {
                batteryPercentage.first <= 5 -> R.drawable.battery_alert
                batteryPercentage.first <= 10 -> R.drawable.battery_0
                batteryPercentage.first <= 20 -> R.drawable.battery_1
                batteryPercentage.first <= 30 -> R.drawable.battery_2
                batteryPercentage.first < 50 -> R.drawable.battery_3
                batteryPercentage.first <= 60 -> R.drawable.battery_5
                batteryPercentage.first <= 70 -> R.drawable.battery_6
                batteryPercentage.first <= 80 -> R.drawable.battery_6
                batteryPercentage.first <= 90 -> R.drawable.battery_full
                batteryPercentage.first <= 100 -> R.drawable.battery_full
                else -> R.drawable.battery_full // Default case, handle unexpected values
            }
        }

        val logoWidth = 20
        val logoHeight = 20
        val logoLeft = 10f // Adjust the horizontal position
        val logoTop = bounds.exactCenterY() - 35f

        val logoDrawable = getLogoDrawable(getLogoDrawable(batteryDrawable), Constants.BATTERY_HEART_FOOT_COLOR, logoLeft.toInt(), logoTop.toInt(), (logoLeft + logoWidth).toInt(), (logoTop + logoHeight).toInt())

        if (batteryPercentage.second) {
            logoDrawable.setTint(Constants.BATTERY_HEART_FOOT_COLOR)
        } else {
            if (batteryPercentage.first <= 15) {
                logoDrawable.setTint(Constants.BATTERY_HEART_FOOT_COLOR)
            } else if (batteryPercentage.first <= 50) {
                logoDrawable.setTint(Constants.BATTERY_HEART_FOOT_COLOR)
            } else if (batteryPercentage.first <= 100) {
                logoDrawable.setTint(Constants.BATTERY_HEART_FOOT_COLOR)
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

        val startY = canvas.height - currentSchedulePaint.textSize - textOffset * 4

        for (i in habits.indices step 2) {
            val habit = habits[i]

            val text = when (i) {
                habits.size - 1 -> habit
                else -> "$habit | ${habits[i + 1]}"
            }

            canvas.drawText(
                text,
                (canvas.width / 2).toFloat(),
                startY + textOffset * (i / 2),
                currentSchedulePaint
            )
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
    private fun displayHeartbeatAndLogo(
        canvas: Canvas,
        bounds: Rect,
        heartBeat: String,
        primaryColor: Int
    ) {
        val heartBeatPaint = getTextPaint(15f, Paint.Align.LEFT, primaryColor)
        val logoWidth = 20
        val logoHeight = 20

        val logoLeft = bounds.right.toFloat() - logoWidth - 60f // Adjust the horizontal position to the right
        val logoTop = bounds.centerY() - logoHeight / 2 - 45 // Center the image vertically


        val logoDrawable = getLogoDrawable(
            getLogoDrawable(R.drawable.heartbeat),
            Constants.BATTERY_HEART_FOOT_COLOR,
            logoLeft.toInt(),
            logoTop,
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight)
        )

        logoDrawable.draw(canvas)

        val additionalTextX = logoLeft + logoWidth + 10f // Adjust the horizontal position
        val additionalTextY = bounds.centerY() + heartBeatPaint.textSize / 2 - 47 // Center the additional text vertically

        // Draw the additional text
        canvas.drawText(heartBeat, additionalTextX, additionalTextY, heartBeatPaint)
    }

    private fun getLogoDrawable(
        drawable: Drawable,
        color: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Drawable {
        drawable.setTint(color)
        drawable.setBounds(left, top, right, bottom)
        return drawable
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

    private fun drawDate(canvas: Canvas, bounds: Rect, primaryColor: Int, currentTime: ZonedDateTime) {
        val formatter = DateTimeFormatter.ofPattern("E d MMM")
        val text = currentTime.format(formatter)
        val datePaint = getTextPaint(20f, Paint.Align.CENTER, primaryColor)
        val centerYDate = bounds.exactCenterY() + 30
        canvas.drawText(text, bounds.exactCenterX(), centerYDate, datePaint)
    }

    /**
     * This function will draw the time in 24h format in the canvas
     */
    private fun drawTimeIn24HourFormat(canvas: Canvas, bounds: Rect, primaryColor: Int, currentTime: LocalTime) {
        val formatter = DateTimeFormatter.ofPattern("HH:mm")
        val text = currentTime.format(formatter)
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

    /**
     * This function will be called when the sensor value is changed
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event!!.sensor.type == Sensor.TYPE_HEART_RATE) {
            heartRateValue = event.values[0].toInt()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "onAccuracyChanged()")
    }
}
