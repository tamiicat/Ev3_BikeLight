package com.example.bikelight.ui.home

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.bikelight.MainActivity
import com.example.bikelight.databinding.FragmentWifiConfigBinding

class WifiConfigFragment : Fragment() {
    private var _binding: FragmentWifiConfigBinding? = null
    private val binding get() = _binding!!

    // ViewModel compartido: MainActivity publica el estado, este fragment solo lo observa
    private val wifiViewModel: WifiConnectionViewModel by activityViewModels()

    private var wifiManager: WifiManager? = null

    // Receptor para escuchar el escaneo de redes Wi-Fi
    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                updateWifiScanList()
            }
        }
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startWifiScan()
        } else {
            Toast.makeText(
                requireContext(),
                "Se requiere permiso de ubicación para escanear redes Wi-Fi",
                Toast.LENGTH_LONG
            ).show()
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

        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        setupListeners()
        observarEstadoWifi()
    }

    // La UI sigue el estado que publica MainActivity (única dueña de la conexión)
    private fun observarEstadoWifi() {
        wifiViewModel.wifiStatus.observe(viewLifecycleOwner) { status ->
            val conectando = status is WifiStatus.Connecting
            binding.progressBar.visibility = if (conectando) View.VISIBLE else View.GONE
            binding.btnConnectDevice.isEnabled = !conectando
        }
    }

    override fun onStart() {
        super.onStart()
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(wifiScanReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(wifiScanReceiver, intentFilter)
            }
        } catch (e: Exception) {
            Log.e("WifiConfig", "Error al registrar receiver", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            requireContext().unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {
        }
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

            // Delegamos en MainActivity: así hay UNA sola solicitud de red activa
            (requireActivity() as MainActivity).conectarARedEsp32(ssid, pass)
        }
    }

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
            Toast.makeText(
                requireContext(),
                "No se encontraron redes o la ubicación está apagada",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        _binding?.let { b ->
            b.lvWifiList.visibility = View.VISIBLE
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, ssids)
            b.lvWifiList.adapter = adapter
            b.lvWifiList.setOnItemClickListener { _, _, position, _ ->
                val selectedSsid = ssids[position]
                b.etDeviceAp.setText(selectedSsid)
                b.tilDeviceAp.error = null
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}