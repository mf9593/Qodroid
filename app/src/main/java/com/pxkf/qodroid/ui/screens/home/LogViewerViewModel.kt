package com.pxkf.qodroid.ui.screens.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pxkf.qodroid.util.DiagnosticLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Loads the diagnostic logs shown by [LogViewerScreen] (About page →
 * "View logs"): this process's own logcat filtered to Podroid tags, plus the
 * QEMU guest console.log when it exists. Read-only — nothing here mutates
 * app state, so it is safe to open from any screen.
 */
@HiltViewModel
class LogViewerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _logText.value = withContext(Dispatchers.IO) { buildLog() }
            _loading.value = false
        }
    }

    /**
     * Put the whole dump on the clipboard so it can be pasted into a bug report.
     * There is no length cap: the text can be hundreds of KB and this is a
     * diagnostic tool, not a paste-into-chat affordance.
     */
    fun copyLog() {
        val text = _logText.value
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("podroid-log", text))
    }

    /**
     * One shared dump — see [DiagnosticLog]. "Export logs" writes this exact
     * text, so the two buttons cannot drift into meaning different things.
     */
    private fun buildLog(): String = DiagnosticLog.build(context, title = "Podroid Log Viewer")
}
