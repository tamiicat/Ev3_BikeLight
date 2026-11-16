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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import com.example.bikelight.ui.devices.SensorProximidad
import com.example.bikelight.ui.devices.UdpClientManager
import com.example.bikelight.ui.home.HistorialFragment
import com.example.bikelight.ui.home.HomeDashboardFragment
import com.example.bikelight.ui.home.ModosProximidad
import com.example.bikelight.ui.home.PerfilFragment
import com.example.bikelight.ui.home.WifiConnectionViewModel
import com.example.bikelight.ui.home.WifiStatus
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // ViewModel compartido con WifiConfigFragment y demás fragments
    private val wifiViewModel: WifiConnectionViewModel by viewModels()

    var isEsp32Connected: Boolean = false
        private set

    var connectedSsid: String = ""
        private set

    // Control de muestreo para evitar saturar SQLite si el sensor emite varias veces por segundo
    private var ultimoGuardadoMs: Long = 0L

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

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // Inicializar el rango del sensor con el modo guardado localmente
        val modoInicial = ModosProximidad.modoGuardado(applicationContext)
        SensorProximidad.establecerRango(ModosProximidad.rangoCm(modoInicial))

        // Iniciar captura Offline-First desde el flujo real de SensorProximidad
        iniciarPersistenciaOfflineSensor()

        // Sincronizar lecturas pendientes acumuladas previamente
        programarSincronizacionFirebase()

        // Intento inicial automático con el SSID configurado
        conectarARedEsp32(targetSsid, targetPassword)

        setupTopNavigation()
        setupBottomNavigation()

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    private fun iniciarPersistenciaOfflineSensor() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SensorProximidad.lecturas().collect { lectura ->
                    // Ignorar si no hay señal (null) o está fuera de alcance (cm < 0)
                    if (lectura == null || lectura.cm < 0) return@collect

                    val modoActivo = ModosProximidad.modoGuardado(applicationContext)
                    val umbralCm = if (lectura.umbralCm > 0) {
                        lectura.umbralCm
                    } else {
                        ModosProximidad.rangoCm(modoActivo)
                    }
                    val limitePrecaucionCm = umbralCm * 2

                    val esCritico = lectura.cm < umbralCm
                    val ahora = System.currentTimeMillis()

                    // Guardar cada 1.5 s en condiciones normales o cada 500 ms si hay alerta crítica
                    val intervaloMinimo = if (esCritico) 500L else 1500L
                    if (ahora - ultimoGuardadoMs < intervaloMinimo) return@collect
                    ultimoGuardadoMs = ahora

                    // Mismo criterio de clasificación que SimuladorProximidadFragment y firmware Arduino
                    val nivelRiesgo = when {
                        esCritico -> "CRITICO"
                        lectura.cm < limitePrecaucionCm -> "MEDIO"
                        else -> "BAJO"
                    }

                    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                    val registro = SensorReading(
                        uid = uid,
                        distanceCm = lectura.cm.toFloat(),
                        riskLevel = nivelRiesgo,
                        proximityMode = modoActivo,
                        timestamp = ahora,
                        isSynced = false
                    )

                    launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(applicationContext)
                        val rowId = db.sensorReadingDao().insertReading(registro)
                        Log.d(
                            "BikeLightOffline",
                            "Guardado Room ID=$rowId | Modo=$modoActivo | Dist=${lectura.cm}cm (Umbral=${umbralCm}cm) | Riesgo=$nivelRiesgo"
                        )
                        programarSincronizacionFirebase()
                    }
                }
            }
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

    /**
     * ÚNICO punto de conexión al Access Point del ESP32.
     */
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

                    // Solo los sockets UDP usan esta red; Firebase sigue usando datos móviles
                    UdpClientManager.esp32Network = network
                    isEsp32Connected = true
                    connectedSsid = targetSsid

                    // Enviar de inmediato el rango del modo activo al Arduino (Nodo A) al conectarse
                    val modoActual = ModosProximidad.modoGuardado(applicationContext)
                    SensorProximidad.fijarRango(ModosProximidad.rangoCm(modoActual))

                    runOnUiThread {
                        wifiViewModel.updateStatus(WifiStatus.Connected(targetSsid))
                        Toast.makeText(this@MainActivity, "Conectado a $targetSsid", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    UdpClientManager.esp32Network = null
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
                    UdpClientManager.esp32Network = null
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
                connectivityManager.requestNetwork(networkRequest, networkCallback!!, 30_000)
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
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        UdpClientManager.esp32Network = null
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
                    cargarFragment(HomeDashboardFragment(), "BIKELIGHT")
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
    }
}