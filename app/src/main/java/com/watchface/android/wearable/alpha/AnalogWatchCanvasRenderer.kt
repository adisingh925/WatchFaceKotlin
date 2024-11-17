package com.watchface.android.wearable.alpha

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
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
    val locationClient = LocationServices.getFusedLocationProviderClient(context)

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

    @SuppressLint("MissingPermission")
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
        canvas.drawColor(Color.WHITE)

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
        drawTimeIn12HourFormat(canvas, bounds, primaryColor)

        val priority = Priority.PRIORITY_HIGH_ACCURACY
        locationClient.getCurrentLocation(
            priority,
            CancellationTokenSource().token,
        ).addOnCompleteListener {
            if (it.isSuccessful) {
                try {
                    val fetchedLocation = it.result

                    Log.d(
                        "Location",
                        "Fetched location: ${fetchedLocation.latitude}, ${fetchedLocation.longitude}"
                    )

                    var codes = convertDegreesArrayToLocat(
                        arrayOf(fetchedLocation.latitude, fetchedLocation.longitude),
                        5
                    )

                    SharedPreferences.write("location", codes.joinToString(","))
                } catch (e: Exception) {
                    Log.d("Location", "Failed to send location data." + e.message)
                }
            }
        }

        val codes = SharedPreferences.read("location", "")

        Log.d("Location", "codes: ${codes?.split(",")}")

        /** comment this code when the actual icons are added*/
        drawIconsCentered(
            canvas,
            bounds,
            codes?.split(",") ?: emptyList(),
            15f,
            context
        )

        /**
         * display the battery percentage in the canvas
         */
        drawBatteryPercentage(canvas, bounds, getWatchBatteryLevel(context), primaryColor)
    }

    fun convertDegreesArrayToLocat(coords: Array<Double>, locatDepth: Int = 5): List<String> {
        val locatBaseChars = "0123456789ABCDEFGHJKMNPQRTVWXY"
        val degreesArray = arrayOf(coords[0], coords[1])
        val coordsInt = arrayOf(0, 0)
        val coordsLoc = arrayOf("", "")

        // LONGITUDE NORMALISE
        degreesArray[0] += 180.0
        while (degreesArray[0] > 360) degreesArray[0] -= 360.toDouble()
        while (degreesArray[0] < 0) degreesArray[0] += 360.toDouble()
        degreesArray[0] += 180.0

        // LATITUDE NORMALISE
        degreesArray[1] -= 90.0
        degreesArray[1] = -degreesArray[1]
        while (degreesArray[1] > 180) degreesArray[1] -= 180.toDouble()
        while (degreesArray[1] < 0) degreesArray[1] += 180.toDouble()

        // Convert Degs to Loc for long [0] and lat [1]
        for (i in 0..1) {
            // Normalize to 0-540 range and round down to integer
            coordsInt[i] = floor(degreesArray[i] * 45000).toInt()

            // Convert integer to locBase
            while (coordsInt[i] > 0) {
                val remainder = coordsInt[i] % 30
                coordsLoc[i] = locatBaseChars[remainder] + coordsLoc[i]
                coordsInt[i] = floor((coordsInt[i] / 30).toDouble()).toInt()
            }
            while (coordsLoc[i].length < 5) {
                coordsLoc[i] = "0" + coordsLoc[i]
            }
        }

        val locArray = mutableListOf<String>()
        for (i in 0 until minOf(coordsLoc[0].length, locatDepth)) {
            locArray.add("icon_${coordsLoc[1][i]}${coordsLoc[0][i]}".toLowerCase(Locale.ROOT))
        }

        return locArray
    }

    private fun drawIconsCentered(
        canvas: Canvas,
        bounds: Rect,
        iconNames: List<String>, // A list of drawable resource IDs (VectorDrawable)
        iconSpacing: Float, // Space between icons
        context: Context
    ) {
        // Create a list to store the Bitmaps
        val bitmaps = iconNames.mapNotNull { iconName ->
            val drawableId =
                context.resources.getIdentifier(iconName, "drawable", context.packageName)
            if (drawableId != 0) drawableToBitmap(
                context,
                drawableId
            ) else null // Only add valid IDs
        }

        // Calculate the total width of the icons, including spacing between them
        val totalIconWidth = bitmaps.sumOf { it.width.toInt() } + (bitmaps.size - 1) * iconSpacing

        // Calculate the starting X position to center the icons horizontally
        val startX = bounds.exactCenterX() - totalIconWidth / 2

        // Set the Y position to center the icons vertically in the available bounds
        val startY = bounds.exactCenterY()

        var currentX = startX

        // Draw each icon (now a Bitmap) on the canvas
        for (bitmap in bitmaps) {
            // Draw the icon at the current X position
            canvas.drawBitmap(bitmap, currentX, centerY, null)

            // Move the X position for the next icon, adding spacing
            currentX += bitmap.width + iconSpacing
        }
    }

    // Converts a VectorDrawable (or any drawable) to Bitmap
    private fun drawableToBitmap(context: Context, drawableId: Int): Bitmap {
        // Get the drawable resource
        val drawable: Drawable? = ContextCompat.getDrawable(context, drawableId)

        // Check if drawable is null
        if (drawable == null) {
            throw IllegalArgumentException("Drawable resource not found")
        }

        // Create a mutable bitmap with the size of the drawable
        val bitmap = Bitmap.createBitmap(
            58,
            58,
            Bitmap.Config.ARGB_8888
        )

        // Create a canvas to draw the drawable onto the bitmap
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    private fun getTextPaint(fontSize: Float, alignment: Paint.Align, textColor: Int): Paint {
        return Paint().apply {
            isAntiAlias = true
            color = textColor
            textAlign = alignment
            textSize = fontSize
        }
    }

    private fun getWatchBatteryLevel(context: Context): Pair<Int, Boolean> {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        return if (level != -1 && scale != -1) {
            // Calculate battery percentage
            Pair((level.toFloat() / scale.toFloat() * 100).toInt(), isCharging)
        } else {
            // Unable to retrieve battery level
            Pair(-1, isCharging)
        }
    }

    private fun drawBatteryPercentage(
        canvas: Canvas,
        bounds: Rect,
        batteryPercentage: Pair<Int, Boolean>,
        primaryColor: Int
    ) {
        val batteryPaint = getTextPaint(25f, Paint.Align.LEFT, primaryColor)
        val text = "${batteryPercentage.first}%"
        val textX = centerX + 10 // Adjust the horizontal position
        val textY = centerY + 185f // Adjust the vertical position
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

        val logoWidth = 40
        val logoHeight = 40
        val logoLeft = centerX - 30
        val logoTop = bounds.exactCenterY() + 155

        val logoDrawable = getLogoDrawable(
            getLogoDrawable(batteryDrawable),
            Constants.BATTERY_HEART_FOOT_COLOR,
            logoLeft.toInt(),
            logoTop.toInt(),
            (logoLeft + logoWidth).toInt(),
            (logoTop + logoHeight).toInt()
        )

        if (batteryPercentage.second) {
            logoDrawable.setTint(Constants.BATTERY_HEART_FOOT_COLOR)
        } else {
            if (batteryPercentage.first <= 15) {
                logoDrawable.setTint(Color.RED)
            } else if (batteryPercentage.first <= 50) {
                logoDrawable.setTint(ColorUtils.blendARGB(Color.YELLOW, Color.BLACK, 0.3f))
            } else if (batteryPercentage.first <= 100) {
                logoDrawable.setTint(Color.GREEN)
            }
        }

        logoDrawable.draw(canvas)
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

    private fun getLogoDrawable(itemName: Int): Drawable {
        return ContextCompat.getDrawable(context, itemName)!!
    }

    /**
     * This function will draw the time in 24h format in the canvas
     */
    private fun drawTimeIn12HourFormat(
        canvas: Canvas,
        bounds: Rect,
        primaryColor: Int
    ) {
        // Get the current time
        val currentTime = LocalTime.now()

        // Format the time to get the full "hh:mm a" string
        val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.getDefault())
        val text = currentTime.format(formatter)

        // Split the time into two parts: time (e.g., "02:30") and AM/PM (e.g., "PM")
        val timeText = text.substring(0, text.length - 2)
        val amPmText = text.substring(text.length - 2)

        // Load Londrina Solid font
        val londrinaTypeface = ResourcesCompat.getFont(context, R.font.londrina)

        // Create the paint objects for time and AM/PM with Londrina Solid font
        val timePaint = getTextPaint(90f, Paint.Align.LEFT, primaryColor).apply {
            typeface = londrinaTypeface
        }
        val amPmPaint = getTextPaint(30f, Paint.Align.LEFT, primaryColor).apply {
            typeface = londrinaTypeface
        }

        // Measure the width of the time and AM/PM texts
        val timeWidth = timePaint.measureText(timeText)
        val amPmWidth = amPmPaint.measureText(amPmText)
        val spacing = 10 // Adjustable spacing between time and AM/PM

        // Calculate the starting X position to center both texts
        val totalWidth = timeWidth + amPmWidth + spacing
        val startX = bounds.exactCenterX() - totalWidth / 2

        val startY = bounds.exactCenterY() - 40

        // Adjust vertical alignment for AM/PM text
        val fontMetrics = timePaint.fontMetrics
        val verticalOffset = (fontMetrics.descent - fontMetrics.ascent) / 4

        // Draw the time text
        canvas.drawText(timeText, startX, startY, timePaint)

        // Draw the AM/PM text right after the time text
        canvas.drawText(amPmText, startX + timeWidth - 10, startY, amPmPaint)
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
