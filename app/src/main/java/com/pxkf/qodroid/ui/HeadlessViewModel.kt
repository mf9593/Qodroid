/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Exposes the shared server-mode state to MainActivity so it can draw the black
 * overlay and drop window brightness, and lets the overlay turn it off.
 */
package com.pxkf.qodroid.ui

import androidx.lifecycle.ViewModel
import com.pxkf.qodroid.engine.hostbridge.HeadlessModeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class HeadlessViewModel @Inject constructor(
    private val headlessModeManager: HeadlessModeManager,
) : ViewModel() {
    val active: StateFlow<Boolean> = headlessModeManager.active
    fun disable() = headlessModeManager.setActive(false)
}
