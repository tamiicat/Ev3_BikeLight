package com.example.bikelight.ui.home

import android.content.Context

/**
 * Modos de proximidad y el rango de detección (en cm) de cada uno.
 * Es la única tabla que define el rango: la usan la pantalla de modos y la del simulador.
 */
object ModosProximidad {
    const val MODO_POR_DEFECTO = "Ciudad"
    private const val PREFS = "BikeLightPrefs"
    private const val KEY_MODO = "modo_proximidad"

    private val RANGOS_CM = mapOf(
        "Carretera" to 40,
        "Ciclovía" to 10,
        "Ciudad" to 20,
        "Nocturno" to 40
    )

    fun rangoCm(modo: String?): Int = RANGOS_CM[modo] ?: RANGOS_CM.getValue(MODO_POR_DEFECTO)

    // copia local del modo activo (la lee el simulador y la capa que habla con el Arduino)
    fun guardarModo(context: Context, modo: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODO, modo).apply()
    }

    fun modoGuardado(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODO, MODO_POR_DEFECTO) ?: MODO_POR_DEFECTO
}