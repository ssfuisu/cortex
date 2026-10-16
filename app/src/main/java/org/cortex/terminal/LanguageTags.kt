package org.cortex.terminal

import java.util.Locale

internal object LanguageTags {
    /** Value meaning "follow the system language". */
    const val SYSTEM = "system"

    /**
     * Maps a BCP-47 tag reported by the system (`in`, `iw`, `zh-Hant-TW`, `pt-PT`, `en-GB` ...) to
     * one of the languages the app offers, or [SYSTEM] if none fits.
     */
    fun match(requested: String?, available: List<String>): String {
        if (requested.isNullOrEmpty()) return SYSTEM
        val parts = requested.replace('_', '-').split('-')
        val lang = when (val l = parts[0].lowercase(Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "tl" -> "fil"
            "no" -> "nb"
            else -> l
        }
        val rest = parts.drop(1)
        val region = rest.firstOrNull { it.length == 2 || it.length == 3 && it.all(Char::isDigit) }
            ?.uppercase(Locale.ROOT)
        val script = rest.firstOrNull { it.length == 4 }

        val candidates = ArrayList<String>()
        if (region != null) candidates.add("$lang-$region")
        if (lang == "zh") {
            val traditional = script.equals("Hant", ignoreCase = true) || region in setOf("TW", "HK", "MO")
            candidates.add(if (traditional) "zh-TW" else "zh-CN")
        }
        if (lang == "pt") candidates.add("pt-BR")
        candidates.add(lang)

        for (c in candidates) {
            available.firstOrNull { it.equals(c, ignoreCase = true) }?.let { return it }
        }
        return available.firstOrNull { it.substringBefore('-').equals(lang, ignoreCase = true) } ?: SYSTEM
    }
}
