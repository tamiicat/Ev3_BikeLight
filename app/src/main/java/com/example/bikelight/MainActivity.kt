package com.example.bikelight

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.SensorReading
import com.example.bikelight.data.sync.SyncReadingsWorker
import com.example.bikelight.databinding.ActivityMainBinding
import com.example.bikelight.ui.auth.AuthActivity
import com.example.bikelight.ui.home.HistorialFragment
import com.example.bikelight.ui.home.HomeDashboardFragment
import com.example.bikelight.ui.home.PerfilFragment
import com.example.bikelight.ui.home.WifiConnectionViewModel
import com.example.bikelight.ui.home.WifiStatus
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ViewModel compartido con WifiConfigFragment y EstadoWifiFragment
    private val wifiViewModel: WifiConnectionViewModel by viewModels()

    // Configuración para el Nodo B (ESP32)
    private val esp32Ip = "192.168.4.1"
    private val puertoUdp = 4210
    var udpClient: UdpClient? = null
        private set

    var isEsp32Connected: Boolean = false
        private set

    var connectedSsid: String = ""
        private set

    var modoProximidadActual: String = "Ciudad"

    // Variables de control para reconexión automática
    private var targetSsid: String = "RED BIKELIGHT"
    private var targetPassword: String = "75648955!"
    private var autoReconnectEnabled: Boolean = true

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (autoReconnectEnabled && !isEsp32Connected) {
                conectarARedEsp32(targetSsid, targetPassword, esReintento = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("BikeLightPrefs", MODE_PRIVATE)
        val temaGuardado = prefs.getInt("app_theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(temaGuardado)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        udpClient = UdpClient(esp32Ip, puertoUdp)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // Iniciar recepción continua de paquetes reales del ESP32 en toda la app
        iniciarRecepcionGlobalUdp()

        // Intento inicial automático con el SSID configurado
        conectarARedEsp32(targetSsid, targetPassword)

        setupTopNavigation()
        setupBottomNavigation()

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    private fun iniciarRecepcionGlobalUdp() {
        val udp = udpClient ?: return
        udp.startListening()
        Log.d("BikeLightUDP", "Escucha UDP iniciada en puerto $puertoUdp")

        udp.onMessageReceived = { rawPayload ->
            Log.d("BikeLightUDP", "Paquete crudo recibido: '$rawPayload'")
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            val lecturaParseada = parsearPaqueteMicrocontrolador(rawPayload, uid)

            if (lecturaParseada == null) {
                Log.w("BikeLightUDP", "Paquete descartado por formato inválido: '$rawPayload'")
            } else {
                Log.d("BikeLightUDP", "Paquete válido guardado en Room: $lecturaParseada")
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(applicationContext)
                    val rowId = db.sensorReadingDao().insertReading(lecturaParseada)
                    Log.d("BikeLightUDP", "Insertado en Room con ID=$rowId. Encolando sincronización...")
                    programarSincronizacionFirebase()
                }
            }
        }
    }

    private fun parsearPaqueteMicrocontrolador(payload: String, uid: String): SensorReading? {
        return try {
            val ahora = System.currentTimeMillis()
            val textoLimpio = payload.trim()
            if (textoLimpio.startsWith("{")) {
                val json = JSONObject(textoLimpio)
                val dist = json.optDouble("distancia", json.optDouble("distance", -1.0)).toFloat()
                if (dist < 0f) return null
                val riesgo = json.optString("riesgo", calcularNivelRiesgo(dist))
                SensorReading(
                    uid = uid,
                    distanceCm = dist,
                    riskLevel = riesgo,
                    proximityMode = modoProximidadActual,
                    timestamp = ahora,
                    isSynced = false
                )
            } else {
                val partes = textoLimpio.split(",")
                val dist = partes[0].trim().toFloatOrNull() ?: return null
                val riesgo = if (partes.size > 1) partes[1].trim() else calcularNivelRiesgo(dist)
                SensorReading(
                    uid = uid,
                    distanceCm = dist,
                    riskLevel = riesgo,
                    proximityMode = modoProximidadActual,
                    timestamp = ahora,
                    isSynced = false
                )
            }
        } catch (e: Exception) {
            Log.e("BikeLightUDP", "Error al parsear paquete '$payload': ${e.message}")
            null
        }
    }

    private fun calcularNivelRiesgo(distanciaCm: Float): String {
        return when {
            distanciaCm <= 100f -> "CRITICO"
            distanciaCm <= 200f -> "MEDIO"
            else -> "BAJO"
        }
    }

    fun programarSincronizacionFirebase(forzarReemplazo: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWorkRequest = OneTimeWorkRequestBuilder<SyncReadingsWorker>()
            .setConstraints(constraints)
            .build()

        val policy = if (forzarReemplazo) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "SyncSensorReadingsOfflineWork",
            policy,
            syncWorkRequest
        )
    }

    private fun setupTopNavigation() {
        binding.btnTopHistorial.setOnClickListener {
            cargarFragment(HistorialFragment(), "Historial de Paquetes")
        }
    }

    fun conectarARedEsp32(
        ssid: String = "RED BIKELIGHT",
        password: String = "75648955!",
        esReintento: Boolean = false
    ) {
        reconnectHandler.removeCallbacks(reconnectRunnable)

        targetSsid = ssid.trim()
        targetPassword = password
        autoReconnectEnabled = true

        liberarRedActual(detenerReintentos = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiViewModel.updateStatus(WifiStatus.Connecting)

            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(targetSsid)

            if (targetPassword.isNotEmpty()) {
                specifierBuilder.setWpa2Passphrase(targetPassword)
            }

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    reconnectHandler.removeCallbacks(reconnectRunnable)

                    connectivityManager.bindProcessToNetwork(network)
                    udpClient?.resetSocket()
                    isEsp32Connected = true
                    connectedSsid = targetSsid

                    runOnUiThread {
                        wifiViewModel.updateStatus(WifiStatus.Connected(targetSsid))
                        Toast.makeText(this@MainActivity, "Conectado a $targetSsid", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    connectivityManager.bindProcessToNetwork(null)
                    udpClient?.resetSocket()
                    isEsp32Connected = false
                    connectedSsid = ""

                    runOnUiThread {
                        wifiViewModel.updateStatus(WifiStatus.Disconnected)
                        Toast.makeText(
                            this@MainActivity,
                            "Conexión perdida con $targetSsid. Reconectando...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    programarReintento()
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    isEsp32Connected = false
                    connectedSsid = ""

                    runOnUiThread {
                        if (esReintento) {
                            wifiViewModel.updateStatus(WifiStatus.Disconnected)
                        } else {
                            wifiViewModel.updateStatus(
                                WifiStatus.Error("Conexión rechazada o red '$targetSsid' no encontrada")
                            )
                            Toast.makeText(
                                this@MainActivity,
                                "Conexión rechazada o red '$targetSsid' no encontrada. Reintentando...",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    programarReintento()
                }
            }

            try {
                connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            } catch (e: Exception) {
                e.printStackTrace()
                wifiViewModel.updateStatus(WifiStatus.Error("Error al solicitar red Wi-Fi"))
                Toast.makeText(this, "Error al solicitar red Wi-Fi: ${e.message}", Toast.LENGTH_SHORT).show()
                programarReintento()
            }
        } else {
            Toast.makeText(
                this,
                "Conéctate manualmente a '$targetSsid' desde los Ajustes de Wi-Fi del teléfono",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun programarReintento() {
        if (autoReconnectEnabled && !isEsp32Connected) {
            reconnectHandler.removeCallbacks(reconnectRunnable)
            reconnectHandler.postDelayed(reconnectRunnable, 5000L)
        }
    }

    fun liberarRedActual(detenerReintentos: Boolean = true) {
        if (detenerReintentos) {
            autoReconnectEnabled = false
            reconnectHandler.removeCallbacks(reconnectRunnable)
        }
        networkCallback?.let {
            try {
                connectivityManager.bindProcessToNetwork(null)
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        isEsp32Connected = false
        connectedSsid = ""
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_perfil -> {
                    cargarFragment(PerfilFragment(), "Mi Perfil")
                    true
                }
                R.id.nav_home -> {
                    cargarFragment(HomeDashboardFragment(), "RED BIKELIGHT")
                    true
                }
                R.id.nav_logout -> {
                    FirebaseAuth.getInstance().signOut()
                    val intent = Intent(this, AuthActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    fun cargarFragment(fragment: Fragment, title: String) {
        binding.tvHeaderTitle.text = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarRedActual(detenerReintentos = true)
        udpClient?.onMessageReceived = null
        udpClient?.close()
    }
}