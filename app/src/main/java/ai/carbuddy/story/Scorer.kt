package ai.carbuddy.story

import ai.carbuddy.core.model.EpisodeType
import ai.carbuddy.data.EpisodeEntity
import ai.carbuddy.episode.Params

/** Episodes of one type merged into one (SPEC 7장: params summed). */
data class StoryEpisode(
    val type: EpisodeType,
    val ids: List<String>,
    val startT: Long,
    val endT: Long,
    val params: Map<String, Any>,
    val lostMinutes: Int?,
    val score: Double = 0.0,
    val hidden: Boolean = false,
) {
    val id: String get() = ids.first()
}

/** SPEC 7장 scoring: base[type] × rarity(times this type appeared on the driver's past cards). */
object Scorer {
    private val SUMMED = setOf("count", "minutes", "highMinutes")

    fun rarity(pastCount: Int): Double = when {
        pastCount <= 0 -> 1.5
        pastCount <= 4 -> 1.0
        else -> 0.7
    }

    fun score(type: EpisodeType, history: Map<EpisodeType, Int>): Double = type.base * rarity(history[type] ?: 0)

    /** Ranking order: score desc, then catalogue priority (EpisodeType declaration order). */
    val byScore: Comparator<StoryEpisode> = compareByDescending<StoryEpisode> { it.score }.thenBy { it.type.ordinal }

    fun merge(episodes: List<EpisodeEntity>, history: Map<EpisodeType, Int>): List<StoryEpisode> =
        episodes.groupBy { EpisodeType.valueOf(it.type) }.map { (type, list) ->
            val sorted = list.sortedBy { it.startT }
            val params = linkedMapOf<String, Any>()
            for (e in sorted) for ((k, v) in Params.decode(e.paramsJson)) {
                val old = params[k]
                params[k] = when {
                    old == null -> v
                    k in SUMMED && old is Number && v is Number -> old.toInt() + v.toInt()
                    old is Boolean && v is Boolean -> old || v
                    else -> old
                }
            }
            val lost = sorted.mapNotNull { it.lostMinutes }.takeIf { it.isNotEmpty() }?.sum()
            StoryEpisode(
                type = type, ids = sorted.map { it.id }, startT = sorted.first().startT, endT = sorted.maxOf { it.endT },
                params = params, lostMinutes = lost, score = score(type, history), hidden = sorted.all { it.hidden },
            )
        }
}
