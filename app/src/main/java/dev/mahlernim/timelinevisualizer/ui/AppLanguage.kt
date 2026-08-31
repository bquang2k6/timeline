package dev.mahlernim.timelinevisualizer.ui

import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguage {
    val supportedTags = listOf("en", "ko", "ja", "zh-CN", "zh-TW", "es", "fr", "de", "pt-BR", "vi")

    fun selectionIndex(languageTags: String): Int {
        val selected = languageTags.substringBefore(',').trim()
        if (selected.isEmpty()) return 0
        val locale = Locale.forLanguageTag(selected)
        val normalized = when {
            supportedTags.contains(selected) -> selected
            locale.language in listOf("en", "ko", "ja", "es", "fr", "de", "vi") -> locale.language
            locale.language == "pt" && locale.country.equals("BR", ignoreCase = true) -> "pt-BR"
            locale.language == "zh" && (
                locale.script.equals("Hans", ignoreCase = true) ||
                    locale.country.uppercase(Locale.ROOT) in listOf("CN", "SG")
                ) -> "zh-CN"
            locale.language == "zh" && (
                locale.script.equals("Hant", ignoreCase = true) ||
                    locale.country.uppercase(Locale.ROOT) in listOf("TW", "HK", "MO")
                ) -> "zh-TW"
            else -> return 0
        }
        return supportedTags.indexOf(normalized) + 1
    }

    fun localesForSelection(position: Int): LocaleListCompat {
        if (position == 0) return LocaleListCompat.getEmptyLocaleList()
        return LocaleListCompat.forLanguageTags(supportedTags[position - 1])
    }
}
