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
package com.tyvo.keyboard.actions

/**
 * A one-tap transformation offered after dictation.
 *
 * [instruction] is dropped into the transform prompt verbatim, so each one
 * reads as a single imperative sentence. Actions are deliberately narrow:
 * a button whose effect you cannot predict before tapping it is a button you
 * stop trusting.
 */
data class QuickAction(
    val id: String,
    val label: String,
    val instruction: String,
    val group: Group,
    /**
     * Whether this action is allowed to change the output language. Only
     * translation is; every other action must return the speaker's own
     * language, which the prompt enforces hard because an English-language
     * instruction otherwise drags non-English text into English.
     */
    val translating: Boolean = false,
) {
    enum class Group { TONE, LENGTH, FIX }
}

object QuickActions {

    // ---- Tone -----------------------------------------------------------

    val FORMAL = QuickAction(
        id = "formal",
        label = "Formal",
        instruction = "Make it sound professional by editing the casual words " +
            "only: expand contractions, drop openers like \"hey\" and \"so\", " +
            "and swap slang for its plain equivalent. Keep every other word " +
            "and the sentence order exactly as spoken -- do not restate their " +
            "point in your own phrasing, and do not reach for grander " +
            "vocabulary than they used.",
        group = QuickAction.Group.TONE,
    )

    val CASUAL = QuickAction(
        id = "casual",
        label = "Casual",
        instruction = "Adjust the wording to sound relaxed and conversational: " +
            "use contractions, and swap formal or bureaucratic words for " +
            "everyday ones. Change only the words that carry the formal tone " +
            "-- keep the speaker's sentences, order and length, and do not add " +
            "slang or chattiness they did not say.",
        group = QuickAction.Group.TONE,
    )

    val WARMER = QuickAction(
        id = "warmer",
        label = "Warmer",
        instruction = "Take the edge off: turn blunt imperatives into requests, " +
            "temper harsh or accusatory words, and add a please where one is " +
            "clearly missing. Change only what carries the bluntness -- keep " +
            "the speaker's sentences, order and length, and add no new " +
            "pleasantries or padding.",
        group = QuickAction.Group.TONE,
    )

    val DIRECT = QuickAction(
        id = "direct",
        label = "Direct",
        instruction = "Make it direct by deleting, not rewriting. Cut every " +
            "hedge (\"I think\", \"maybe\", \"probably\", \"might\", \"sort of\", " +
            "\"just\"), filler opener (\"hey so\", \"well\") and unnecessary " +
            "apology. Leave every surviving word exactly as the speaker said " +
            "it, and keep all their points.",
        group = QuickAction.Group.TONE,
    )

    // ---- Length ---------------------------------------------------------

    val SHORTER = QuickAction(
        id = "shorter",
        label = "Shorter",
        instruction = "Cut roughly a third of the words. Remove redundancy, keep " +
            "every distinct point. Do not drop information.",
        group = QuickAction.Group.LENGTH,
    )

    val EXPAND = QuickAction(
        id = "expand",
        label = "Expand",
        instruction = "Expand into fuller sentences, making implied connections " +
            "explicit. Add no new facts or claims.",
        group = QuickAction.Group.LENGTH,
    )

    val BULLETS = QuickAction(
        id = "bullets",
        label = "Bullets",
        instruction = "Restructure the existing content as a bullet list, one " +
            "idea per bullet, using '- ' as the marker. Use only ideas already " +
            "present in the text; invent no new bullets, steps or suggestions. " +
            "Keep the wording close to the original.",
        group = QuickAction.Group.LENGTH,
    )

    // ---- Fix ------------------------------------------------------------

    val PROOFREAD = QuickAction(
        id = "proofread",
        label = "Proofread",
        instruction = "Fix spelling, grammar and punctuation only. Keep every " +
            "word the speaker used, including informal ones and abbreviations " +
            "like 'deploy' or 'repo'; do not substitute synonyms or expand " +
            "shortened words. Change no tone or structure.",
        group = QuickAction.Group.FIX,
    )

    val PUNCTUATE = QuickAction(
        id = "punctuate",
        label = "Punctuate",
        instruction = "Add and correct punctuation, capitalisation and paragraph " +
            "breaks. Do not change, add, remove or substitute a single word.",
        group = QuickAction.Group.FIX,
    )

    val TIGHTEN = QuickAction(
        id = "tighten",
        label = "Tighten",
        instruction = "Remove filler, hedges and redundant words. Keep every " +
            "distinct piece of information and the sentence structure: this is " +
            "a trim, not a summary, and the result should stay a complete " +
            "sentence of at least half the original length.",
        group = QuickAction.Group.FIX,
    )

    val TRANSLATE_EN = QuickAction(
        id = "translate_en",
        label = "→ English",
        instruction = "Translate into natural English, preserving tone and register.",
        group = QuickAction.Group.FIX,
        translating = true,
    )

    /** Order here is the order shown in the action bar. */
    val ALL: List<QuickAction> = listOf(
        PROOFREAD, SHORTER, DIRECT, FORMAL, CASUAL, WARMER,
        TIGHTEN, PUNCTUATE, EXPAND, BULLETS,
        TRANSLATE_EN,
    )

    fun byId(id: String): QuickAction? = ALL.firstOrNull { it.id == id }
}
