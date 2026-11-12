package com.example.bikelight.ui.devices

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.bikelight.R
import com.example.bikelight.databinding.FragmentSimuladorProximidadBinding
import kotlinx.coroutines.launch

/**
 * Muestra en tiempo real la distancia medida por el sensor ultrasónico del Arduino (Nodo A).
 * Ya no genera valores simulados: si no hay conexión o el Arduino no responde, lo indica.
 */
class SimuladorProximidadFragment : Fragment() {
    private var _binding: FragmentSimuladorProximidadBinding? = null
    private val binding get() = _binding!!

    // métrico o imperial sincronizado con preferencias de unidades de medidas
    private var unidadMedida: String = "METRICO"

    // true = la pantalla se actualiza con cada lectura; false = pantalla en pausa
    private var monitoreoActivo: Boolean = true

    // última lectura recibida (para repintar al cambiar de unidad)
    private var ultimaLectura: LecturaProximidad? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimuladorProximidadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // carga las preferencias de unidades
        cargarUnidadPreferida()
        configurarListeners()
        mostrarSinSenal()
        iniciarLecturaEnTiempoReal()
    }

    // lee las preferencias de unidades de medidas
    private fun cargarUnidadPreferida() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        unidadMedida = prefs.getString("unidad_medida", "METRICO") ?: "METRICO"
        actualizarTextoUnidad()
    }

    // actualiza texto informativo
    private fun actualizarTextoUnidad() {
        val texto = if (unidadMedida == "METRICO") "Métrico (cm, m)" else "Imperial (in, ft)"
        binding.tvUnidadConfigurada.text = "Unidad activa: $texto"
    }

    // configura los eventos de clic
    private fun configurarListeners() {
        // botón para alternar entre 'pausa' y 'reanudar' la actualización de la pantalla
        binding.btnIniciarPausar.setOnClickListener {
            monitoreoActivo = !monitoreoActivo
            binding.btnIniciarPausar.text = if (monitoreoActivo) "Pausar" else "Reanudar"
        }

        binding.btnCambiarUnidad.setOnClickListener {
            // permite alternar la unidad y guardarla en preferencias
            unidadMedida = if (unidadMedida == "METRICO") "IMPERIAL" else "METRICO"
            requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
                .edit().putString("unidad_medida", unidadMedida).apply()

            actualizarTextoUnidad()
            ultimaLectura?.let { renderizarLectura(it) }
        }
    }

    // Recibe las lecturas reales del Arduino mientras la pantalla está visible
    private fun iniciarLecturaEnTiempoReal() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SensorProximidad.lecturas().collect { lectura ->
                    ultimaLectura = lectura
                    if (!monitoreoActivo) return@collect   // en pausa: se ignora la actualización

                    if (lectura == null) mostrarSinSenal() else renderizarLectura(lectura)
                }
            }
        }
    }

    // sin conexión con la red del ESP32 o sin datos del Arduino
    private fun mostrarSinSenal() {
        binding.pbProximidad.progress = 0
        binding.tvDistanciaValor.text = "--"
        binding.tvEstadoAlerta.text = "Sin señal del sensor. Revisa la conexión con la red BikeLight"
        binding.tvEstadoAlerta.setTextColor(Color.GRAY)
        binding.cardDisplaySensor.strokeWidth = 0
    }

    // procesa y renderiza la lectura real
    private fun renderizarLectura(lectura: LecturaProximidad) {
        // el sensor no recibió eco: no hay nada dentro de su alcance
        if (lectura.cm < 0) {
            binding.pbProximidad.progress = 0
            binding.tvDistanciaValor.text = "Fuera de rango"
            binding.tvEstadoAlerta.text = "Distancia segura (sin objetos cerca)"
            binding.tvEstadoAlerta.setTextColor(Color.parseColor("#81C784"))
            binding.cardDisplaySensor.strokeWidth = 0
            return
        }

        val cm = lectura.cm

        // % de cercanía para la barra de progreso (según el alcance real del sensor)
        val factorCercania = ((1.0f - (cm.toFloat() / ALCANCE_MAX_CM)).coerceIn(0f, 1f) * 100).toInt()
        binding.pbProximidad.progress = factorCercania

        // conversión y formateo según unidades
        val metros = cm / 100f
        val textoDistancia = if (unidadMedida == "METRICO") {
            if (cm < 100) "$cm cm" else String.format("%.2f m", metros)
        } else {
            // conversión a pies y pulgadas
            val totalPies = metros * 3.28084f
            if (totalPies < 1.0f) {
                val pulgadas = (totalPies * 12).toInt()
                "$pulgadas in"
            } else {
                String.format("%.2f ft", totalPies)
            }
        }
        binding.tvDistanciaValor.text = textoDistancia

        // estados de advertencia y colores del tema
        when {
            // dentro del umbral del Arduino: es cuando el dispositivo activa LEDs y buzzer
            cm <= lectura.umbralCm -> {
                binding.tvEstadoAlerta.text = "¡ALERTA CRÍTICA: Objeto muy cercano!"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#FF5252")) // Rojo advertencia
                binding.cardDisplaySensor.strokeColor = ContextCompat.getColor(requireContext(), R.color.bikelight_sos)
                binding.cardDisplaySensor.strokeWidth = 4
            }
            cm < PRECAUCION_CM -> {
                binding.tvEstadoAlerta.text = "Precaución: Objeto detectado"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#FFB74D")) // Naranja precaución
                binding.cardDisplaySensor.strokeWidth = 0
            }
            else -> {
                binding.tvEstadoAlerta.text = "Distancia segura"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#81C784")) // Verde seguro
                binding.cardDisplaySensor.strokeWidth = 0
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        // Alcance útil del sensor del Arduino (pulseIn con timeout de 15 ms ≈ 2.5 m)
        private const val ALCANCE_MAX_CM = 250f

        // Por debajo de esta distancia (y por encima del umbral del Arduino) se muestra "Precaución"
        private const val PRECAUCION_CM = 100
    }
}