package com.example.bikelight.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.bikelight.databinding.FragmentWifiConfigBinding

class WifiConfigFragment : Fragment() {
    private var _binding: FragmentWifiConfigBinding? = null
    private val binding get() = _binding!!

    // viewmodel compartido para reportar estado wifi
    private val wifiViewModel: WifiConnectionViewModel by activityViewModels()
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiManager: WifiManager? = null
    // receptor para escuchar el escaneo de redes wifi
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                updateWifiScanList()
            }
        }
    }
    // solicitar permisos de ubicación necesarios para escarear redes disponibles
    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startWifiScan()
        } else {
            Toast.makeText(requireContext(), "Se requiere permiso de ubicación para escanear redes Wi-Fi", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWifiConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        // registra el BroadcastREceiver
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        requireContext().registerReceiver(wifiScanReceiver, intentFilter)
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {}
    }

    private fun setupListeners() {
        binding.btnScanWifi.setOnClickListener {
            checkPermissionsAndScan()
        }

        binding.btnConnectDevice.setOnClickListener {
            val ssid = binding.etDeviceAp.text.toString().trim()
            val pass = binding.etDeviceApPass.text.toString().trim()

            if (ssid.isEmpty()) {
                binding.tilDeviceAp.error = "Ingresa el SSID del ESP32"
                return@setOnClickListener
            }
            binding.tilDeviceAp.error = null

            connectToEsp32Ap(ssid, pass)
        }
    }
    // comprueba permisos de ubicación antes de escanear
    private fun checkPermissionsAndScan() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startWifiScan()
        } else {
            requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @Suppress("DEPRECATION")
    private fun startWifiScan() {
        binding.scanProgressBar.visibility = View.VISIBLE
        val success = wifiManager?.startScan() ?: false
        if (!success) {
            binding.scanProgressBar.visibility = View.GONE
            updateWifiScanList()
        }
    }
    // actualiza la lista de redes wifi encontradas
    private fun updateWifiScanList() {
        _binding?.scanProgressBar?.visibility = View.GONE
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        @Suppress("DEPRECATION")
        val scanResults = wifiManager?.scanResults ?: emptyList()
        val ssids = scanResults
            .map { it.SSID }
            .filter { it.isNotBlank() }
            .distinct()

        if (ssids.isEmpty()) {
            Toast.makeText(requireContext(), "No se encontraron redes o la ubicación está apagada", Toast.LENGTH_SHORT).show()
            return
        }

        _binding?.let { b ->
            b.lvWifiList.visibility = View.VISIBLE
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ssids)
            b.lvWifiList.adapter = adapter
            // permite seleccionar una red y autocompletar el SSID
            b.lvWifiList.setOnItemClickListener { _, _, position, _ ->
                val selectedSsid = ssids[position]
                b.etDeviceAp.setText(selectedSsid)
                b.tilDeviceAp.error = null
            }
        }
    }
    // establece contexión con la red
    private fun connectToEsp32Ap(ssid: String, pass: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.progressBar.visibility = View.VISIBLE
            binding.btnConnectDevice.isEnabled = false

            // estado a viewmodel
            wifiViewModel.updateStatus(WifiStatus.Connecting)

            unregisterNetworkCallback()

            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (pass.isNotEmpty()) {
                specifierBuilder.setWpa2Passphrase(pass)
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    // enrutar el tráfico local hacia el ESP32
                    connectivityManager?.bindProcessToNetwork(network)
                    wifiViewModel.updateStatus(WifiStatus.Connected(ssid))

                    activity?.runOnUiThread {
                        _binding?.progressBar?.visibility = View.GONE
                        _binding?.btnConnectDevice?.isEnabled = true
                        Toast.makeText(requireContext(), "Conectado al punto de acceso $ssid", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    wifiViewModel.updateStatus(WifiStatus.Error("Conexión rechazada o no encontrada"))

                    activity?.runOnUiThread {
                        _binding?.progressBar?.visibility = View.GONE
                        _binding?.btnConnectDevice?.isEnabled = true
                        Toast.makeText(requireContext(), "Conexión rechazada o no encontrada", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    connectivityManager?.bindProcessToNetwork(null)
                    wifiViewModel.updateStatus(WifiStatus.Disconnected)

                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "Conexión con el ESP32 finalizada", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            networkCallback?.let { connectivityManager?.requestNetwork(request, it) }
        } else {
            Toast.makeText(
                requireContext(),
                "En Android 9 o inferior, conéctate a '$ssid' manualmente desde Ajustes de Wi-Fi",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // sin desvincular la red para que permanezca conectada mientras se navega el estado wifi
        _binding = null
    }
}