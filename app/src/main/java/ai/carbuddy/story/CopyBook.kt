package ai.carbuddy.story

import ai.carbuddy.core.util.Korean
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * User-facing copy, keyed (CLAUDE.md 규칙 7). Source of truth: res/values/strings_copy.xml.
 * Keys may be written with dots (SPEC "home.idle") — they map to "home_idle" resources.
 *
 * Template syntax: `{name}` inserts a variable, `{name|로}` appends the right particle form
 * (을/를, 이/가, 은/는, 과/와, 으로/로, 이었는데/였는데, 이야/야) after the value.
 */
abstract class CopyBook {
    /** Raw template, or null when the key is missing. */
    protected abstract fun lookup(resName: String): String?

    fun has(key: String) = lookup(resName(key)) != null

    fun raw(key: String): String = lookup(resName(key)) ?: key

    fun get(key: String, vars: Map<String, Any?> = emptyMap()): String = render(raw(key), vars)

    fun get(key: String, vararg vars: Pair<String, Any?>): String = get(key, vars.toMap())

    /** First existing key wins (for per-type keys with a generic fallback). */
    fun first(keys: List<String>, vars: Map<String, Any?>): String =
        keys.firstOrNull { has(it) }?.let { get(it, vars) } ?: keys.last()

    companion object {
        fun resName(key: String) = key.replace('.', '_')

        private val TOKEN = Regex("""\{([A-Za-z0-9_]+)(?:\|([^}]+))?\}""")

        fun render(template: String, vars: Map<String, Any?>): String = TOKEN.replace(template) { m ->
            val name = m.groupValues[1]
            val josa = m.groupValues[2]
            val value = formatValue(vars[name])
            if (josa.isEmpty()) value else value + Korean.josa(value, josa)
        }

        fun formatValue(v: Any?): String = when (v) {
            null -> ""
            is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
            is Float -> if (v % 1f == 0f) v.toLong().toString() else "%.1f".format(v)
            else -> v.toString()
        }
    }
}

/** Parses an Android string-resource XML (works in JVM unit tests and on device). */
class XmlCopyBook(input: InputStream) : CopyBook() {
    private val map: Map<String, String> = input.use { parse(it) }
    override fun lookup(resName: String) = map[resName]

    private companion object {
        fun parse(input: InputStream): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i ->
                val el = nodes.item(i) as Element
                el.getAttribute("name") to unescape(el.textContent)
            }
        }

        /** Android resource unescaping (subset): \' \" \n \@ \? and surrounding quotes. */
        fun unescape(s: String): String {
            var t = s.trim()
            if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) t = t.substring(1, t.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < t.length) {
                val c = t[i]
                if (c == '\\' && i + 1 < t.length) {
                    when (val n = t[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(n) }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}

/** Fixed map, for tests. */
class MapCopyBook(private val map: Map<String, String>) : CopyBook() {
    override fun lookup(resName: String) = map[resName]
}
