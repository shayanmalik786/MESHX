package com.meshx.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshx.app.core.AppController
import com.meshx.app.platform.AndroidDevice

/**
 * Keeps the app logic alive while the screen is rotated or the app is in the background.
 * viewModelScope runs on the main thread, which is what the mesh code expects.
 */
class MeshViewModel(application: Application) : AndroidViewModel(application) {
    val app = AppController(AndroidDevice(application, viewModelScope))

    init {
        app.start()
    }

    override fun onCleared() {
        app.shutdown()
    }
}
