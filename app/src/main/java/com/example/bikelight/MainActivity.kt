package com.example.bikelight

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.bikelight.databinding.ActivityMainBinding
import com.example.bikelight.ui.auth.AuthActivity
import com.example.bikelight.ui.home.HomeDashboardFragment
import com.example.bikelight.ui.home.PerfilFragment
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Configuración para el Nodo B (ESP32)
    private val esp32Ip = "192.168.4.1" // IP por defecto en modo Access Point del ESP32
    private val puertoUdp = 4210
    var udpClient: UdpClient? = null
        private set

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Aplicar tema claro/oscuro
        val prefs = getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val temaGuardado = prefs.getInt("app_theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(temaGuardado)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Inicializar cliente UDP
        udpClient = UdpClient(esp32Ip, puertoUdp)

        // Inicializar administrador de conectividad
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Iniciar monitoreo y reconexión automática
        iniciarMonitoreoRedWiFi()

        setupBottomNavigation()

        // Cargar tema por defecto
        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    /**
     * Monitorea la red del ESP32. Si se pierde la señal y vuelve a estar disponible,
     * la App se reconecta automáticamente sin intervención del usuario.
     */
    private fun iniciarMonitoreoRedWiFi() {
        val wifiSpecifier = WifiNetworkSpecifier.Builder()
            .setSsid("ProyectoSensor")
            .setWpa2Passphrase("75648955!")
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // Importante: Red local sin internet
            .setNetworkSpecifier(wifiSpecifier)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                // Enlaza el proceso de la App a la red WiFi del ESP32 para ignorar datos móviles
                connectivityManager.bindProcessToNetwork(network)

                // Reinicia el socket UDP
                udpClient?.resetSocket()

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Conectado automáticamente a ProyectoSensor", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                // Liberar el enlace cuando se sale del rango de la red
                connectivityManager.bindProcessToNetwork(null)
                udpClient?.resetSocket()

                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Conexión perdida con el ESP32. Reintentando...", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Dispositivo ESP32 no encontrado en el rango", Toast.LENGTH_SHORT).show()
                }
            }
        }

        connectivityManager.requestNetwork(networkRequest, networkCallback!!)
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_perfil -> {
                    cargarFragment(PerfilFragment(), "Mi Perfil")
                    true
                }
                R.id.nav_home -> {
                    cargarFragment(HomeDashboardFragment(), "BikeLight")
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
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Desenlazar red y cerrar socket de forma limpia
        networkCallback?.let {
            try {
                connectivityManager.bindProcessToNetwork(null)
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        udpClient?.close()
    }
}