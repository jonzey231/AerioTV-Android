package com.aeriotv.android.core.update

/**
 * GitHub self-update eligibility (GH #152). The decision is the APK SIGNER:
 * an install signed with the GitHub release key can take GitHub APKs, and
 * anything else (Play App Signing deliveries, debug builds) cannot. The
 * installer package is deliberately not an input to the decision: a
 * GitHub-signed APK provisioned with `adb install -i com.android.vending`
 * reports the Play Store as installer but can only be updated by a
 * GitHub-signed APK, while a real Play install never carries the GitHub
 * signer. [installer] is accepted so the call site and tests state that.
 */
object UpdaterGate {
    const val GITHUB_RELEASE_SIGNER_SHA256 =
        "ab94078f621e6b65b75d1bf1f49a1b2fd657cc6629eb4729cae5e74d280df005"

    @Suppress("UNUSED_PARAMETER")
    fun isEligible(updaterEnabledInFlavor: Boolean, installer: String?, signerSha256: Set<String>): Boolean =
        updaterEnabledInFlavor && GITHUB_RELEASE_SIGNER_SHA256 in signerSha256.map { it.lowercase() }
}
