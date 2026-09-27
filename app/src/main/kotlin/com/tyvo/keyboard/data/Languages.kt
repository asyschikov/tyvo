/*
 * Tyvo -- a voice keyboard that writes what you meant.
 * Copyright (C) 2026 Andrey Syschikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tyvo.keyboard.data

/** An ISO 639-1 language, as shown in the picker. */
data class Language(val code: String, val name: String, val native: String)

/**
 * The languages a transcription provider can be asked about.
 *
 * Kept to the set the current models actually claim to support, rather than
 * all of ISO 639-1: offering a language the model cannot transcribe would be
 * a promise the app cannot keep.
 */
object Languages {

    val ALL: List<Language> = listOf(
        Language("en", "English", "English"),
        Language("ru", "Russian", "Русский"),
        Language("de", "German", "Deutsch"),
        Language("fr", "French", "Français"),
        Language("es", "Spanish", "Español"),
        Language("it", "Italian", "Italiano"),
        Language("pt", "Portuguese", "Português"),
        Language("nl", "Dutch", "Nederlands"),
        Language("pl", "Polish", "Polski"),
        Language("uk", "Ukrainian", "Українська"),
        Language("cs", "Czech", "Čeština"),
        Language("sv", "Swedish", "Svenska"),
        Language("da", "Danish", "Dansk"),
        Language("no", "Norwegian", "Norsk"),
        Language("fi", "Finnish", "Suomi"),
        Language("tr", "Turkish", "Türkçe"),
        Language("el", "Greek", "Ελληνικά"),
        Language("bg", "Bulgarian", "Български"),
        Language("ro", "Romanian", "Română"),
        Language("hu", "Hungarian", "Magyar"),
        Language("sr", "Serbian", "Српски"),
        Language("hr", "Croatian", "Hrvatski"),
        Language("sk", "Slovak", "Slovenčina"),
        Language("he", "Hebrew", "עברית"),
        Language("ar", "Arabic", "العربية"),
        Language("hi", "Hindi", "हिन्दी"),
        Language("zh", "Chinese", "中文"),
        Language("ja", "Japanese", "日本語"),
        Language("ko", "Korean", "한국어"),
        Language("vi", "Vietnamese", "Tiếng Việt"),
        Language("th", "Thai", "ไทย"),
        Language("id", "Indonesian", "Bahasa Indonesia"),
        Language("ms", "Malay", "Bahasa Melayu"),
        Language("fa", "Persian", "فارسی"),
        Language("ta", "Tamil", "தமிழ்"),
        Language("ur", "Urdu", "اردو"),
        Language("ca", "Catalan", "Català"),
        Language("et", "Estonian", "Eesti"),
        Language("lv", "Latvian", "Latviešu"),
        Language("lt", "Lithuanian", "Lietuvių"),
        Language("sl", "Slovenian", "Slovenščina"),
        Language("az", "Azerbaijani", "Azərbaycan"),
        Language("kk", "Kazakh", "Қазақша"),
        Language("hy", "Armenian", "Հայերեն"),
        Language("ka", "Georgian", "ქართული"),
    )

    fun byCode(code: String): Language? =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }

    /** Matches on code, English name or endonym, so either spelling finds it. */
    fun search(query: String): List<Language> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ALL
        return ALL.filter {
            it.code.startsWith(q) ||
                it.name.lowercase().contains(q) ||
                it.native.lowercase().contains(q)
        }
    }
}
