package com.signal.server

import kotlin.test.Test
import kotlin.test.assertEquals

class UnresolvedFailuresTest {
    private val day = 24 * 60 * 60_000L
    private val now = 30 * day
    private fun run(id: Long, job: String, at: Long, result: String?) = JobRun(id, job, at, at + 1, result, null)

    @Test fun aLaterSuccessOfTheSameJobClearsAFailure() {
        val jobs = listOf(
            run(4, "guide_build", now - 1 * day, "ok"),
            run(3, "source_refresh", now - 1 * day, "failed"),
            run(2, "guide_build", now - 3 * day, "failed"),
            run(1, "source_refresh", now - 3 * day, "ok"),
        )
        assertEquals(listOf(3L), unresolvedFailures(jobs, now).map { it.id })
    }

    @Test fun failuresOlderThanAWeekAndUnfinishedRunsAreLeftOut() {
        val jobs = listOf(
            run(3, "vod_enrich", now - 1 * day, null),
            run(2, "guide_build", now - 8 * day, "failed"),
            run(1, "guide_build", now - 2 * day, "failed"),
        )
        assertEquals(listOf(1L), unresolvedFailures(jobs, now).map { it.id })
    }
}
