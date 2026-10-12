package com.signal.nas

import com.signal.data.Log
import com.signal.data.Settings
import com.signal.platform.AppInfo
import com.signal.platform.SecretBox
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * The app's link to the NAS companion (PLAN §5.7): which of its two addresses answers, pairing, and
 * the device's key. The app works without it; everything here fails fast and quietly.
 */
class NasConnection(
    private val settings: Settings,
    private val http: HttpClient,
    private val secretBox: SecretBox,
    private val appInfo: AppInfo,
    private val log: Log,
    private val pollMs: Long = POLL_MS,
) {
    enum class Via(val label: String) { HOME("home network"), TAILSCALE("Tailscale") }

    sealed interface Status {
        data object NotPaired : Status
        data object Checking : Status
        data class Connected(val deviceName: String, val via: Via, val address: String) : Status
        /** Paired, but neither address answers: the app carries on standalone. */
        data object Unreachable : Status
        /** The web admin forgot this device; it has to pair again. */
        data object Removed : Status
    }

    sealed interface Pairing {
        data object Searching : Pairing
        data class ShowCode(val code: String, val via: Via, val address: String) : Pairing
        data class Paired(val deviceName: String) : Pairing
        data class Failed(val message: String) : Pairing
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _status = MutableStateFlow(if (isPaired) Status.Checking else Status.NotPaired)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The address that answered last, tried first next time. */
    private var active: Pair<Via, String>? = null

    val homeAddress: String get() = settings.raw(Settings.Key.NAS_HOME_ADDRESS) ?: DEFAULT_HOME
    val tailscaleAddress: String get() = settings.raw(Settings.Key.NAS_TAILSCALE_ADDRESS) ?: DEFAULT_TAILSCALE

    fun setAddresses(home: String, tailscale: String) {
        settings.put(Settings.Key.NAS_HOME_ADDRESS, home.trim().takeIf { it.isNotEmpty() && it != DEFAULT_HOME })
        settings.put(Settings.Key.NAS_TAILSCALE_ADDRESS, tailscale.trim().takeIf { it.isNotEmpty() && it != DEFAULT_TAILSCALE })
        active = null
    }

    val isPaired: Boolean get() = settings.raw(Settings.Key.NAS_API_KEY) != null

    private val apiKey: String? get() = settings.raw(Settings.Key.NAS_API_KEY)?.let { runCatching { secretBox.decrypt(it) }.getOrNull() }

    private fun addresses(): List<Pair<Via, String>> =
        listOf(Via.HOME to homeAddress, Via.TAILSCALE to tailscaleAddress).filter { it.second.isNotBlank() }.map { it.first to baseUrl(it.second) }

    /**
     * Asks /health on both addresses at once and keeps the first that answers (PLAN §5.7). A
     * companion whose database is down still answers, so it still counts as found.
     */
    suspend fun findAddress(): Pair<Via, String>? {
        val found = channelFlow {
            for (candidate in addresses()) launch {
                val answered = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    runCatching { json.decodeFromString<NasHealth>(http.get(candidate.second + NasPaths.HEALTH).bodyAsText()) }.isSuccess
                } == true
                if (answered) send(candidate)
            }
        }.firstOrNull()
        active = found
        return found
    }

    /**
     * One request to the companion: the address that worked last, and on a failure the search again
     * and one retry (PLAN §5.7: "it re-checks inside the request path"). Null when the NAS is unreachable.
     */
    private suspend fun call(timeoutMs: Long = REQUEST_TIMEOUT_MS, block: suspend (base: String) -> HttpResponse): HttpResponse? {
        repeat(2) { attempt ->
            val target = (if (attempt == 0) active else null) ?: findAddress() ?: return null
            try {
                return withTimeout(timeoutMs) { block(target.second) }
            } catch (e: CancellationException) {
                // withTimeout's own cancellation is a failed attempt; the caller's is not.
                if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            } catch (_: Exception) {
            }
            active = null
        }
        return null
    }

    private fun HttpRequestBuilder.asDevice() {
        apiKey?.let { header(DEVICE_KEY_HEADER, bearer(it)) }
        header(APP_VERSION_HEADER, appInfo.versionName)
    }

    /** On launch and on returning to the foreground: is the key still good, and which address? */
    suspend fun check() {
        if (!isPaired) { _status.value = Status.NotPaired; return }
        if (_status.value !is Status.Connected) _status.value = Status.Checking
        val response = call { base -> http.get(base + NasPaths.ME) { asDevice() } }
        _status.value = when {
            response == null -> Status.Unreachable
            response.status == HttpStatusCode.Unauthorized -> {
                log.warn("NAS: this device was removed in the web admin")
                settings.put(Settings.Key.NAS_API_KEY, null)
                Status.Removed
            }
            response.status.isSuccess() -> runCatching { json.decodeFromString<DeviceInfo>(response.bodyAsText()) }.getOrNull()?.let { me ->
                settings.put(Settings.Key.NAS_DEVICE_NAME, me.name)
                val (via, address) = active ?: (Via.HOME to "")
                Status.Connected(me.name, via, address)
            } ?: Status.Unreachable
            // Answering, but not ready (the database is still connecting): keep the key, try later.
            else -> Status.Unreachable
        }
    }

    /**
     * Pairing (PLAN §5.7): shows a 6-digit code until someone approves it in the web admin, then
     * keeps the key. An expired code is replaced by a new one. It runs until the collector stops, for
     * example when the screen showing the code closes.
     */
    fun pair(): Flow<Pairing> = flow {
        emit(Pairing.Searching)
        while (true) {
            val response = call { base ->
                http.post(base + NasPaths.PAIR) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(PairRequest.serializer(), PairRequest(settings.deviceId, appInfo.deviceModel, appInfo.deviceModel, appInfo.versionName)))
                }
            }
            if (response == null) {
                emit(Pairing.Failed("Couldn't reach the NAS at $homeAddress or $tailscaleAddress."))
                return@flow
            }
            if (!response.status.isSuccess()) {
                emit(Pairing.Failed(response.bodyAsText().take(200).ifBlank { "The NAS refused: ${response.status}" }))
                return@flow
            }
            val started = json.decodeFromString<PairStarted>(response.bodyAsText())
            emit(Pairing.ShowCode(started.code, active?.first ?: Via.HOME, active?.second.orEmpty()))
            log.info("NAS: pairing, code shown")
            while (true) {
                delay(pollMs)
                // A failed poll (Wi-Fi blip) just waits for the next one.
                val polled = call { base -> http.get(base + NasPaths.pairStatus(started.pairingId)) } ?: continue
                val status = runCatching { json.decodeFromString<PairStatus>(polled.bodyAsText()) }.getOrNull() ?: continue
                when (status.state) {
                    PairState.PENDING -> Unit
                    PairState.EXPIRED -> break
                    PairState.APPROVED -> {
                        val key = status.apiKey ?: break
                        settings.put(Settings.Key.NAS_API_KEY, secretBox.encrypt(key))
                        val name = status.deviceName ?: appInfo.deviceModel
                        settings.put(Settings.Key.NAS_DEVICE_NAME, name)
                        log.info("NAS: paired as $name")
                        val (via, address) = active ?: (Via.HOME to "")
                        _status.value = Status.Connected(name, via, address)
                        emit(Pairing.Paired(name))
                        return@flow
                    }
                }
            }
        }
    }

    /** Forgets the key on this device only. The web admin still lists it until forgotten there too. */
    fun unpair() {
        settings.put(Settings.Key.NAS_API_KEY, null)
        settings.put(Settings.Key.NAS_DEVICE_NAME, null)
        _status.value = Status.NotPaired
    }

    companion object {
        /** PLAN §5.7: the NAS's reserved LAN address, and its Tailscale name. */
        const val DEFAULT_HOME = "192.168.0.105:3013"
        const val DEFAULT_TAILSCALE = "synology:3013"
        const val PROBE_TIMEOUT_MS = 3_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val POLL_MS = 2_000L

        /** "192.168.0.105:3013" → "http://192.168.0.105:3013"; a scheme the user typed is kept. */
        fun baseUrl(address: String): String {
            val a = address.trim().trimEnd('/')
            return if ("://" in a) a else "http://$a"
        }
    }
}
