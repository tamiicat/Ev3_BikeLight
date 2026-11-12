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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentLedConfigBinding
import com.example.bikelight.ui.devices.UdpClientManager
import kotlinx.coroutines.launch

class LedConfigFragment : Fragment() {
    private var _binding: FragmentLedConfigBinding? = null
    private val binding get() = _binding!!

    private val wifiViewModel: WifiConnectionViewModel by activityViewModels()

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

        // Cada vez que el ESP32 se (re)conecta, el Arduino puede haberse reiniciado
        // y perdido la configuración: se la reenviamos.
        wifiViewModel.wifiStatus.observe(viewLifecycleOwner) { status ->
            if (status is WifiStatus.Connected) reenviarConfiguracionGuardada()
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
                    enviarComando("""{"brillo":${it.progress}}""", null)
                }
            }
        })
    }

    private fun configurarBotonesManuales() {
        binding.btnActivarLed.setOnClickListener {
            val colorIdx = binding.spinnerLedColors.selectedItemPosition
            val efectoIdx = binding.spinnerEffectColors.selectedItemPosition
            val brillo = binding.seekBarBrightness.progress

            if (colorIdx == 0 || efectoIdx == 0) {
                Toast.makeText(requireContext(), "Selecciona un patrón de color y un efecto válidos", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            enviarComando(
                """{"modo":"manual","estado":true,"color":$colorIdx,"efecto":$efectoIdx,"brillo":$brillo}""",
                "LEDs encendidos manualmente"
            )
        }

        binding.btnDesactivarLed.setOnClickListener {
            enviarComando("""{"modo":"manual","estado":false}""", "LEDs apagados")
        }
    }

    /**
     * Envía el comando y avisa según la CONFIRMACIÓN real del Arduino.
     * Si mensajeExito es null, solo avisa cuando falla.
     */
    private fun enviarComando(json: String, mensajeExito: String?, alTerminar: ((Boolean) -> Unit)? = null) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = UdpClientManager.sendCommand(json)
            alTerminar?.invoke(ok)
            val ctx = context ?: return@launch
            when {
                ok && mensajeExito != null ->
                    Toast.makeText(ctx, mensajeExito, Toast.LENGTH_SHORT).show()
                !ok ->
                    Toast.makeText(ctx, "El dispositivo no respondió. Revisa la conexión Wi-Fi con la red BIKELIGHT", Toast.LENGTH_LONG).show()
            }
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

    private fun reenviarConfiguracionGuardada() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val colorIdx = opcionesColores.indexOf(prefs.getString("led_color", null))
        val efectoIdx = opcionesEfectos.indexOf(prefs.getString("led_efecto", null))
        val brillo = prefs.getInt("led_brillo", -1)

        if (colorIdx > 0 && efectoIdx > 0 && brillo != -1) {
            enviarComando(
                """{"modo":"auto","color":$colorIdx,"efecto":$efectoIdx,"brillo":$brillo}""",
                null
            )
        }
    }

    private fun guardarConfiguracionLed() {
        val colorIdx = binding.spinnerLedColors.selectedItemPosition
        val efectoIdx = binding.spinnerEffectColors.selectedItemPosition

        if (colorIdx == 0 || efectoIdx == 0) {
            Toast.makeText(requireContext(), "Selecciona un patrón de color y un efecto válidos", Toast.LENGTH_SHORT).show()
            return
        }

        val colorSeleccionado = binding.spinnerLedColors.selectedItem.toString()
        val efectoSeleccionado = binding.spinnerEffectColors.selectedItem.toString()
        val brilloSeleccionado = binding.seekBarBrightness.progress

        enviarComando(
            """{"modo":"auto","color":$colorIdx,"efecto":$efectoIdx,"brillo":$brilloSeleccionado}""",
            "Configuración aplicada en el dispositivo"
        ) { ok ->
            // Solo se guarda localmente si el Arduino confirmó la configuración
            if (ok && _binding != null) {
                requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE).edit().apply {
                    putString("led_color", colorSeleccionado)
                    putString("led_efecto", efectoSeleccionado)
                    putInt("led_brillo", brilloSeleccionado)
                    apply()
                }
                binding.tvDisplayColor.text = "Colores: $colorSeleccionado"
                binding.tvDisplayEfecto.text = "Efecto: $efectoSeleccionado"
                binding.tvDisplayBrillo.text = "Nivel de Brillo: $brilloSeleccionado%"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance() = LedConfigFragment()
    }
}