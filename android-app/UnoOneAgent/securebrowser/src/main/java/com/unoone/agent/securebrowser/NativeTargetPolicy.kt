package com.unoone.agent.securebrowser

/** Native document lifetime and exact-target matching; DOM claims remain untrusted. */
internal class NativeTargetPolicy {
    var epoch: Long = 0L
        private set
    fun revoke() { epoch++ }
    fun isCurrent(expected: Long): Boolean = expected == epoch
    fun matches(expectedEpoch: Long, index: Int, fingerprint: String, freshIndex: Int?, freshFingerprint: String?): Boolean =
        isCurrent(expectedEpoch) && index > 0 && fingerprint.isNotBlank() && index == freshIndex && fingerprint == freshFingerprint
}
