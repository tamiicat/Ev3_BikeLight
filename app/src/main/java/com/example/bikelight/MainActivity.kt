package com.example.bikelight

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.example.bikelight.databinding.ActivityMainBinding
import com.example.bikelight.ui.auth.AuthActivity
import com.example.bikelight.ui.home.HomeDashboardFragment
import com.example.bikelight.ui.home.PerfilFragment
import com.example.bikelight.ui.home.WifiConnectionViewModel
import com.example.bikelight.ui.home.WifiStatus
import com.google.firebase.auth.FirebaseAuth

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

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("BikeLightPrefs", MODE_PRIVATE)
        val temaGuardado = prefs.getInt("app_theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(temaGuardado)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        udpClient = UdpClient(esp32Ip, puertoUdp)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // Intento inicial automático con el nuevo SSID
        conectarARedEsp32("RED BIKELIGHT", "75648955!")

        setupBottomNavigation()

        if (savedInstanceState == null) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
    }

    /**
     * Inicia o reinicia limpiamente la conexión hacia el Access Point del ESP32
     * y notifica en tiempo real al WifiConnectionViewModel.
     */
    fun conectarARedEsp32(ssid: String = "RED BIKELIGHT", password: String = "75648955!") {
        liberarRedActual()

        val cleanSsid = ssid.trim()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiViewModel.updateStatus(WifiStatus.Connecting)

            val specifierBuilder = WifiNetworkSpecifier.Builder()
                .setSsid(cleanSsid)

            if (password.isNotEmpty()) {
                specifierBuilder.setWpa2Passphrase(password)
            }

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    connectivityManager.bindProcessToNetwork(network)
                    udpClient?.resetSocket()
                    isEsp32Connected = true
                    connectedSsid = cleanSsid

                    runOnUiThread {
                        // Notifica a EstadoWifiFragment y WifiConfigFragment que ya está conectado
                        wifiViewModel.updateStatus(WifiStatus.Connected(cleanSsid))
                        Toast.makeText(this@MainActivity, "Conectado a $cleanSsid", Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@MainActivity, "Conexión perdida con $cleanSsid", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    isEsp32Connected = false
                    connectedSsid = ""

                    runOnUiThread {
                        wifiViewModel.updateStatus(WifiStatus.Error("Conexión rechazada o red '$cleanSsid' no encontrada"))
                        Toast.makeText(
                            this@MainActivity,
                            "Conexión rechazada o red '$cleanSsid' no encontrada",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            try {
                connectivityManager.requestNetwork(networkRequest, networkCallback!!)
            } catch (e: Exception) {
                e.printStackTrace()
                wifiViewModel.updateStatus(WifiStatus.Error("Error al solicitar red Wi-Fi"))
                Toast.makeText(this, "Error al solicitar red Wi-Fi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(
                this,
                "Conéctate manualmente a '$cleanSsid' desde los Ajustes de Wi-Fi del teléfono",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun liberarRedActual() {
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
            .commit()
    }

    override fun onDestroy() {
        super.onDestroy()
        liberarRedActual()
        udpClient?.close()
    }
}