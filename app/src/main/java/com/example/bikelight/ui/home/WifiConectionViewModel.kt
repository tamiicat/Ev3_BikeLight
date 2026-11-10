package com.example.bikelight.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

// define los posibles estados de la conexión
sealed class WifiStatus {
    object Disconnected : WifiStatus()
    object Connecting : WifiStatus()
    data class Connected(val ssid: String) : WifiStatus()
    data class Error(val message: String) : WifiStatus()
}

class WifiConnectionViewModel : ViewModel() {
    private val _wifiStatus = MutableLiveData<WifiStatus>(WifiStatus.Disconnected)
    val wifiStatus: LiveData<WifiStatus> = _wifiStatus
    // actualiza el estado
    fun updateStatus(status: WifiStatus) {
        _wifiStatus.postValue(status)
    }
}