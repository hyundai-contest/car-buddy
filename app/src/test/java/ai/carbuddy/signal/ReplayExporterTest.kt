package ai.carbuddy.signal

import ai.carbuddy.TestSupport
import ai.carbuddy.signal.adapter.replay.ReplayAdapter
import ai.carbuddy.signal.adapter.replay.ReplayExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A recorded trip exported to JSONL and replayed again gives the same card (round trip). */
class ReplayExporterTest {
    @Test fun scenarioA_exportedAndReplayed_sameEpisodesAndReceipt() {
        val first = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"))
        val trip = first.store.trips().single()
        val jsonl = ReplayExporter.export(first.store, trip.id)
        assertNotNull(jsonl)
        val lines = jsonl!!.lines().filter { it.isNotBlank() }
        assertTrue(lines.first().contains("\"Meta\""))
        assertTrue("events exported: ${lines.size}", lines.size > 1000)

        val again = TestSupport.runWithPowerCycles(ReplayAdapter.timeline(SignalJson.parseLog(jsonl), fallbackStart = 0))
        val trip2 = again.store.trips().single { !it.discarded }
        assertEquals(first.store.episodes(trip.id).map { it.type }.sorted(), again.store.episodes(trip2.id).map { it.type }.sorted())
        val r1 = first.pipeline.card(trip.id)!!.receipt
        val r2 = again.pipeline.card(trip2.id)!!.receipt
        assertEquals(r1.mode, r2.mode)
        assertEquals(r1.totalMin, r2.totalMin)
    }

    @Test fun unknownTrip_isNull() {
        val run = TestSupport.runWithPowerCycles(TestSupport.replay("scenario_a.jsonl"))
        assertEquals(null, ReplayExporter.export(run.store, "nope"))
    }
}
