package com.unoone.agent.core.integrations

import kotlinx.serialization.Serializable

/** Wire contract: interface token, UTF-8 JSON request string; reply exception header then JSON string. */
object UnoBridgeContract {
    const val VERSION = 1
    const val ACTION = "com.unoone.agent.bridge.DISCOVER_V1"
    const val DESCRIPTOR = "com.unoone.agent.bridge.IUnoBridge.v1"
    const val DISCOVER_TRANSACTION = 1 // IBinder.FIRST_CALL_TRANSACTION
    const val INVOKE_TRANSACTION = 2
    const val MAX_BYTES = 32_768
}
@Serializable data class BridgeCapability(val name: String, val readOnly: Boolean)
@Serializable data class BridgeDiscovery(val version: Int, val capabilities: List<BridgeCapability>) {
    init { require(version == UnoBridgeContract.VERSION && capabilities.size <= 64)
        require(capabilities.map { it.name }.distinct().size == capabilities.size)
        require(capabilities.all { it.name.matches(Regex("[a-zA-Z0-9_.-]{1,80}")) }) }
}
@Serializable data class BridgeCall(val version: Int = UnoBridgeContract.VERSION, val capability: String,
    val requestId: String, val arguments: Map<String, String>)
@Serializable data class BridgeReply(val version: Int, val requestId: String, val accepted: Boolean, val result: String)

data class IntegrationAvailability(val available: Boolean, val reason: String)
/** Honest optional seam: no alpha SDK reflection, fake discovery, or fallback execution. */
class OptionalAppFunctions {
    val availability = IntegrationAvailability(false, "AppFunctions runtime adapter not installed or qualified")
    fun discover(): List<BridgeCapability> = emptyList()
    fun invoke(): Nothing = error(availability.reason)
}
