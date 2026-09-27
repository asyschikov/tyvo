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
        instruction = "Rewrite in a professional register: full words instead of " +
            "contractions, no slang, courteous but not stiff. Keep it the same length.",
        group = QuickAction.Group.TONE,
    )

    val CASUAL = QuickAction(
        id = "casual",
        label = "Casual",
        instruction = "Rewrite in a relaxed, conversational register, as if " +
            "messaging a colleague you know well. Contractions are fine.",
        group = QuickAction.Group.TONE,
    )

    val WARMER = QuickAction(
        id = "warmer",
        label = "Warmer",
        instruction = "Soften the tone. Make it friendlier and less blunt without " +
            "adding filler or changing what is being said.",
        group = QuickAction.Group.TONE,
    )

    val DIRECT = QuickAction(
        id = "direct",
        label = "Direct",
        instruction = "Make it more direct. Remove hedging, apologies and " +
            "throat-clearing. Say the thing plainly.",
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
