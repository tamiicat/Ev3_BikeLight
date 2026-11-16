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
import com.example.bikelight.ui.home.ModosProximidad
import com.example.bikelight.databinding.FragmentSimuladorProximidadBinding
import kotlinx.coroutines.launch

/**
 * Muestra en tiempo real la distancia medida por el sensor ultrasónico del Arduino (Nodo A).
 * El rango de detección depende del modo de proximidad activo (Carretera, Ciclovía, Ciudad, Nocturno).
 */
class SimuladorProximidadFragment : Fragment() {
    private var _binding: FragmentSimuladorProximidadBinding? = null
    private val binding get() = _binding!!

    // métrico o imperial sincronizado con preferencias de unidades de medidas
    private var unidadMedida: String = "METRICO"

    // modo de proximidad activo y su rango (cm)
    private var modoActivo: String = ModosProximidad.MODO_POR_DEFECTO
    private var rangoModoCm: Int = ModosProximidad.rangoCm(ModosProximidad.MODO_POR_DEFECTO)

    // true = la pantalla se actualiza con cada lectura; false = pantalla en pausa
    private var monitoreoActivo: Boolean = true

    // última lectura recibida (para repintar al cambiar de unidad o de modo)
    private var ultimaLectura: LecturaProximidad? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSimuladorProximidadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        configurarListeners()
        mostrarSinSenal()
        iniciarLecturaEnTiempoReal()
    }

    // al volver a la pantalla (por ejemplo desde "Modo proximidad") se relee modo y unidad
    override fun onResume() {
        super.onResume()
        cargarUnidadPreferida()
        cargarModoActivo()
        ultimaLectura?.let { if (monitoreoActivo) renderizarLectura(it) }
    }

    // lee las preferencias de unidades de medidas
    private fun cargarUnidadPreferida() {
        val prefs = requireContext().getSharedPreferences("BikeLightPrefs", Context.MODE_PRIVATE)
        unidadMedida = prefs.getString("unidad_medida", "METRICO") ?: "METRICO"
        actualizarTextoInfo()
    }

    // lee el modo de proximidad elegido y el rango que le corresponde
    private fun cargarModoActivo() {
        modoActivo = ModosProximidad.modoGuardado(requireContext())
        rangoModoCm = ModosProximidad.rangoCm(modoActivo)
        SensorProximidad.establecerRango(rangoModoCm)   // la suscripción al Arduino lleva este rango
        actualizarTextoInfo()
    }

    // texto informativo: modo, rango y unidad
    private fun actualizarTextoInfo() {
        val unidad = if (unidadMedida == "METRICO") "Métrico (cm, m)" else "Imperial (in, ft)"
        binding.tvUnidadConfigurada.text =
            "Modo: $modoActivo · Rango: ${formatearDistancia(rangoModoCm)}\nUnidad activa: $unidad"
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

            actualizarTextoInfo()
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

    // formatea una distancia en cm según la unidad elegida
    private fun formatearDistancia(cm: Int): String {
        val metros = cm / 100f
        return if (unidadMedida == "METRICO") {
            if (cm < 100) "$cm cm" else String.format("%.2f m", metros)
        } else {
            val totalPies = metros * 3.28084f
            if (totalPies < 1.0f) "${(totalPies * 12).toInt()} in" else String.format("%.2f ft", totalPies)
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

        // Rango efectivo: el umbral que el Arduino está usando de verdad (viaja en cada lectura).
        // Si no llegara, se usa el del modo elegido en la app.
        val umbralCm = if (lectura.umbralCm > 0) lectura.umbralCm else rangoModoCm

        // Zona de precaución: desde el umbral hasta el doble del umbral
        val limitePrecaucionCm = umbralCm * FACTOR_PRECAUCION

        // % de cercanía para la barra: 100 % pegado al sensor, 0 % al salir de la zona de precaución
        val factorCercania = ((1.0f - (cm.toFloat() / limitePrecaucionCm)).coerceIn(0f, 1f) * 100).toInt()
        binding.pbProximidad.progress = factorCercania

        binding.tvDistanciaValor.text = formatearDistancia(cm)

        // estados de advertencia y colores del tema
        when {
            // dentro del umbral del Arduino (mismo criterio "<" que usa el firmware): activa LEDs y buzzer
            cm < umbralCm -> {
                binding.tvEstadoAlerta.text = "¡ALERTA CRÍTICA: Objeto muy cercano!"
                binding.tvEstadoAlerta.setTextColor(Color.parseColor("#FF5252")) // Rojo advertencia
                binding.cardDisplaySensor.strokeColor = ContextCompat.getColor(requireContext(), R.color.bikelight_sos)
                binding.cardDisplaySensor.strokeWidth = 4
            }
            cm < limitePrecaucionCm -> {
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
        // La zona de "Precaución" llega hasta este múltiplo del umbral del modo (ej.: Nocturno 40 cm -> 80 cm)
        private const val FACTOR_PRECAUCION = 2
    }
}