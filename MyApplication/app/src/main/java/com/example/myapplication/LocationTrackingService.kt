package com.example.myapplication

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.google.android.gms.location.*
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocationTrackingService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val sharedPreferences = getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
                val cards = loadCards(sharedPreferences, Gson())
                val shops = loadShopsFromGeoJson(this@LocationTrackingService)

                var minDistance = Double.MAX_VALUE
                var nearestShop: Shop? = null

                for (shopLoc in shops) {
                    val distance = calculateDistance(location.latitude, location.longitude, shopLoc.lat, shopLoc.lon)
                    if (distance < minDistance) {
                        minDistance = distance
                        nearestShop = shopLoc.shop
                    }
                }

                // Радиус 2км для тестов
                if (nearestShop != null && minDistance <= 2.0) {
                    val card = cards.find { it.shop == nearestShop }
                    if (card != null) {
                        // ПИШЕМ ВСЕГДА (обновляем last_update_time для реактивности)
                        serviceScope.launch {
                            val manager = GlanceAppWidgetManager(applicationContext)
                            val widget = ShoppingWidget()
                            val glanceIds = manager.getGlanceIds(widget.javaClass)

                            glanceIds.forEach { glanceId ->
                                updateAppWidgetState(applicationContext, glanceId) { prefs ->
                                    prefs[stringPreferencesKey("nearest_shop_enum")] = nearestShop.name
                                    prefs[stringPreferencesKey("nearest_shop_id")] = card.id
                                    prefs[longPreferencesKey("last_update_time")] = System.currentTimeMillis()
                                }
                                widget.update(applicationContext, glanceId)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "location_tracking"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "GPS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Shopping Tracker Active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, notification)
        }
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            }
        } catch (e: Exception) { Log.e("GPS", "${e.message}") }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    override fun onBind(intent: Intent?) = null
}