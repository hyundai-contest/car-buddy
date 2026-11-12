package ai.carbuddy.core.util

/** Korean particle (조사) and number helpers for CopyBook templates. */
object Korean {
    private const val HANGUL_BASE = 0xAC00
    private const val HANGUL_LAST = 0xD7A3

    /** Final consonant index of the last syllable (0 = none), or null if not Hangul. */
    private fun jongseong(word: String): Int? {
        val c = word.trimEnd().lastOrNull { !it.isWhitespace() && it !in ")]\"'”’" } ?: return null
        if (c.code in HANGUL_BASE..HANGUL_LAST) return (c.code - HANGUL_BASE) % 28
        if (c.isDigit()) return digitJong[c.digitToInt()]
        return null
    }

    // 0 영, 1 일, 2 이, 3 삼, 4 사, 5 오, 6 육, 7 칠, 8 팔, 9 구 — final consonant (8 = ㄹ)
    private val digitJong = intArrayOf(21, 8, 0, 16, 0, 0, 1, 8, 8, 0)

    fun hasBatchim(word: String): Boolean = (jongseong(word) ?: 0) != 0

    /**
     * Picks the right particle form. [particle] may be either form: 을/를, 이/가, 은/는, 과/와,
     * 으로/로, 이었는데/였는데, 이야/야, 이랑/랑.
     */
    fun josa(word: String, particle: String): String {
        val j = jongseong(word)
        val has = j != null && j != 0
        return when (particle) {
            "을", "를" -> if (has) "을" else "를"
            "이", "가" -> if (has) "이" else "가"
            "은", "는" -> if (has) "은" else "는"
            "과", "와" -> if (has) "과" else "와"
            "으로", "로" -> if (has && j != 8) "으로" else "로"
            "이었는데", "였는데" -> if (has) "이었는데" else "였는데"
            "이야", "야" -> if (has) "이야" else "야"
            "이랑", "랑" -> if (has) "이랑" else "랑"
            else -> particle
        }
    }

    private val native = listOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")

    /** Native counting word for 1..10 ("세 번"), digits otherwise. */
    fun countWord(n: Int): String = if (n in 1..10) native[n] else n.toString()

    private val days = listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")

    /** SPEC 7장 dayWord: weekend → "주말", else the weekday name. [isoDay] 1=Mon … 7=Sun. */
    fun dayWord(isoDay: Int): String = if (isoDay >= 6) "주말" else days[isoDay - 1]

    fun dayShort(isoDay: Int): String = days[isoDay - 1].take(1)
}
