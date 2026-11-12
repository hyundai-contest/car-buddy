package ai.carbuddy.story

import ai.carbuddy.TestSupport
import ai.carbuddy.core.model.EpisodeType
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every dex episode has all the copy the card, ceremony, recap and dex need (CLAUDE.md 규칙 7). */
class CopyCoverageTest {
    private val copy = TestSupport.copy()

    @Test fun everyDetectedType_hasItsCopy() {
        // types with a detector (6 Must + 8 PT); BORDER_CROSS is 'Could' and only has its dex cell
        val detected = (ai.carbuddy.episode.EpisodeEngine.mustDetectors(null) + ai.carbuddy.episode.EpisodeEngine.ptDetectors { _, _ -> null }).map { it.type }
        val missing = detected.flatMap { t ->
            listOf("name_", "title_", "tl_", "short_", "award_", "award_desc_", "recap_a_", "recap_b_", "buddy_", "hint_")
                .map { it + t.name }.filterNot(copy::has)
        } + EpisodeType.entries.filter { it.dex }.flatMap { t -> listOf("name_${t.name}", "hint_${t.name}").filterNot(copy::has) }
        assertTrue("missing copy keys: $missing", missing.isEmpty())
    }

    @Test fun everyItem_hasNameAndRule() {
        val items = listOf("COMPASS", "MAP", "RAINCOAT", "HANDY_FAN", "CUSHION", "WAVE", "STARLIGHT", "FRIEND_BADGE")
        val missing = items.flatMap { listOf("item_$it", "item_rule_$it") }.filterNot(copy::has)
        assertTrue("missing item keys: $missing", missing.isEmpty())
    }
}
