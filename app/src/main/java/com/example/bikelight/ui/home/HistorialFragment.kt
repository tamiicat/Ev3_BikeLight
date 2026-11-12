package com.example.bikelight.ui.home

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
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

        actualizarEstadoInternet()

        // 1. Observar paquetes reales en Room (Offline + Sincronizados localmente)
        val db = AppDatabase.getDatabase(requireContext())
        db.sensorReadingDao().observeAllReadings().observe(viewLifecycleOwner) { listaRoom ->
            lecturasLocales = listaRoom
            fusionarYMostrarHistorial()
        }

        // 2. Escuchar en tiempo real los paquetes reales de Firebase Realtime Database
        escucharPaquetesEnFirebase()

        // 3. Botón para forzar subida inmediata de los paquetes Offline pendientes
        binding.btnForzarSync.setOnClickListener {
            actualizarEstadoInternet()
            (activity as? MainActivity)?.programarSincronizacionFirebase(forzarReemplazo = true)
            Toast.makeText(
                requireContext(),
                "Sincronizando paquetes pendientes con Firebase...",
                Toast.LENGTH_SHORT
            ).show()
        }
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
                    val proximityMode = child.child("proximityMode").getValue(String::class.java) ?: "Ciudad"
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
                // Si está sin internet, Room seguirá mostrando los datos locales sin interrupción
            }
        }

        firebaseRef?.addValueEventListener(firebaseListener!!)
    }

    private fun fusionarYMostrarHistorial() {
        actualizarEstadoInternet()

        // Combinar Room + Firebase evitando duplicados por timestamp y distancia
        val mapaCombinado = LinkedHashMap<String, SensorReading>()

        // Primero agregamos los registros locales (priorizando estado offline si aún no sube)
        for (item in lecturasLocales) {
            val clave = "${item.timestamp}_${item.distanceCm}"
            mapaCombinado[clave] = item
        }

        // Luego agregamos los registros descargados de Firebase que no estén en Room
        for (itemNube in lecturasNube) {
            val clave = "${itemNube.timestamp}_${itemNube.distanceCm}"
            if (!mapaCombinado.containsKey(clave)) {
                mapaCombinado[clave] = itemNube
            }
        }

        val listaFinal = mapaCombinado.values.sortedByDescending { it.timestamp }

        val total = listaFinal.size
        val online = listaFinal.count { it.isSynced }
        val offline = total - online

        binding.tvResumenSync.text =
            "Total: $total | Online (Firebase): $online | Offline (Local): $offline"

        if (listaFinal.isEmpty()) {
            binding.tvSinRegistros.visibility = View.VISIBLE
            binding.rvHistorialPaquetes.visibility = View.GONE
        } else {
            binding.tvSinRegistros.visibility = View.GONE
            binding.rvHistorialPaquetes.visibility = View.VISIBLE
            adapter.submitList(listaFinal)
        }
    }

    private fun actualizarEstadoInternet() {
        val hayInternet = tieneConexionInternet(requireContext())
        if (hayInternet) {
            binding.tvEstadoRedHistorial.text = "Estado de Red: ONLINE (Conectado a Firebase)"
            binding.tvEstadoRedHistorial.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            binding.tvEstadoRedHistorial.text = "Estado de Red: OFFLINE (Modo Local Room)"
            binding.tvEstadoRedHistorial.setTextColor(Color.parseColor("#C62828"))
        }
    }

    private fun tieneConexionInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        firebaseListener?.let { firebaseRef?.removeEventListener(it) }
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
                holder.tvBadgeSync.text = "ONLINE (Firebase)"
                holder.tvBadgeSync.setTextColor(Color.parseColor("#2E7D32"))
                holder.tvBadgeSync.setBackgroundColor(Color.parseColor("#E8F5E9"))
            } else {
                holder.tvBadgeSync.text = "OFFLINE (Local)"
                holder.tvBadgeSync.setTextColor(Color.parseColor("#EF6C00"))
                holder.tvBadgeSync.setBackgroundColor(Color.parseColor("#FFF3E0"))
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