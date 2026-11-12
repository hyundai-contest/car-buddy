package ai.carbuddy.ui.screens

import ai.carbuddy.card.Receipt
import ai.carbuddy.ui.components.ReceiptTexts
import ai.carbuddy.ui.t
import androidx.compose.runtime.Composable

/** CopyBook texts for a receipt (V4, card, awards side panel). */
@Composable
fun receiptTexts(r: Receipt, long: Boolean = false): ReceiptTexts {
    val s = r.summary
    val plan = if (r.etaLabel != null && r.arrivedLabel != null) t(if (long) "receipt_plan_long" else "receipt_plan", "eta" to r.etaLabel, "arrived" to r.arrivedLabel) else null
    val summary = buildString {
        append(
            if (s.participants <= 1 && s.episodeCount == 0) t("receipt_summary_alone", "km" to s.distanceKm, "drive" to s.drivingMin)
            else t("receipt_summary", "km" to s.distanceKm, "drive" to s.drivingMin, "rest" to s.restMin, "people" to s.participants, "n" to s.episodeCount),
        )
        s.tallies.forEach { append(" · "); append(it) }
    }
    return ReceiptTexts(t("receipt_title"), plan, t("receipt_section_summary"), t("receipt_section_settle"), summary)
}
