package ai.carbuddy.story

import android.annotation.SuppressLint
import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** CopyBook backed by Android string resources (strings_copy.xml). */
class ResCopyBook(context: Context) : CopyBook() {
    private val res = context.applicationContext.resources
    private val pkg = context.applicationContext.packageName
    private val cache = ConcurrentHashMap<String, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    @SuppressLint("DiscouragedApi") // keys are data-driven (episode types); ids resolved once and cached
    override fun lookup(resName: String): String? {
        cache[resName]?.let { return it }
        if (resName in missing) return null
        val id = res.getIdentifier(resName, "string", pkg)
        if (id == 0) { missing += resName; return null }
        return res.getString(id).also { cache[resName] = it }
    }
}
