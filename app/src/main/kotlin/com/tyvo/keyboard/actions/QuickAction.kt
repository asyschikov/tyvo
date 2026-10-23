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
    enum class Group { TONE, LENGTH, FIX, CUSTOM }
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

    // The heavier counterpart to the automatic clean-up, which only does what
    // the speaker would have typed themselves. This one may restructure, but
    // only from spoken to written form -- tone is the thing it must not touch.
    val WRITTEN = QuickAction(
        id = "written",
        label = "Written",
        // No example slang words here: listed ones ("no cap") got pasted into
        // outputs that never had them.
        instruction = "Turn this dictated speech into written text, the way " +
            "the speaker would write it if they sat down and typed it out: " +
            "rework run-on speech into clear sentences, drop verbal tics and " +
            "filler phrases (\"you know\", \"I mean\", \"like\", \"so yeah\", " +
            "\"ok so\"), and turn spoken storytelling into written narration " +
            "(\"I'm like\" -> \"I said\", \"he goes\" -> \"he said\"). This " +
            "changes the form, never the voice. Every slang word, swear word " +
            "and way of addressing someone that the speaker used stays exactly " +
            "as they said it: never swap it for a tamer word, a standard word " +
            "or a different piece of slang, and never add slang they did not " +
            "use. Keep hedges that carry meaning (\"I guess\", \"kinda\"), " +
            "keep every point, and add nothing they did not say, not even a " +
            "sigh or a \"please\". Casual stays casual.",
        group = QuickAction.Group.FIX,
    )


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
        WRITTEN, PROOFREAD, SHORTER, DIRECT, FORMAL, CASUAL, WARMER,
        TIGHTEN, PUNCTUATE, EXPAND, BULLETS,
        TRANSLATE_EN,
    )

    fun byId(id: String): QuickAction? = ALL.firstOrNull { it.id == id }

    /** Ids of actions the user made start with this, so they never clash with ours. */
    const val CUSTOM_PREFIX = "custom_"

    /**
     * The action bar as the user has shaped it: the built-ins, each with its
     * instruction replaced where the user edited it, then the user's own
     * actions in the order they made them. A blank edit counts as none, so
     * clearing the field brings the built-in instruction back.
     */
    fun merged(
        edits: Map<String, String>,
        custom: List<QuickAction>,
        removed: Set<String> = emptySet(),
    ): List<QuickAction> =
        ALL.filter { it.id !in removed }.map { a ->
            edits[a.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { a.copy(instruction = it) } ?: a
        } + custom.filter { it.label.isNotBlank() && it.instruction.isNotBlank() }

    fun custom(id: String, label: String, instruction: String) = QuickAction(
        id = id,
        label = label.trim(),
        instruction = instruction.trim(),
        group = QuickAction.Group.CUSTOM,
    )
}
