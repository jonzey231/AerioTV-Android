package com.aeriotv.android.core.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider

/**
 * Cast Connect receiver-side configuration (GH #33). On an Android TV device the
 * Cast SDK instantiates this (named by the RECEIVER_OPTIONS_PROVIDER_CLASS_NAME
 * manifest meta-data) when CastReceiverContext.initInstance() runs, to obtain the
 * receiver options.
 *
 * The status text is what the sender surfaces while connecting to this receiver.
 */
class AerioReceiverOptionsProvider : ReceiverOptionsProvider {

    override fun getOptions(context: Context): CastReceiverOptions {
        return CastReceiverOptions.Builder(context)
            .setStatusText("AerioTV")
            // GH #33 full-parity cast remote: declare the custom control namespace
            // so senders can drive audio/subtitle/speed/aspect on this receiver
            // (see [CastControl] + AerioCastReceiverController's message listener).
            .setCustomNamespaces(listOf(CastControl.NAMESPACE))
            // Owner rule 2026-11-14, a basic cast must not need the app: a
            // phone that picked the TV under "Google Cast" says so in the
            // launch credentials, and this app declines; the Cast framework
            // then loads the web receiver. Only "AerioTV on TV" (or a sender
            // that names no intent) launches the app.
            .setLaunchRequestChecker { request ->
                val accept = CastLaunchIntent.tvAppAccepts(request.credentialsData?.credentials)
                android.util.Log.i(
                    "AerioCastReceiver",
                    "[Cast] launch request: ${if (accept) "launching the AerioTV app" else "plain cast, the web receiver loads instead"}",
                )
                com.google.android.gms.tasks.Tasks.forResult(accept)
            }
            .build()
    }
}
