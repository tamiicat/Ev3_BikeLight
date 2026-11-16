package com.example.bikelight.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.bikelight.MainActivity
import com.example.bikelight.R
import com.example.bikelight.data.local.AppDatabase
import com.example.bikelight.data.local.SensorReading
import com.example.bikelight.databinding.FragmentHistorialBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialFragment : Fragment() {

    private var _binding: FragmentHistorialBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: SensorReadingAdapter

    private var lecturasLocales: List<SensorReading> = emptyList()
    private var lecturasNube: List<SensorReading> = emptyList()

    private var firebaseRef: DatabaseReference? = null
    private var firebaseListener: ValueEventListener? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val PREFS_HISTORIAL = "bikelight_historial_prefs"
        private const val KEY_CACHE_CLEARED_AT = "cache_cleared_timestamp_"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistorialBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SensorReadingAdapter()
        binding.rvHistorialPaquetes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistorialPaquetes.adapter = adapter

        registrarMonitorDeRed()
        actualizarEstadoInternet()

        val db = AppDatabase.getDatabase(requireContext())
        db.sensorReadingDao().observeAllReadings().observe(viewLifecycleOwner) { listaRoom ->
            lecturasLocales = listaRoom
            fusionarYMostrarHistorial()
        }

        escucharPaquetesEnFirebase()

        binding.btnForzarSync.setOnClickListener {
            actualizarEstadoInternet()
            if (!tieneConexionInternet(requireContext())) {
                Toast.makeText(
                    requireContext(),
                    "Sin conexión a internet (OFFLINE). Se sincronizará al reconectar.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            (activity as? MainActivity)?.programarSincronizacionFirebase(forzarReemplazo = true)
            Toast.makeText(
                requireContext(),
                "Sincronizando paquetes pendientes con Firebase...",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnLimpiarCache.setOnClickListener {
            confirmarLimpiezaCacheLocal(db)
        }
    }

    private fun obtenerMarcaTiempoLimpieza(): Long {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "default"
        val prefs = requireContext().getSharedPreferences(PREFS_HISTORIAL, Context.MODE_PRIVATE)
        return prefs.getLong("$KEY_CACHE_CLEARED_AT$uid", 0L)
    }

    private fun guardarMarcaTiempoLimpieza(timestamp: Long) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: "default"
        val prefs = requireContext().getSharedPreferences(PREFS_HISTORIAL, Context.MODE_PRIVATE)
        prefs.edit {
            putLong("$KEY_CACHE_CLEARED_AT$uid", timestamp)
        }
    }

    private fun confirmarLimpiezaCacheLocal(db: AppDatabase) {
        val totalVisibles = adapter.itemCount
        if (totalVisibles == 0) {
            Toast.makeText(requireContext(), "No hay registros en caché para limpiar.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Limpiar caché local")
            .setMessage(
                "Se eliminarán de la vista y de la caché del teléfono los registros ya sincronizados. " +
                        "Tus datos respaldados en Firebase NO se borrarán."
            )
            .setPositiveButton("Limpiar") { _, _ ->
                val ahora = System.currentTimeMillis()
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val eliminadosRoom = db.sensorReadingDao().deleteSyncedReadings()
                    withContext(Dispatchers.Main) {
                        if (_binding != null) {
                            guardarMarcaTiempoLimpieza(ahora)
                            val totalEliminadosVista = maxOf(eliminadosRoom, totalVisibles - lecturasLocales.count { !it.isSynced })
                            fusionarYMostrarHistorial()
                            Toast.makeText(
                                requireContext(),
                                "Caché eliminada ($totalEliminadosVista elementos eliminados de la app).",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun registrarMonitorDeRed() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        actualizarEstadoInternet()
                        if (firebaseListener == null) {
                            escucharPaquetesEnFirebase()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        actualizarEstadoInternet()
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                activity?.runOnUiThread {
                    if (_binding != null) {
                        actualizarEstadoInternet()
                    }
                }
            }
        }

        networkCallback = callback
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun escucharPaquetesEnFirebase() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        firebaseRef = FirebaseDatabase.getInstance().reference
            .child("users")
            .child(uid)
            .child("sensor_readings")

        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val listaRemota = mutableListOf<SensorReading>()
                for (child in snapshot.children) {
                    val distanceCm = child.child("distanceCm").getValue(Double::class.java)?.toFloat()
                        ?: child.child("distanceCm").getValue(Float::class.java)
                        ?: continue
                    val riskLevel = child.child("riskLevel").getValue(String::class.java) ?: "DESCONOCIDO"
                    val proximityMode = child.child("proximityMode").getValue(String::class.java)
                        ?: ModosProximidad.MODO_POR_DEFECTO
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L

                    listaRemota.add(
                        SensorReading(
                            uid = uid,
                            distanceCm = distanceCm,
                            riskLevel = riskLevel,
                            proximityMode = proximityMode,
                            timestamp = timestamp,
                            isSynced = true
                        )
                    )
                }
                lecturasNube = listaRemota
                if (_binding != null) {
                    fusionarYMostrarHistorial()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Si está sin internet, Room mantiene los datos locales visibles
            }
        }

        firebaseRef?.addValueEventListener(firebaseListener!!)
    }

    private fun fusionarYMostrarHistorial() {
        val hayInternet = tieneConexionInternet(requireContext())
        actualizarEstadoInternet(hayInternet)

        val marcaLimpieza = obtenerMarcaTiempoLimpieza()
        val mapaCombinado = LinkedHashMap<String, SensorReading>()

        for (item in lecturasLocales) {
            val clave = "${item.timestamp}_${item.distanceCm}"
            mapaCombinado[clave] = item
        }

        // Solo fusionar lecturas de la nube posteriores a la última limpieza de caché local
        if (hayInternet) {
            for (itemNube in lecturasNube) {
                if (itemNube.timestamp > marcaLimpieza) {
                    val clave = "${itemNube.timestamp}_${itemNube.distanceCm}"
                    if (!mapaCombinado.containsKey(clave)) {
                        mapaCombinado[clave] = itemNube
                    }
                }
            }
        }

        val listaFinal = mapaCombinado.values.sortedByDescending { it.timestamp }

        val total = listaFinal.size
        val online = listaFinal.count { it.isSynced }
        val offline = total - online

        binding.tvResumenSync.text =
            "Total: $total | ONLINE: $online | OFFLINE: $offline"

        if (listaFinal.isEmpty()) {
            binding.tvSinRegistros.visibility = View.VISIBLE
            binding.rvHistorialPaquetes.visibility = View.GONE
            adapter.submitList(emptyList())
        } else {
            binding.tvSinRegistros.visibility = View.GONE
            binding.rvHistorialPaquetes.visibility = View.VISIBLE
            adapter.submitList(listaFinal)
        }
    }

    private fun actualizarEstadoInternet(forzarEstado: Boolean? = null) {
        val hayInternet = forzarEstado ?: tieneConexionInternet(requireContext())
        if (hayInternet) {
            binding.tvEstadoRedHistorial.text = "Estado de Red: ONLINE"
            binding.tvEstadoRedHistorial.setTextColor("#2E7D32".toColorInt())
        } else {
            binding.tvEstadoRedHistorial.text = "Estado de Red: OFFLINE"
            binding.tvEstadoRedHistorial.setTextColor("#C62828".toColorInt())
        }
    }

    private fun tieneConexionInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        firebaseListener?.let { firebaseRef?.removeEventListener(it) }
        firebaseListener = null
        _binding = null
    }

    private class SensorReadingAdapter :
        RecyclerView.Adapter<SensorReadingAdapter.ReadingViewHolder>() {

        private var items: List<SensorReading> = emptyList()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        fun submitList(newList: List<SensorReading>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReadingViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sensor_reading, parent, false)
            return ReadingViewHolder(view)
        }

        override fun onBindViewHolder(holder: ReadingViewHolder, position: Int) {
            val item = items[position]
            holder.tvDistanciaRiesgo.text = "${item.distanceCm} cm — ${item.riskLevel}"

            val fechaStr = dateFormat.format(Date(item.timestamp))
            holder.tvDetallePaquete.text = "Modo: ${item.proximityMode} | $fechaStr"

            if (item.isSynced) {
                holder.tvBadgeSync.text = "ONLINE"
                holder.tvBadgeSync.setTextColor("#2E7D32".toColorInt())
                holder.tvBadgeSync.setBackgroundColor("#E8F5E9".toColorInt())
            } else {
                holder.tvBadgeSync.text = "OFFLINE"
                holder.tvBadgeSync.setTextColor("#EF6C00".toColorInt())
                holder.tvBadgeSync.setBackgroundColor("#FFF3E0".toColorInt())
            }
        }

        override fun getItemCount(): Int = items.size

        class ReadingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDistanciaRiesgo: TextView = view.findViewById(R.id.tvDistanciaRiesgo)
            val tvBadgeSync: TextView = view.findViewById(R.id.tvBadgeSync)
            val tvDetallePaquete: TextView = view.findViewById(R.id.tvDetallePaquete)
        }
    }
}