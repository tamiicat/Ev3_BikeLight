package com.example.bikelight.ui.home

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentLedConfigBinding
import com.example.bikelight.UdpClient
import kotlinx.coroutines.launch

class LedConfigFragment : Fragment() {
    private var _binding: FragmentLedConfigBinding? = null
    private val binding get() = _binding!!

    // CORRECCIÓN 2: Instanciar la clase UdpClient apuntando al ESP32
    private val udpClient = UdpClient("192.168.4.1", 4210)

    private val opcionesColores = listOf(
        "Seleccione una opción",
        "Clásico (Rojo - Amarillo - Verde)",
        "Neón (Cian - Magenta - Amarillo)",
        "Emergencia (Azul - Blanco - Rojo)",
        "Flúor (Turquesa - Lima - Blanco)",
        "Arcoíris (Azul - Violeta - Rojo)",
        "Eco (Verde - Lima - Amarillo)",
        "Fuego (Rojo - Naranja - Amarillo)"
    )

    private val opcionesEfectos = listOf(
        "Seleccione una opción",
        "Fijo / Continuo",
        "Parpadeo lento",
        "Parpadeo rápido",
        "Hacia la izquierda",
        "Hacia la derecha",
        "División en el centro"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLedConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurarSpinners()
        configurarSeekBar()
        configurarBotonesManuales()
        cargarConfiguracionLed()

        binding.btnGuardarLed.setOnClickListener {
            guardarConfiguracionLed()
        }
    }

    private fun configurarSpinners() {
        val adapterColor = ArrayAdapter(requireContext(), R.layout.item_spinner_buzzer, opcionesColores)
        val adapterEfecto = ArrayAdapter(requireContext(), R.layout.item_spinner_buzzer, opcionesEfectos)

        binding.spinnerLedColors.adapter = adapterColor
        binding.spinnerEffectColors.adapter = adapterEfecto
    }

    private fun configurarSeekBar() {
        binding.seekBarBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvValorBrilloActual.text = "$progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    viewLifecycleOwner.lifecycleScope.launch {
                        udpClient.sendCommand("""{"brillo":${it.progress}}""")
                    }
                }
            }
        })
    }

    private fun configurarBotonesManuales() {
        binding.btnActivarLed.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                udpClient.sendCommand("""{"modo":"manual", "estado":true}""")
            }
            Toast.makeText(requireContext(), "LEDs encendidos manualmente", Toast.LENGTH_SHORT).show()
        }

        binding.btnDesactivarLed.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                udpClient.sendCommand("""{"modo":"manual", "estado":false}""")
            }
            Toast.makeText(requireContext(), "LEDs apagados", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cargarConfiguracionLed() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val colorGuardado = prefs.getString("led_color", null)
        val efectoGuardado = prefs.getString("led_efecto", null)
        val brilloGuardado = prefs.getInt("led_brillo", -1)

        binding.tvDisplayColor.text = "Colores: ${colorGuardado ?: "No registrado"}"
        binding.tvDisplayEfecto.text = "Efecto: ${efectoGuardado ?: "No registrado"}"
        binding.tvDisplayBrillo.text = if (brilloGuardado != -1) "Nivel de Brillo: $brilloGuardado%" else "Nivel de Brillo: No registrado"

        colorGuardado?.let {
            val index = opcionesColores.indexOf(it)
            if (index >= 0) binding.spinnerLedColors.setSelection(index)
        }

        efectoGuardado?.let {
            val index = opcionesEfectos.indexOf(it)
            if (index >= 0) binding.spinnerEffectColors.setSelection(index)
        }

        if (brilloGuardado != -1) {
            binding.seekBarBrightness.progress = brilloGuardado
            binding.tvValorBrilloActual.text = "$brilloGuardado%"
        }
    }

    private fun guardarConfiguracionLed() {
        val colorSeleccionado = binding.spinnerLedColors.selectedItem.toString()
        val efectoSeleccionado = binding.spinnerEffectColors.selectedItem.toString()
        val brilloSeleccionado = binding.seekBarBrightness.progress

        val colorIdx = binding.spinnerLedColors.selectedItemPosition
        val efectoIdx = binding.spinnerEffectColors.selectedItemPosition

        if (colorIdx == 0 || efectoIdx == 0) {
            Toast.makeText(requireContext(), "Selecciona un patrón de color y un efecto válidos", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            udpClient.sendCommand("""{"modo":"auto", "color":$colorIdx, "efecto":$efectoIdx, "brillo":$brilloSeleccionado}""")
        }

        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("led_color", colorSeleccionado)
            putString("led_efecto", efectoSeleccionado)
            putInt("led_brillo", brilloSeleccionado)
            apply()
        }

        binding.tvDisplayColor.text = "Colores: $colorSeleccionado"
        binding.tvDisplayEfecto.text = "Efecto: $efectoSeleccionado"
        binding.tvDisplayBrillo.text = "Nivel de Brillo: $brilloSeleccionado%"

        Toast.makeText(requireContext(), "Configuración de LEDs guardada exitosamente", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        udpClient.close()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance() = LedConfigFragment()
    }
}