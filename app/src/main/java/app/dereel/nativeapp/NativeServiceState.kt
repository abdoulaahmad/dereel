package app.dereel.nativeapp

import app.dereel.core.model.Platform
import app.dereel.core.status.LayoutRecognition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live state published by the accessibility service. It runs in the app process, so the UI and
 * status checks observe its lifecycle callbacks immediately (FR-36).
 */
object NativeServiceState {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _layout = MutableStateFlow<Map<Platform, LayoutRecognition>>(emptyMap())
    val layout: StateFlow<Map<Platform, LayoutRecognition>> = _layout.asStateFlow()

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    fun setLayout(platform: Platform, recognition: LayoutRecognition) {
        _layout.update { it + (platform to recognition) }
    }
}
