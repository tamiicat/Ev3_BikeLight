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
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentBuzzerConfigBinding

class BuzzerFragment : Fragment() {
    private var _binding: FragmentBuzzerConfigBinding? = null
    private val binding get() = _binding!!
    private var toneGenerator: ToneGenerator? = null
    private var isSimulando: Boolean = false

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
        // generador de audios
        toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)

        // configuración de spinners de frecuencia y duración
        val adapterFrec = ArrayAdapter(requireContext(), R.layout.item_spinner_buzzer, opcionesFrecuencia)
        val adapterDur = ArrayAdapter(requireContext(), R.layout.item_spinner_buzzer, opcionesDuracion)

        binding.spinnerFrecuencia.adapter = adapterFrec
        binding.spinnerDuracion.adapter = adapterDur

        // carga los valores guardados
        cargarConfiguracionBuzzer()

        // guardar configuración
        binding.btnGuardarBuzzer.setOnClickListener {
            guardarConfiguracionBuzzer()
        }

        // activar simulación de funcionamiento del buzzer
        binding.btnActivar.setOnClickListener {
            val duracionTexto = binding.spinnerDuracion.selectedItem.toString()
            val duracionMs = when {
                duracionTexto.contains("0.05s") -> 50
                duracionTexto.contains("0.10s") -> 100
                duracionTexto.contains("0.25s") -> 250
                duracionTexto.contains("0.50s") -> 500
                duracionTexto.contains("1.00s") -> 1000
                else -> 200
            }

            isSimulando = true
            ejecutarSimulacion(duracionMs)
            Toast.makeText(requireContext(), "Buzzer Activado ($duracionMs ms)", Toast.LENGTH_SHORT).show()
        }

        // desactivar simulación
        binding.btnDesactivar.setOnClickListener {
            isSimulando = false
            toneGenerator?.stopTone()
            Toast.makeText(requireContext(), "Buzzer Desactivado", Toast.LENGTH_SHORT).show()
        }
    }
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
    // guarda los valores del buzzer
    private fun guardarConfiguracionBuzzer() {
        val frecSeleccionada = binding.spinnerFrecuencia.selectedItem.toString()
        val durSeleccionada = binding.spinnerDuracion.selectedItem.toString()

        // valida que se seleccione una opción válida
        if (binding.spinnerFrecuencia.selectedItemPosition == 0 || binding.spinnerDuracion.selectedItemPosition == 0) {
            Toast.makeText(requireContext(), "Por favor selecciona una frecuencia y una duración válidas", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("buzzer_frecuencia", frecSeleccionada)
            putString("buzzer_duracion", durSeleccionada)
            apply()
        }

        // actualiza tarjeta con datos guardados
        binding.tvDisplayFrecuencia.text = "Frecuencia: $frecSeleccionada"
        binding.tvDisplayDuracion.text = "Duración: $durSeleccionada"

        Toast.makeText(requireContext(), "Configuración guardada exitosamente", Toast.LENGTH_SHORT).show()
    }
    // emite tono acústico
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
}