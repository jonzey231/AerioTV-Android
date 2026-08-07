package com.aeriotv.android.core.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the TV launcher's INITIALIZE_PROGRAMS broadcast (audit #47): sent
 * when the user enables AerioTV's channel row from the launcher's own
 * customize-channels UI while the app isn't running, so the row fills without
 * requiring an app launch first.
 */
@AndroidEntryPoint
class HomeChannelsInitReceiver : BroadcastReceiver() {

    @Inject lateinit var publisher: HomeChannelsPublisher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvContractCompatActionInitializePrograms) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                publisher.publishNow()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // TvContractCompat.ACTION_INITIALIZE_PROGRAMS mirrors this literal;
        // inlined so the receiver has no library dependency at class-load.
        const val TvContractCompatActionInitializePrograms =
            "android.media.tv.action.INITIALIZE_PROGRAMS"
    }
}
