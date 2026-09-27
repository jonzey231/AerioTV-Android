package com.aeriotv.android.core.cast

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The small persisted set of Cast device ids that answered the
 * urn:x-cast:com.aeriotv.control hello probe with platform=android-tv-app,
 * i.e. the TVs that run AerioTV natively via Cast Connect (2026-09-13).
 *
 * The Cast framework exposes nothing about a route's receiver app before a
 * session exists, so a device can only be classified by having cast to it once.
 * The picker therefore promotes a device into its "AerioTV on TV" section AFTER
 * its first native session, and the membership is remembered across launches so
 * the promotion is permanent for that TV.
 *
 * Keyed by [com.google.android.gms.cast.CastDevice.getDeviceId], which is stable
 * per device and is also readable from a MediaRouter route's extras, so the
 * picker can match a route without a session.
 */
@Singleton
class NativeCastDevices @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _deviceIds = MutableStateFlow(prefs.getStringSet(KEY_IDS, emptySet()).orEmpty())

    /** Device ids known to run the AerioTV Android TV receiver. */
    val deviceIds: StateFlow<Set<String>> = _deviceIds.asStateFlow()

    /** Record a device as native. No-op for a blank id or one already known. */
    fun remember(deviceId: String?) {
        val id = deviceId?.takeIf { it.isNotBlank() } ?: return
        if (id in _deviceIds.value) return
        val updated = _deviceIds.value + id
        _deviceIds.value = updated
        prefs.edit().putStringSet(KEY_IDS, updated).apply()
    }

    /** Drop a device from the native set, persisted like [remember]. Returns
     *  true when it was known. A TV whose AerioTV app was uninstalled answers
     *  the hello probe as the web receiver, and without this it stayed listed
     *  under "AerioTV on TV" forever. */
    fun forget(deviceId: String?): Boolean {
        val id = deviceId?.takeIf { it.isNotBlank() } ?: return false
        if (id !in _deviceIds.value) return false
        val updated = _deviceIds.value - id
        _deviceIds.value = updated
        prefs.edit().putStringSet(KEY_IDS, updated).apply()
        return true
    }

    private companion object {
        const val PREFS = "aerio_native_cast_devices"
        const val KEY_IDS = "device_ids"
    }
}
