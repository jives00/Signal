package com.signal.server

import com.signal.nas.PairRequest
import com.signal.nas.PairStarted
import com.signal.nas.PairState
import com.signal.nas.PairStatus
import java.security.SecureRandom
import java.util.Base64

/**
 * Pairing in progress (PLAN §5.7): a device asks, shows a 6-digit code, and someone types that code
 * into the web admin. Held in memory only: they last 10 minutes, and after a restart the device
 * simply asks again.
 */
class Pairings(private val now: () -> Long = System::currentTimeMillis) {
    private class Pending(val request: PairRequest, val code: String, val expiresAt: Long) {
        /** Set on approval, until the device collects it. */
        var apiKey: String? = null
        var deviceName: String? = null
    }

    private val random = SecureRandom()
    private val pending = HashMap<String, Pending>()

    @Synchronized
    fun start(request: PairRequest): PairStarted? {
        prune()
        // A device asking again replaces its own earlier code.
        pending.entries.removeIf { it.value.request.deviceId == request.deviceId && it.value.apiKey == null }
        if (pending.size >= MAX_PENDING) return null
        val taken = pending.values.map { it.code }.toSet()
        val code = generateSequence { "%06d".format(random.nextInt(1_000_000)) }.first { it !in taken }
        val id = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        pending[id] = Pending(request, code, now() + TTL_MS)
        return PairStarted(id, code, (TTL_MS / 1000).toInt())
    }

    /** What the device sees when it polls. The key is handed over once, then forgotten. */
    @Synchronized
    fun status(pairingId: String): PairStatus {
        prune()
        val p = pending[pairingId] ?: return PairStatus(PairState.EXPIRED)
        val key = p.apiKey ?: return PairStatus(PairState.PENDING)
        pending.remove(pairingId)
        return PairStatus(PairState.APPROVED, apiKey = key, deviceName = p.deviceName)
    }

    /**
     * The web admin's Approve device: finds the pairing showing [code], hands it a new key, and
     * saves the device. Null when no device is showing that code.
     */
    @Synchronized
    fun approve(code: String, store: DeviceStore): Device? {
        prune()
        val p = pending.values.firstOrNull { it.code == code.trim() && it.apiKey == null } ?: return null
        val key = DeviceKeys.newKey()
        val at = now()
        val r = p.request
        val device = Device(r.deviceId, r.name.take(100), r.model.take(100), r.appVersion.take(30), pairedAt = at, lastSeenAt = at)
        store.save(device, DeviceKeys.hash(key))
        p.apiKey = key
        p.deviceName = device.name
        return device
    }

    private fun prune() {
        val t = now()
        pending.entries.removeIf { it.value.expiresAt <= t }
    }

    companion object {
        const val TTL_MS = 10 * 60_000L
        /** Anything on the home network can ask, so cap how many codes can be waiting. */
        const val MAX_PENDING = 20
    }
}
