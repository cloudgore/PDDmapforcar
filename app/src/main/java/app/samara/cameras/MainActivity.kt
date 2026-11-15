
package app.samara.cameras

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.FeatureCollection

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

import android.graphics.Path
import org.maplibre.geojson.Point

class MainActivity : Activity() {

    companion object {
        private const val TAG = "PDDmap"

        private const val MAP_STYLE =
            "https://tiles.openfreemap.org/styles/liberty"

        private const val LOCATION_PERMISSION_REQUEST = 1001

        private const val MAX_GPS_ACCURACY = 150f
        private const val WARNING_GPS_ACCURACY = 35f

        private const val WARNING_DISTANCE = 300f
        private const val SEARCH_DISTANCE = 500f
        private const val MIN_WARNING_SPEED = 2.8f

        private const val GPS_TIMEOUT_MS = 10000L
        private const val WARNING_INTERVAL_MS = 10000L
    }

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private var locationManager: LocationManager? = null

    private lateinit var rootLayout: FrameLayout

    private lateinit var speedText: TextView
    private lateinit var gpsText: TextView
  
    private lateinit var cameraText: TextView
    private lateinit var recenterButton: TextView

    private var tts: TextToSpeech? = null
    private var voiceReady = false

    private var followingCar = true
    private var followZoom: Double? = null
    private var firstGpsFix = true
    private var activityResumed = false

    private var lastGpsTime = 0L
    private var latestLocation: Location? = null
    private var lastWarningTime = -WARNING_INTERVAL_MS

    private val handler = Handler(Looper.getMainLooper())

    private val cameraList = mutableListOf<CameraInfo>()
    private val warnedCameras = mutableSetOf<String>()

    // =========================================
    // МОДЕЛЬ КАМЕРЫ
    // =========================================

    private data class CameraInfo(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val types: MutableSet<String> = mutableSetOf()
    ) {
        fun addTypes(value: String) {
            value.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { types.add(it) }
        }
    }

    // =========================================
    // ЗАПУСК ПРИЛОЖЕНИЯ
    // =========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        MapLibre.getInstance(this)

        mapView = MapView(this)

        rootLayout = FrameLayout(this)

        rootLayout.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)

        Toast.makeText(
            this,
            "PDDmap v5: обзор дороги впереди",
            Toast.LENGTH_LONG
        ).show()

        createDrivingInterface()
        initVoice()
        loadCamerasForWarnings()

        mapView.onCreate(savedInstanceState)

        checkMapInternet()

        mapView.getMapAsync { map ->

            mapLibreMap = map

            // Отключаем автоследование только при жестах пользователя.
            // Автоматическое перемещение карты этого не делает.
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    followingCar = false
                    updateRecenterButton()
                }
            }

            Log.i(TAG, "MapLibre создан")
            Log.i(TAG, "Загружаем стиль карты")

            map.setStyle(MAP_STYLE) { style ->

                Log.i(TAG, "Стиль карты загружен")

                addCameras(style)
                addCarMarker(style)
                latestLocation?.let { location ->
                    updateCarMarker(location)
                    if (followingCar) followLocation(location)
                }
                if (firstGpsFix) {
                    val initialPosition = CameraPosition.Builder()
                        .target(LatLng(53.1959, 50.1002))
                        .zoom(14.0)
                        .build()

                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            initialPosition
                        )
                    )
                }

                if (activityResumed) {
                    startGps()
                }
            }
        }

        handler.post(gpsWatchdog)
    }

    // =========================================
    // ИНТЕРФЕЙС
    // =========================================

    private fun dp(value: Float): Int {
        return (
            value * resources.displayMetrics.density
        ).roundToInt()
    }

    private fun dp(value: Int): Int = dp(value.toFloat())

    private fun createLabel(
        text: String,
        textSize: Float,
        backgroundColor: Int
    ): TextView {

        return TextView(this).apply {
            this.text = text
            this.textSize = textSize
            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                dp(8),
                dp(4),
                dp(8),
                dp(4)
            )

            background = GradientDrawable().apply {
                setColor(backgroundColor)
                cornerRadius = dp(12).toFloat()
            }
        }
    }

    private fun createDrivingInterface() {

        // Спидометр

        speedText = createLabel(
            "— км/ч",
            20f,
            0xDD152535.toInt()
        )

        val speedParams = FrameLayout.LayoutParams(
            dp(110),
            dp(56),
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(12), dp(12), 0)
        }

        rootLayout.addView(speedText, speedParams)

        // GPS

        gpsText = createLabel(
            "Поиск GPS",
            12f,
            0xDD444444.toInt()
        )

        val gpsParams = FrameLayout.LayoutParams(
            dp(110),
            dp(30),
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(74), dp(12), 0)
        }

        rootLayout.addView(gpsText, gpsParams)

        // Кнопка возвращения к автомобилю (справа над предупреждениями).
        recenterButton = TextView(this).apply {
            text = "⌖"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Вернуться к текущему местоположению"
            elevation = dp(6).toFloat()
            setOnClickListener {
                val location = latestLocation
                if (location == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ожидаем координаты GPS",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    followingCar = true
                    followZoom = null // При возврате сразу подстраиваем масштаб под скорость
                    updateRecenterButton()
                    followLocation(location)
                }
            }
        }
        rootLayout.addView(
            recenterButton,
            FrameLayout.LayoutParams(
                dp(52), dp(52), Gravity.END or Gravity.BOTTOM
            ).apply {
                setMargins(0, 0, dp(12), dp(65))
            }
        )
        updateRecenterButton()

        // Панель предупреждений

        cameraText = createLabel(
            "Ожидание GPS",
            14f,
            0xDD152535.toInt()
        )

        val cameraParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(42),
            Gravity.BOTTOM
        ).apply {
            setMargins(dp(8), 0, dp(8), dp(8))
        }

        rootLayout.addView(cameraText, cameraParams)
    }

    private fun updateRecenterButton() {
        if (!::recenterButton.isInitialized) return
        recenterButton.background = GradientDrawable().apply {
            setColor(if (followingCar) 0xD91F78D1.toInt() else 0xE6323742.toInt())
            cornerRadius = dp(26).toFloat()
            setStroke(dp(2), Color.WHITE)
        }
        recenterButton.alpha = if (followingCar) 0.85f else 1f
    }

    // =========================================
    // ПРОВЕРКА ИНТЕРНЕТА
    // =========================================

    private fun checkMapInternet() {

        Thread {

            var connection: HttpURLConnection? = null

            try {
                val url = URL("$MAP_STYLE/style.json")

                connection =
                    url.openConnection() as HttpURLConnection

                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                val response = connection.responseCode

                Log.i(TAG, "OpenFreeMap HTTP: $response")

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Сервер карт: HTTP $response",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {

                Log.e(TAG, "Ошибка сети OpenFreeMap", e)

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Ошибка сети: ${e.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    // =========================================
    // ЧТЕНИЕ БАЗЫ КАМЕР
    // =========================================

    private fun readAsset(filename: String): String {
        return assets.open(filename).bufferedReader(
            Charsets.UTF_8
        ).use { it.readText() }
    }

    private fun loadCamerasForWarnings() {

        cameraList.clear()

        try {
            val json = readAsset("cameras.geojson")
            val root = JSONObject(json)
            val features = root.getJSONArray("features")

            val grouped = linkedMapOf<String, CameraInfo>()

            for (i in 0 until features.length()) {

                val feature = features.getJSONObject(i)
                val properties =
                    feature.getJSONObject("properties")

                val coordinates = feature
                    .getJSONObject("geometry")
                    .getJSONArray("coordinates")

                val longitude = coordinates.getDouble(0)
                val latitude = coordinates.getDouble(1)

                val id = properties.optString("id", "")

                val types = properties.optString(
                    "camera_types",
                    properties.optString("camera_type", "unknown")
                )

                val key = coordinateKey(
                    latitude,
                    longitude
                )

                val camera = grouped.getOrPut(key) {
                    CameraInfo(
                        id = id,
                        latitude = latitude,
                        longitude = longitude
                    )
                }

                camera.addTypes(types)
            }

            cameraList.addAll(grouped.values)

            Log.i(
                TAG,
                "Загружено уникальных точек: ${cameraList.size}"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка чтения камер", e)
        }
    }

    private fun coordinateKey(
        latitude: Double,
        longitude: Double
    ): String {
        return "${(latitude * 1_000_000).roundToInt()}:" +
                "${(longitude * 1_000_000).roundToInt()}"
    }

    private fun cameraKey(camera: CameraInfo): String {
        return coordinateKey(
            camera.latitude,
            camera.longitude
        )
    }

    // =========================================
    // ЗНАЧКИ КАМЕР НА КАРТЕ
    // =========================================

 
private fun makeCameraIcon(
    label: String,
    color: Int
): Bitmap {

    val bitmap = Bitmap.createBitmap(
        80, 80, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Фоновый круг
    paint.color = Color.WHITE
    canvas.drawCircle(40f, 40f, 38f, paint)

    paint.color = color
    canvas.drawCircle(40f, 40f, 33f, paint)

    paint.color = Color.WHITE
    paint.strokeWidth = 5f
    paint.strokeCap = Paint.Cap.ROUND

    when (label) {

        // Спидометр
        "СК" -> {
            paint.style = Paint.Style.STROKE
            canvas.drawArc(
                21f, 23f, 59f, 61f,
                155f, 230f, false, paint
            )

            canvas.drawLine(
                40f, 43f, 52f, 31f, paint
            )

            paint.style = Paint.Style.FILL
            canvas.drawCircle(40f, 43f, 4f, paint)
        }

        // Светофор
        "СВ" -> {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(30, 35, 40)

            canvas.drawRoundRect(
                29f, 11f, 51f, 69f,
                7f, 7f, paint
            )

            paint.color = Color.RED
            canvas.drawCircle(40f, 23f, 7f, paint)

            paint.color = Color.YELLOW
            canvas.drawCircle(40f, 40f, 7f, paint)

            paint.color = Color.GREEN
            canvas.drawCircle(40f, 57f, 7f, paint)
        }

        // Встречное направление
        "↔" -> {
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = 5f

            canvas.drawLine(
                28f, 58f, 28f, 22f, paint
            )
            canvas.drawLine(
                52f, 22f, 52f, 58f, paint
            )

            paint.style = Paint.Style.FILL

            val up = Path().apply {
                moveTo(28f, 13f)
                lineTo(17f, 30f)
                lineTo(39f, 30f)
                close()
            }

            val down = Path().apply {
                moveTo(52f, 67f)
                lineTo(41f, 50f)
                lineTo(63f, 50f)
                close()
            }

            canvas.drawPath(up, paint)
            canvas.drawPath(down, paint)
        }

        // Обычная камера
        else -> {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE

            canvas.drawRoundRect(
                16f, 27f, 64f, 55f,
                6f, 6f, paint
            )

            paint.color = color
            canvas.drawCircle(40f, 41f, 10f, paint)

            paint.color = Color.WHITE
            canvas.drawCircle(40f, 41f, 6f, paint)

            canvas.drawRect(
                24f, 22f, 42f, 29f, paint
            )
        }
    }

    return bitmap
}


    private fun addCameras(style: Style) {

        try {
            val json = readAsset("cameras.geojson")

            // Normalize camera_type for map icons, even when the asset
            // contains only camera_types (a comma-separated list).
            val document = JSONObject(json)
            val features = document.getJSONArray("features")
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val properties = feature.optJSONObject("properties") ?: continue
                val rawTypes = listOf(
                    properties.optString("camera_type", ""),
                    properties.optString("camera_types", "")
                ).joinToString(",").lowercase(Locale.ROOT)

                val type = when {
                    "traffic_light" in rawTypes -> "traffic_light"
                    "speed" in rawTypes -> "speed"
                    "oncoming" in rawTypes -> "oncoming"
                    else -> "other"
                }
                properties.put("camera_type", type)
            }
            val source = GeoJsonSource(
                "cameras-source",
                FeatureCollection.fromJson(document.toString())
            )

            style.addSource(source)

            style.addImage(
                "camera-speed",
                makeCameraIcon(
                    "СК",
                    Color.rgb(25, 118, 210)
                )
            )

            style.addImage(
                "camera-light",
                makeCameraIcon(
                    "СВ",
                    Color.rgb(55, 71, 79)
                )
            )

            style.addImage(
                "camera-oncoming",
                makeCameraIcon(
                    "↔",
                    Color.rgb(255, 152, 0)
                )
            )

            style.addImage(
                "camera-other",
                makeCameraIcon(
                    "КАМ",
                    Color.rgb(97, 97, 97)
                )
            )

            val types = listOf(
                "speed",
                "traffic_light",
                "oncoming",
                "other"
            )

            val images = listOf(
                "camera-speed",
                "camera-light",
                "camera-oncoming",
                "camera-other"
            )

            types.forEachIndexed { index, type ->

                val layer = SymbolLayer(
                    "camera-layer-$type",
                    "cameras-source"
                )

                if (type == "other") {

                    layer.setFilter(
                        Expression.all(
                            Expression.neq(
                                Expression.get("camera_type"),
                                Expression.literal("speed")
                            ),
                            Expression.neq(
                                Expression.get("camera_type"),
                                Expression.literal("traffic_light")
                            ),
                            Expression.neq(
                                Expression.get("camera_type"),
                                Expression.literal("oncoming")
                            )
                        )
                    )

                } else {

                    layer.setFilter(
                        Expression.eq(
                            Expression.get("camera_type"),
                            Expression.literal(type)
                        )
                    )
                }

                layer.setProperties(
                    PropertyFactory.iconImage(images[index]),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(0.80f)
                )

                style.addLayer(layer)
            }

            Log.i(TAG, "Значки камер добавлены")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка добавления камер", e)
        }
    }

    // =========================================
    // GPS
    // =========================================

    private val locationListener = object : LocationListener {

        override fun onLocationChanged(location: Location) {

            if (!activityResumed) return

            if (!location.hasAccuracy() ||
                location.accuracy > MAX_GPS_ACCURACY
            ) {
                return
            }

            val ageNanos =
                SystemClock.elapsedRealtimeNanos() -
                    location.elapsedRealtimeNanos

            if (ageNanos < 0 ||
                ageNanos > GPS_TIMEOUT_MS * 1_000_000L
            ) {
                return
            }

            lastGpsTime = SystemClock.elapsedRealtime()
            latestLocation = location

            updateDrivingInterface(location)
            updateCarMarker(location)
            if (followingCar) {
                followLocation(location)
            }

            checkNearbyCameras(location)
        }
    }

    private fun startGps() {
        if (!activityResumed) return

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }

        val manager = locationManager ?: (
            getSystemService(LOCATION_SERVICE) as? LocationManager
        )?.also { locationManager = it }

        if (manager == null) {
            gpsText.text = "Геолокация недоступна"
            return
        }

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            try { manager.isProviderEnabled(provider) }
            catch (_: Exception) { false }
        }

        if (providers.isEmpty()) {
            gpsText.text = "Включите геолокацию"
            return
        }

        try {
            manager.removeUpdates(locationListener)

            // Show a recent cached position immediately, if available.
            // Cached positions are NOT used for camera voice warnings.
            val cached = providers.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) }
                catch (_: Exception) { null }
            }.filter { position ->
                val age = SystemClock.elapsedRealtimeNanos() -
                    position.elapsedRealtimeNanos
                age >= 0L && age <= 120_000_000_000L &&
                    position.hasAccuracy() && position.accuracy <= 150f
            }.minByOrNull { it.accuracy }

            if (cached != null && latestLocation == null) {
                latestLocation = cached
                updateCarMarker(cached)
                if (followingCar && mapLibreMap?.style != null) followLocation(cached)
                gpsText.text = "Уточнение GPS ±${cached.accuracy.roundToInt()} м"
            }

            providers.forEach { provider ->
                manager.requestLocationUpdates(
                    provider, 1000L, 0f,
                    locationListener, Looper.getMainLooper()
                )
            }
            gpsText.text = "Поиск точного GPS"
            Log.i(TAG, "Геолокация запущена: $providers")
        } catch (e: SecurityException) {
            Log.e(TAG, "Нет разрешения на геолокацию", e)
            gpsText.text = "Нет доступа к GPS"
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска геолокации", e)
            gpsText.text = "Ошибка геолокации"
        }
    }

    private fun stopGps() {
        locationManager?.removeUpdates(locationListener)
    }

    // =========================================
    // ДВИЖЕНИЕ КАРТЫ
    // =========================================

    private fun zoomForSpeed(location: Location): Double {
        // Location.speed хранится в м/с, переводим в км/ч.
        // При недоступной скорости используем городской масштаб.
        val speedKmh = if (location.hasSpeed()) {
            (location.speed * 3.6f).coerceAtLeast(0f)
        } else {
            0f
        }

        // На малой скорости видим детали дороги, на трассе — больше пути впереди.
        return when {
            speedKmh < 30f -> 18.3
            speedKmh < 60f -> 18.0
            speedKmh < 90f -> 17.5
            else -> 17.0
        }
    }

    private fun followLocation(location: Location) {

        val map = mapLibreMap ?: return
        val desiredZoom = zoomForSpeed(location)

        // Сглаживаем изменение масштаба, чтобы он не прыгал на границах скоростей.
        // После нажатия кнопки возврата сразу используем подходящий масштаб.
        val previousZoom = followZoom
        val nextZoom = if (previousZoom == null) {
            desiredZoom
        } else {
            previousZoom + (desiredZoom - previousZoom) * 0.35
        }
        followZoom = nextZoom

        // Сдвигаем GPS-точку ниже середины экрана (примерно 67% высоты).
        // Верхний отступ камеры оставляет больше места для дороги впереди.
        // Значение выражено в физических пикселях, поэтому зависит от размера экрана.
        val screenHeight = if (mapView.height > 0) {
            mapView.height
        } else {
            resources.displayMetrics.heightPixels
        }
        val topPadding = screenHeight * 0.34

        val builder = CameraPosition.Builder()
            .target(
                LatLng(
                    location.latitude,
                    location.longitude
                )
            )
            .zoom(nextZoom)
            .tilt(45.0)
            .padding(0.0, topPadding, 0.0, 0.0)

        if (location.hasBearing() &&
            location.hasSpeed() &&
            location.speed >= 2f
        ) {

            builder.bearing(
                location.bearing.toDouble()
            )

        } else {

            builder.bearing(
                map.cameraPosition.bearing
            )
        }

        val position = builder.build()

        if (firstGpsFix) {

            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    position
                )
            )

            firstGpsFix = false

        } else {

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    position
                ),
                400
            )
        }
    }

    // =========================================
    // СПИДОМЕТР
    // =========================================

    private fun updateDrivingInterface(location: Location) {

        speedText.text = if (location.hasSpeed()) {

            val speed = max(
                0f,
                location.speed * 3.6f
            ).roundToInt()

            "$speed км/ч"

        } else {
            "— км/ч"
        }

        gpsText.text =
            "GPS ±${location.accuracy.roundToInt()} м"

    }

    // =========================================
    // КОНТРОЛЬ ПОТЕРИ GPS
    // =========================================

    private val gpsWatchdog = object : Runnable {

        override fun run() {

            if (activityResumed) {

                val elapsed =
                    SystemClock.elapsedRealtime() -
                        lastGpsTime

                if (lastGpsTime == 0L ||
                    elapsed > GPS_TIMEOUT_MS
                ) {

                    speedText.text = "— км/ч"
                    gpsText.text = "Поиск GPS"

                    cameraText.text =
                        "Нет актуальных GPS-данных"

                }
            }

            handler.postDelayed(this, 2000L)
        }
    }

    // =========================================
    // РАССТОЯНИЕ ДО КАМЕРЫ
    // =========================================

    private fun distanceToCamera(
        location: Location,
        camera: CameraInfo
    ): Float {

        val result = FloatArray(1)

        Location.distanceBetween(
            location.latitude,
            location.longitude,
            camera.latitude,
            camera.longitude,
            result
        )

        return result[0]
    }

    private fun isCameraAhead(
        location: Location,
        camera: CameraInfo
    ): Boolean {

        if (!location.hasBearing()) return false

        val destination = Location("camera").apply {
            latitude = camera.latitude
            longitude = camera.longitude
        }

        val cameraBearing = location.bearingTo(destination)
        val drivingBearing = location.bearing

        val difference = abs(
            ((cameraBearing - drivingBearing + 540f) %
                360f) - 180f
        )

        return difference <= 35f
    }

    // =========================================
    // ТИП КАМЕРЫ
    // =========================================

    private fun getCameraDescription(
        camera: CameraInfo
    ): String {

        return when {
            "traffic_light" in camera.types ->
                "контроля светофора"

            "speed" in camera.types ->
                "контроля скорости"

            "oncoming" in camera.types ->
                "контроля встречной полосы"

            "bus_lane" in camera.types ->
                "контроля выделенной полосы"

            "seatbelt" in camera.types ->
                "контроля ремня безопасности"

            else ->
                "фиксации нарушений"
        }
    }

    // =========================================
    // ПРЕДУПРЕЖДЕНИЯ О КАМЕРАХ
    // =========================================

    private fun checkNearbyCameras(location: Location) {

        if (!location.hasAccuracy() ||
            location.accuracy > WARNING_GPS_ACCURACY ||
            !location.hasSpeed() ||
            location.speed < MIN_WARNING_SPEED ||
            !location.hasBearing()
        ) {

            cameraText.text =
                "Ожидание точного GPS и курса"

            return
        }

        var nearest: CameraInfo? = null
        var nearestDistance = Float.MAX_VALUE

        for (camera in cameraList) {

            val distance = distanceToCamera(
                location,
                camera
            )

            if (distance > 1000f) {
                warnedCameras.remove(cameraKey(camera))
            }

            if (distance < 15f ||
                distance > SEARCH_DISTANCE
            ) {
                continue
            }

            if (!isCameraAhead(location, camera)) {
                continue
            }

            if (distance < nearestDistance) {
                nearest = camera
                nearestDistance = distance
            }
        }

        val camera = nearest

        if (camera == null) {
            cameraText.text =
                "Камер впереди не обнаружено"
            return
        }

        val description =
            getCameraDescription(camera)

        cameraText.text =
            "Камера $description · " +
                "${nearestDistance.roundToInt()} м"

        val key = cameraKey(camera)
        val now = SystemClock.elapsedRealtime()

        if (nearestDistance <= WARNING_DISTANCE &&
            key !in warnedCameras &&
            now - lastWarningTime >= WARNING_INTERVAL_MS
        ) {

            val roundedDistance = max(
                50,
                (nearestDistance / 50f).roundToInt() * 50
            )

            val message =
                "Впереди камера $description, " +
                    "примерно через $roundedDistance метров"

            if (speak(message)) {

                warnedCameras.add(key)
                lastWarningTime = now

                Log.i(TAG, message)
            }
        }
    }

    // =========================================
    // РУССКАЯ ОЗВУЧКА
    // =========================================

    private fun initVoice() {

        tts = TextToSpeech(this) { status ->

            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "Ошибка инициализации TTS")
            } else {

                val engine = tts

                if (engine != null) {

                    val result = engine.setLanguage(
                        Locale("ru", "RU")
                    )

                    voiceReady =
                        result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                    if (voiceReady) {

                        engine.setSpeechRate(1.05f)

                        Log.i(TAG, "Русский голос готов")

                    } else {

                        Log.w(
                            TAG,
                            "Русский голос недоступен"
                        )
                    }
                }
            }
        }
    }

    private fun speak(message: String): Boolean {

        val engine = tts ?: return false

        if (!voiceReady) return false

        val result = engine.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "camera-warning"
        )

        return result == TextToSpeech.SUCCESS
    }


private fun makeCarArrow(): Bitmap {
    val bitmap = Bitmap.createBitmap(
        72, 72, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Белая обводка
    paint.color = Color.WHITE
    canvas.drawCircle(36f, 36f, 29f, paint)

    // Синий фон
    paint.color = Color.rgb(25, 118, 210)
    canvas.drawCircle(36f, 36f, 25f, paint)

    // Стрелка направления
    val path = Path().apply {
        moveTo(36f, 14f)
        lineTo(19f, 53f)
        lineTo(36f, 45f)
        lineTo(53f, 53f)
        close()
    }

    paint.color = Color.WHITE
    paint.style = Paint.Style.FILL
    canvas.drawPath(path, paint)

    return bitmap
}

private fun addCarMarker(style: Style) {
    val source = GeoJsonSource(
        "car-source",
        FeatureCollection.fromFeatures(
            emptyList<org.maplibre.geojson.Feature>()
        )
    )

    style.addSource(source)
    style.addImage("car-arrow-icon", makeCarArrow())

    val layer = SymbolLayer("car-layer", "car-source")

    layer.setProperties(
        PropertyFactory.iconImage("car-arrow-icon"),
        PropertyFactory.iconSize(0.80f),
        PropertyFactory.iconRotationAlignment("viewport"),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true)
    )

    style.addLayer(layer)
}

private fun updateCarMarker(location: Location) {
    val source = mapLibreMap
        ?.style
        ?.getSourceAs<GeoJsonSource>("car-source")

    source?.setGeoJson(
        Point.fromLngLat(
            location.longitude,
            location.latitude
        )
    )
}



    // =========================================
    // РАЗРЕШЕНИЕ НА GPS
    // =========================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == LOCATION_PERMISSION_REQUEST) {

            if (grantResults.isNotEmpty() &&
                grantResults[0] ==
                    PackageManager.PERMISSION_GRANTED
            ) {

                startGps()

            } else {

                gpsText.text = "Нет доступа к GPS"

                Toast.makeText(
                    this,
                    "Разрешите доступ к геолокации",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // =========================================
    // ЖИЗНЕННЫЙ ЦИКЛ
    // =========================================

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()

        activityResumed = true

        mapView.onResume()

        speedText.text = "— км/ч"
        gpsText.text = "Поиск GPS"
        cameraText.text = "Поиск камер впереди"

        lastGpsTime = 0L

        startGps()
    }

    override fun onPause() {

        activityResumed = false

        stopGps()
        mapView.onPause()

        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {

        stopGps()

        handler.removeCallbacks(gpsWatchdog)

        tts?.stop()
        tts?.shutdown()
        tts = null

        mapView.onDestroy()

        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(
        outState: Bundle
    ) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
