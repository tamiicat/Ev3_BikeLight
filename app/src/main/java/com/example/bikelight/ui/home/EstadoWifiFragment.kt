package com.example.bikelight.ui.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentEstadoWifiBinding

class EstadoWifiFragment : Fragment() {
    private var _binding: FragmentEstadoWifiBinding? = null
    private val binding get() = _binding!!

    // viewmodel compartido para obtener estado de conexión wifi
    private val wifiViewModel: WifiConnectionViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEstadoWifiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observarEstadoWifi()
    }

    // estado wifi y actualiza
    private fun observarEstadoWifi() {
        wifiViewModel.wifiStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is WifiStatus.Disconnected -> {
                    binding.tvWifiStatusDetail.text = "Desconectado"
                    binding.tvWifiStatusDetail.setTextColor(Color.parseColor("#D32F2F"))
                    binding.ivWifiIcon.alpha = 0.5f
                    binding.ivWifiIcon.setImageResource(R.drawable.wifi_desconectado)
                }
                is WifiStatus.Connecting -> {
                    binding.tvWifiStatusDetail.text = "Conectando al ESP32..."
                    binding.tvWifiStatusDetail.setTextColor(Color.parseColor("#FFA500"))
                    binding.ivWifiIcon.alpha = 0.6f
                    binding.ivWifiIcon.setImageResource(R.drawable.wifi)
                }
                is WifiStatus.Connected -> {
                    binding.tvWifiStatusDetail.text = "Conectado a ${status.ssid}"
                    binding.tvWifiStatusDetail.setTextColor(Color.parseColor("#2E7D32"))
                    binding.ivWifiIcon.alpha = 1.0f
                    binding.ivWifiIcon.setImageResource(R.drawable.wifi_conectado)
                }
                is WifiStatus.Error -> {
                    binding.tvWifiStatusDetail.text = status.message
                    binding.tvWifiStatusDetail.setTextColor(Color.parseColor("#D32F2F"))
                    binding.ivWifiIcon.alpha = 0.4f
                    binding.ivWifiIcon.setImageResource(R.drawable.wifi_error)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}