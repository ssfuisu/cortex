package org.cortex.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageTagsTest {

    private val available = listOf(
        "system", "en", "zh-CN", "zh-TW", "pt-BR", "es", "fr", "id", "he", "fil", "nb", "sr", "de", "tr"
    )

    private fun match(tag: String?) = LanguageTags.match(tag, available)

    @Test
    fun exactTagsAndPlainLanguages() {
        assertEquals("tr", match("tr"))
        assertEquals("de", match("de-AT"))
        assertEquals("en", match("en-GB"))
        assertEquals("pt-BR", match("pt-BR"))
    }

    @Test
    fun legacyAndRenamedLanguageCodes() {
        assertEquals("id", match("in"))
        assertEquals("id", match("in-ID"))
        assertEquals("he", match("iw"))
        assertEquals("fil", match("tl"))
        assertEquals("nb", match("no"))
        assertEquals("nb", match("no-NO"))
    }

    @Test
    fun chineseScriptsAndRegions() {
        assertEquals("zh-CN", match("zh"))
        assertEquals("zh-CN", match("zh-Hans-CN"))
        assertEquals("zh-TW", match("zh-Hant-TW"))
        assertEquals("zh-TW", match("zh-HK"))
        assertEquals("zh-TW", match("zh-Hant"))
    }

    @Test
    fun portugueseVariantsShareTheBrazilianTranslation() {
        assertEquals("pt-BR", match("pt-PT"))
        assertEquals("pt-BR", match("pt"))
    }

    @Test
    fun scriptSubtagsAreIgnoredWhenThereIsNoSpecialCase() {
        assertEquals("sr", match("sr-Latn-RS"))
    }

    @Test
    fun unsupportedOrMissingFallsBackToSystem() {
        assertEquals("system", match(null))
        assertEquals("system", match(""))
        assertEquals("system", match("xx"))
        assertEquals("system", match("tlh-US"))
    }
}
