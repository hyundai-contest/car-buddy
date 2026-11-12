package ai.carbuddy.story

import ai.carbuddy.card.Back
import ai.carbuddy.card.Card
import ai.carbuddy.core.model.SeatPos

/**
 * One buddy line when the driver gets out (NEXT_DESIGN 3.1 배웅).
 * @param closeSeat the window the [닫기] button closes, null when the line has no action.
 * @param speakText what the buddy says aloud: only the thing to mind, never the story (no voice when nothing to mind).
 */
data class FarewellLine(val text: String, val closeSeat: SeatPos?, val speakText: String?)

/**
 * NEXT_DESIGN 1장 원칙: the exit check is the buddy's manner, not a feature. Story first, then at
 * most one thing to mind (window > trunk > lights), then the fuel reminder. A routine trip with
 * nothing to mind gets silence. Copy keys `farewell_*` (CLAUDE.md 규칙 7).
 */
class FarewellComposer(private val copy: CopyBook) {
    fun compose(card: Card?, exit: Back?, routine: Boolean): FarewellLine? {
        val story = card?.takeIf { !routine }?.visibleEpisodes?.maxByOrNull { it.score }
            ?.short?.takeIf { it.isNotBlank() }?.let { copy.get("farewell_story", "short" to it) }
        val seat = exit?.windowsOpen?.firstNotNullOfOrNull { runCatching { SeatPos.valueOf(it) }.getOrNull() }
        val mind = when {
            seat != null -> copy.get("farewell_window", "seat" to copy.get("seat_${seat.name}"))
            exit?.trunkOpen == true -> copy.get("farewell_trunk")
            exit?.lightsOn == true -> copy.get("farewell_lights")
            else -> null
        }
        val fuel = if (exit?.fuelLow == true) copy.get("farewell_fuel") else null
        val text = listOfNotNull(story, mind, fuel).joinToString(" ").takeIf { it.isNotEmpty() } ?: return null
        return FarewellLine(text, seat, mind)
    }
}
