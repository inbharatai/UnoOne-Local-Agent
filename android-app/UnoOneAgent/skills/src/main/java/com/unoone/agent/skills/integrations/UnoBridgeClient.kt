package com.unoone.agent.skills.integrations

import android.content.*
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.*
import com.unoone.agent.core.device.DeviceActionCodec
import com.unoone.agent.core.integrations.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.coroutines.resume

/** Explicit package + current signer pins from native configuration, NEVER model-supplied trust. */
class UnoBridgeClient(context: Context, private val packageName: String,
    private val signerSha256: Set<String>, private val nativeAllowlist: Set<String>) : AutoCloseable {
    private val context = context.applicationContext
    @Volatile private var binder: IBinder? = null
    @Volatile private var discovery: BridgeDiscovery? = null
    private var connection: ServiceConnection? = null
    private val death = IBinder.DeathRecipient { binder = null; discovery = null }
    @Suppress("DEPRECATION")
    private fun checkTrust() {
        require(signerSha256.isNotEmpty()) { "No pinned trust" }
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = requireNotNull(info.signingInfo).apkContentsSigners
        require(signers.isNotEmpty() && signers.all { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } in signerSha256
        }) { "Untrusted current signer" }
    }
    @Suppress("DEPRECATION")
    suspend fun connect(): BridgeDiscovery {
        close(); checkTrust()
        val pm = context.packageManager
        val service = pm.queryIntentServices(Intent(UnoBridgeContract.ACTION).setPackage(packageName), 0)
            .mapNotNull { it.serviceInfo }.filter { it.packageName == packageName && it.enabled && it.exported }.singleOrNull()
            ?: error("Trusted bridge absent or ambiguous under package visibility")
        val permission = requireNotNull(service.permission) { "Bridge must require signature permission" }
        val protection = pm.getPermissionInfo(permission, 0).protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        require(protection == PermissionInfo.PROTECTION_SIGNATURE) { "Bridge service must not be broadly exported" }
        try {
            withTimeout(3000) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, service: IBinder) {
                            if (!continuation.isActive) return
                            try {
                                checkTrust(); require(service.interfaceDescriptor == UnoBridgeContract.DESCRIPTOR)
                                service.linkToDeath(death, 0); binder = service; continuation.resume(Unit)
                            } catch (e: Exception) { continuation.cancel(e) }
                        }
                        override fun onServiceDisconnected(name: ComponentName) { death.binderDied() }
                        override fun onBindingDied(name: ComponentName) { death.binderDied() }
                        override fun onNullBinding(name: ComponentName) { continuation.cancel(IllegalStateException("Null bridge")) }
                    }
                    connection = conn
                    continuation.invokeOnCancellation { close() }
                    if (!context.bindService(Intent(UnoBridgeContract.ACTION).setComponent(ComponentName(packageName, service.name)), conn, Context.BIND_AUTO_CREATE)) {
                        continuation.cancel(IllegalStateException("Bridge bind refused"))
                    }
                }
            }
            return withContext(Dispatchers.IO) {
                val result = DeviceActionCodec.json.decodeFromString<BridgeDiscovery>(transact(UnoBridgeContract.DISCOVER_TRANSACTION, "{\"version\":1}"))
                require(result.version == UnoBridgeContract.VERSION)
                discovery = result
                result
            }
        } catch (e: Exception) { close(); throw e }
    }
    /** Native authorizer must apply user consent, epoch/master switch and any per-call confirmation. */
    suspend fun invoke(call: BridgeCall, nativeAuthorize: (BridgeCall) -> Boolean): BridgeReply = withContext(Dispatchers.IO) {
        try {
            checkTrust()
            require(call.version == UnoBridgeContract.VERSION && call.requestId.length in 1..80)
            require(call.capability in nativeAllowlist && discovery?.capabilities?.any { it.name == call.capability } == true)
            require(nativeAuthorize(call)) { "Native authorization denied" }
            val reply = DeviceActionCodec.json.decodeFromString<BridgeReply>(transact(UnoBridgeContract.INVOKE_TRANSACTION, DeviceActionCodec.json.encodeToString(call)))
            require(reply.version == UnoBridgeContract.VERSION && reply.requestId == call.requestId)
            reply // accepted is dispatch acknowledgement, NOT native outcome proof
        } catch (e: Exception) { close(); throw e } // No retries: uncertain side effects must not be duplicated.
    }
    private fun transact(code: Int, json: String): String {
        require(json.toByteArray(Charsets.UTF_8).size <= UnoBridgeContract.MAX_BYTES)
        val remote = requireNotNull(binder) { "Bridge unavailable/dead" }
        require(remote.isBinderAlive)
        val input = Parcel.obtain(); val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(UnoBridgeContract.DESCRIPTOR); input.writeString(json)
            require(input.dataSize() <= UnoBridgeContract.MAX_BYTES)
            require(remote.transact(code, input, output, 0)) { "Unsupported bridge transaction" }
            require(output.dataSize() <= UnoBridgeContract.MAX_BYTES) { "Oversized bridge reply" }
            output.readException()
            val result = requireNotNull(output.readString())
            require(result.toByteArray(Charsets.UTF_8).size <= UnoBridgeContract.MAX_BYTES && output.dataAvail() == 0)
            require(remote.isBinderAlive) { "Bridge died during call; outcome uncertain" }
            return result
        } finally { input.recycle(); output.recycle() }
    }
    override fun close() {
        binder?.let { runCatching { it.unlinkToDeath(death, 0) } }; binder = null; discovery = null
        connection?.let { runCatching { context.unbindService(it) } }; connection = null
    }
}
