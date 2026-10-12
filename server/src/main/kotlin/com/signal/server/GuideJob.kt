package com.signal.server

import com.signal.nas.GuideInputs
import com.signal.nas.NasGuideMeta
import com.signal.nas.NasGuidePaths
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When the nightly run happens (PLAN §5.2 → Jobs → Schedule): a web admin setting, not an env var. */
@Serializable
data class GuideSchedule(val time: String = "03:00", val timeZone: String = "America/Chicago") {
    fun isValid(): Boolean = runCatching { LocalTime.parse(time); ZoneId.of(timeZone) }.isSuccess

    /** The next run after [now], in the named zone, so it follows daylight saving time. */
    fun nextAfter(now: Long): Long {
        val zone = ZoneId.of(timeZone)
        val at = LocalTime.parse(time)
        var next = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone).with(at)
        if (!next.toInstant().isAfter(java.time.Instant.ofEpochMilli(now))) next = next.plusDays(1).with(at)
        return next.toInstant().toEpochMilli()
    }
}

/** What the web admin's guide card shows. */
@Serializable
data class GuideStatus(
    val meta: NasGuideMeta?,
    val running: Boolean,
    val lastRun: JobRun?,
    val schedule: GuideSchedule,
    val nextRunAt: Long?,
    /** The EPG sources the NAS builds from, by name; empty until a paired device sends them. */
    val epgSources: List<String>,
    val inputsFrom: String?,
    val inputsAt: Long?,
)

/**
 * The guide job: the inputs a device sent (stored encrypted, PLAN §5.7), the nightly schedule, and
 * runs on demand. One run at a time; GuideBuilds enforces that too.
 */
class GuideJob(private val companion: Companion, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger("GuideJob")
    private val json = Json { ignoreUnknownKeys = true }
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun schedule(): GuideSchedule = companion.store?.let { store ->
        GuideSchedule(store.setting(SCHEDULE_TIME) ?: "03:00", store.setting(SCHEDULE_ZONE) ?: "America/Chicago").takeIf { it.isValid() }
    } ?: GuideSchedule()

    fun setSchedule(schedule: GuideSchedule) {
        val store = companion.store ?: error("The database isn't connected yet")
        store.putSetting(SCHEDULE_TIME, schedule.time)
        store.putSetting(SCHEDULE_ZONE, schedule.timeZone)
        // Takes effect at once, with no restart (PLAN §5.8).
        wake.trySend(Unit)
    }

    fun inputs(): GuideInputs? {
        val stored = companion.store?.setting(INPUTS) ?: return null
        val cipher = companion.cipher ?: return null
        return runCatching { json.decodeFromString<GuideInputs>(cipher.decrypt(stored)) }
            .onFailure { log.error("Stored guide inputs can't be read (was SIGNAL_SECRET_KEY changed?): ${it.message}") }
            .getOrNull()
    }

    /** Stores what [device] sent. A change starts a build straight away instead of waiting for the night. */
    fun putInputs(inputs: GuideInputs, device: Device) {
        val store = companion.store ?: error("The database isn't connected yet")
        val cipher = companion.cipher ?: error("SIGNAL_SECRET_KEY isn't set")
        val changed = inputs != inputs()
        store.putSetting(INPUTS, cipher.encrypt(json.encodeToString(GuideInputs.serializer(), inputs)))
        store.putSetting(INPUTS_FROM, device.name)
        store.putSetting(INPUTS_AT, System.currentTimeMillis().toString())
        if (changed && inputs.epgSources.any { it.enabled }) {
            log.info("Guide inputs changed (${inputs.epgSources.size} EPG sources from ${device.name}): building")
            runInBackground("inputs changed")
        }
    }

    fun status(): GuideStatus {
        val store = companion.store
        val schedule = schedule()
        return GuideStatus(
            meta = companion.guide.meta,
            running = companion.guide.running,
            lastRun = store?.lastJob(JOB),
            schedule = schedule,
            nextRunAt = schedule.nextAfter(System.currentTimeMillis()),
            epgSources = inputs()?.epgSources?.filter { it.enabled }?.map { it.name }.orEmpty(),
            inputsFrom = store?.setting(INPUTS_FROM),
            inputsAt = store?.setting(INPUTS_AT)?.toLongOrNull(),
        )
    }

    /** Starts a run unless one is running. False when it can't start. */
    fun runInBackground(trigger: String): Boolean {
        if (companion.guide.running) return false
        scope.launch { run(trigger) }
        return true
    }

    suspend fun run(trigger: String) {
        val store = companion.store
        val inputs = inputs()
        if (store == null || inputs == null || inputs.epgSources.none { it.enabled }) {
            val why = when {
                store == null -> "no database"
                inputs == null -> "no guide inputs yet; pair a device"
                else -> "no enabled EPG sources"
            }
            log.info("Guide build ($trigger) skipped: $why")
            return
        }
        val id = store.startJob(JOB, System.currentTimeMillis())
        val result = companion.guide.build(inputs, System.currentTimeMillis())
        val detail = result.fold(
            { m -> "$trigger: ${m.programs} programs, ${m.channels} channels" + m.outcomes.values.mapNotNull { it.error }.joinToString("") { "; failed: $it" } },
            { "$trigger: ${it.message}" },
        )
        store.finishJob(id, System.currentTimeMillis(), if (result.isSuccess) "ok" else "failed", detail)
    }

    /** The nightly loop: sleeps until the next run, or until the schedule changes. */
    fun start() = scope.launch {
        while (true) {
            val wait = schedule().nextAfter(System.currentTimeMillis()) - System.currentTimeMillis()
            if (withTimeoutOrNull(wait.coerceAtLeast(1)) { wake.receive() } == null) run("nightly")
        }
    }

    companion object Keys {
        const val JOB = "guide_build"
        const val INPUTS = "guide_inputs"
        const val INPUTS_FROM = "guide_inputs_from"
        const val INPUTS_AT = "guide_inputs_at"
        const val SCHEDULE_TIME = "guide_build_time"
        const val SCHEDULE_ZONE = "guide_time_zone"
    }
}

fun Route.guideDeviceRoutes(companion: Companion) {
    put(NasGuidePaths.INPUTS) {
        val device = call.device(companion) ?: return@put
        if (companion.cipher == null) {
            call.respondText("SIGNAL_SECRET_KEY isn't set on the companion, so it can't store credentials.", status = HttpStatusCode.ServiceUnavailable)
            return@put
        }
        val inputs = runCatching { call.receive<GuideInputs>() }.getOrNull()
        if (inputs == null) {
            call.respondText("Bad guide inputs", status = HttpStatusCode.BadRequest)
            return@put
        }
        companion.guideJob.putInputs(inputs, device)
        call.respond(HttpStatusCode.NoContent)
    }

    get(NasGuidePaths.META) {
        call.device(companion) ?: return@get
        val meta = companion.guide.meta
        if (meta == null) call.respondText("No guide built yet", status = HttpStatusCode.NotFound) else call.respond(meta)
    }

    get(NasGuidePaths.FILE) {
        call.device(companion) ?: return@get
        val file = companion.guide.file()
        if (file == null) call.respondText("No guide built yet", status = HttpStatusCode.NotFound) else call.respondFile(file)
    }
}

fun Route.guideAdminRoutes(companion: Companion) {
    get("/guide") { call.respond(companion.guideJob.status()) }

    post("/guide/build") {
        when {
            companion.store == null -> call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
            companion.guideJob.inputs()?.epgSources?.any { it.enabled } != true -> call.respondText("No EPG sources yet: a paired device sends them.", status = HttpStatusCode.Conflict)
            !companion.guideJob.runInBackground("run now") -> call.respondText("A guide build is already running.", status = HttpStatusCode.Conflict)
            else -> call.respond(HttpStatusCode.Accepted)
        }
    }

    put("/guide/schedule") {
        val schedule = runCatching { call.receive<GuideSchedule>() }.getOrNull()
        when {
            schedule == null || !schedule.isValid() -> call.respondText("Enter a time like 03:00 and a time zone like America/Chicago.", status = HttpStatusCode.BadRequest)
            companion.store == null -> call.respondText("The database isn't connected yet.", status = HttpStatusCode.ServiceUnavailable)
            else -> {
                companion.guideJob.setSchedule(schedule)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
