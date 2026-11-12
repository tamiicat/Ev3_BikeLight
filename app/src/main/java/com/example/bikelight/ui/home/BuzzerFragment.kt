package com.example.bikelight.ui.home

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentBuzzerConfigBinding
import com.example.bikelight.ui.devices.Nodo
import com.example.bikelight.ui.devices.UdpClientManager
import kotlinx.coroutines.launch

class BuzzerFragment : Fragment() {
    private var _binding: FragmentBuzzerConfigBinding? = null
    private val binding get() = _binding!!
    private var toneGenerator: ToneGenerator? = null
    private var isSimulando: Boolean = false

    // Estado de la conexión Wi-Fi (lo publica MainActivity)
    private val wifiViewModel: WifiConnectionViewModel by activityViewModels()

    private val opcionesFrecuencia = arrayOf(
        "Seleccione una opción",
        "Tono Grave (1000 Hz)",
        "Tono Medio (1500 Hz)",
        "Estándar (2000 Hz)",
        "Alerta de Tráfico (2500 Hz)",
        "Tono Agudo (4000 Hz)"
    )

    private val opcionesDuracion = arrayOf(
        "Seleccione una opción",
        "Pulso Ultracorto (0.05s)",
        "Pitido Estándar (0.10s)",
        "Pulso Medio (0.25s)",
        "Advertencia (0.50s)",
        "Tono Continuo (1.00s)"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBuzzerConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // generador de audios (simulación local en el teléfono)
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

        // configuración de spinners de frecuencia y duración
        val adapterFrec = ArrayAdapter(requireContext(), R.layout.item_spinner_buzzer, opcionesFrecuencia)
        val adapterDur = ArrayAdapter(requireContext(), R.layout.item_spinner_buzzer, opcionesDuracion)

        binding.spinnerFrecuencia.adapter = adapterFrec
        binding.spinnerDuracion.adapter = adapterDur

        // carga los valores guardados
        cargarConfiguracionBuzzer()

        // guardar configuración (se aplica en el buzzer real del ESP32)
        binding.btnGuardarBuzzer.setOnClickListener {
            guardarConfiguracionBuzzer()
        }

        // probar el buzzer: suena el del ESP32 y se simula en el teléfono
        binding.btnActivar.setOnClickListener {
            val frecuenciaHz = if (binding.spinnerFrecuencia.selectedItemPosition > 0) {
                frecuenciaEnHz(binding.spinnerFrecuencia.selectedItem.toString())
            } else {
                FRECUENCIA_POR_DEFECTO_HZ
            }
            val duracionMs = duracionEnMs(binding.spinnerDuracion.selectedItem.toString())

            isSimulando = true
            ejecutarSimulacion(duracionMs)

            enviarAlEsp32(
                """{"buzzer_frec":$frecuenciaHz,"buzzer_dur":$duracionMs,"buzzer_prueba":true}""",
                "Buzzer activado ($duracionMs ms)"
            )
        }

        // detener la prueba
        binding.btnDesactivar.setOnClickListener {
            isSimulando = false
            toneGenerator?.stopTone()
            enviarAlEsp32("""{"buzzer_prueba":false}""", "Buzzer desactivado")
        }

        // Si el ESP32 se reinicia o se reconecta, pierde la configuración: se la reenviamos
        wifiViewModel.wifiStatus.observe(viewLifecycleOwner) { status ->
            if (status is WifiStatus.Connected) reenviarConfiguracionGuardada()
        }
    }

    // ---------- Comunicación con el ESP32 (Nodo B) ----------

    /**
     * Envía un comando JSON SOLO al ESP32 (Nodo B, donde está el buzzer) y avisa
     * según la confirmación real. Si mensajeExito es null, solo avisa cuando falla.
     */
    private fun enviarAlEsp32(
        json: String,
        mensajeExito: String?,
        alTerminar: ((Boolean) -> Unit)? = null
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = UdpClientManager.sendCommand(json, setOf(Nodo.B))
            alTerminar?.invoke(ok)
            val ctx = context ?: return@launch
            if (ok) {
                mensajeExito?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
            } else {
                Toast.makeText(
                    ctx,
                    "No respondió ${Nodo.B.nombre}. Revisa la conexión Wi-Fi",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun reenviarConfiguracionGuardada() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val frecuencia = prefs.getString("buzzer_frecuencia", null)
        val duracion = prefs.getString("buzzer_duracion", null)

        if (frecuencia != null && duracion != null) {
            enviarAlEsp32(
                """{"buzzer_activo":true,"buzzer_frec":${frecuenciaEnHz(frecuencia)},"buzzer_dur":${duracionEnMs(duracion)}}""",
                null
            )
        }
    }

    // ---------- Conversión de las opciones de los spinners ----------

    // "Estándar (2000 Hz)" -> 2000
    private fun frecuenciaEnHz(texto: String): Int =
        Regex("""\((\d+)\s*Hz\)""").find(texto)?.groupValues?.get(1)?.toIntOrNull()
            ?: FRECUENCIA_POR_DEFECTO_HZ

    // "Advertencia (0.50s)" -> 500
    private fun duracionEnMs(texto: String): Int = when {
        texto.contains("0.05s") -> 50
        texto.contains("0.10s") -> 100
        texto.contains("0.25s") -> 250
        texto.contains("0.50s") -> 500
        texto.contains("1.00s") -> 1000
        else -> 200
    }

    // ---------- Preferencias ----------

    // lee las preferencias almacenadas
    private fun cargarConfiguracionBuzzer() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        val frecuencia = prefs.getString("buzzer_frecuencia", null)
        val duracion = prefs.getString("buzzer_duracion", null)

        binding.tvDisplayFrecuencia.text = "Frecuencia: ${frecuencia ?: "No registrada"}"
        binding.tvDisplayDuracion.text = "Duración: ${duracion ?: "No registrada"}"
        // selecciona la opcion guardada en spinners
        frecuencia?.let {
            val index = opcionesFrecuencia.indexOf(it)
            if (index >= 0) binding.spinnerFrecuencia.setSelection(index)
        }
        duracion?.let {
            val index = opcionesDuracion.indexOf(it)
            if (index >= 0) binding.spinnerDuracion.setSelection(index)
        }
    }

    // aplica la configuración en el ESP32 y, si confirmó, la guarda
    private fun guardarConfiguracionBuzzer() {
        // valida que se seleccione una opción válida
        if (binding.spinnerFrecuencia.selectedItemPosition == 0 || binding.spinnerDuracion.selectedItemPosition == 0) {
            Toast.makeText(requireContext(), "Por favor selecciona una frecuencia y una duración válidas", Toast.LENGTH_SHORT).show()
            return
        }

        val frecSeleccionada = binding.spinnerFrecuencia.selectedItem.toString()
        val durSeleccionada = binding.spinnerDuracion.selectedItem.toString()
        val frecuenciaHz = frecuenciaEnHz(frecSeleccionada)
        val duracionMs = duracionEnMs(durSeleccionada)

        enviarAlEsp32(
            """{"buzzer_activo":true,"buzzer_frec":$frecuenciaHz,"buzzer_dur":$duracionMs}""",
            "Configuración aplicada en el buzzer"
        ) { ok ->
            // Solo se guarda localmente si el ESP32 confirmó la configuración
            if (ok && _binding != null) {
                requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE).edit().apply {
                    putString("buzzer_frecuencia", frecSeleccionada)
                    putString("buzzer_duracion", durSeleccionada)
                    apply()
                }
                // actualiza tarjeta con datos guardados
                binding.tvDisplayFrecuencia.text = "Frecuencia: $frecSeleccionada"
                binding.tvDisplayDuracion.text = "Duración: $durSeleccionada"
            }
        }
    }

    // emite tono acústico y vibración en el teléfono (simulación local)
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun ejecutarSimulacion(duracionMs: Int) {
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, duracionMs)

        val context = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(
                VibrationEffect.createOneShot(duracionMs.toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(duracionMs.toLong())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        toneGenerator?.release()
        toneGenerator = null
        _binding = null
    }

    companion object {
        private const val FRECUENCIA_POR_DEFECTO_HZ = 2000
    }
}