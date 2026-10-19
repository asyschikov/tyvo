package com.tyvo.keyboard.polish

/**
 * One thing the clean-up pass is allowed to do.
 *
 * Split out so the user can choose: people dictating notes want the lot,
 * while someone drafting a message often wants spoken corrections applied and
 * nothing else touched. Each entry contributes one numbered instruction to
 * the prompt, and disabled ones are simply absent rather than negated -- a
 * prompt full of "do not do X" reads as a list of things to think about.
 */
enum class Correction(
    val id: String,
    val label: String,
    val summary: String,
    /** Recommended for a first-time user. */
    val defaultOn: Boolean,
    val instruction: String,
) {
    SPOKEN_EDITS(
        id = "spoken_edits",
        label = "Spoken corrections",
        summary = "\"cook pasta, sorry no, lasagna\" → \"cook lasagna\"",
        defaultOn = true,
        instruction = """
            SPOKEN CORRECTIONS. Treat phrases like "sorry no", "I mean",
            "scratch that", "no wait", "actually make that", "rather" as edit
            commands. Apply the correction and delete both the command and the
            text it replaced. Keep every other word of the sentence, including
            the subject and any leading clause.
              "I am going to cook pasta, sorry no, lasagna"
                -> "I am going to cook lasagna."
              "let's meet at five, I mean six" -> "Let's meet at six."
              "email Sarah - scratch that - email Tom" -> "Email Tom."
        """.trimIndent(),
    ),

    FILLER(
        id = "filler",
        label = "Filler and false starts",
        summary = "Drops \"um\", \"uh\", stutters and abandoned half-sentences",
        defaultOn = true,
        instruction = """
            FALSE STARTS AND FILLER. Remove "um", "uh", "like" used as filler,
            stutters, and genuinely abandoned half-sentences. Keep "like" when
            it carries meaning ("it works like this"). Never drop a subject,
            auxiliary verb or opening clause just to make the sentence shorter:
            "um so I was thinking we could ship it friday" keeps "I was
            thinking we could" and becomes
            "So I was thinking we could ship it Friday."
        """.trimIndent(),
    ),

    PUNCTUATION(
        id = "punctuation",
        label = "Punctuation and capitalisation",
        summary = "Adds what speech does not carry",
        defaultOn = true,
        instruction = """
            PUNCTUATION AND CAPITALISATION. Add what speech does not carry:
            sentence breaks, commas, capitals, question marks.
        """.trimIndent(),
    ),

    SPOKEN_MARKS(
        id = "spoken_marks",
        label = "Spoken punctuation",
        summary = "Saying \"question mark\" or \"new line\" inserts the real thing",
        defaultOn = true,
        instruction = """
            SPOKEN PUNCTUATION. Honour spoken punctuation words ("period",
            "comma", "new line", "question mark") by converting them into the
            actual mark, but only when they are clearly meant as dictation
            commands rather than part of the sentence.
        """.trimIndent(),
    ),

    GRAMMAR(
        id = "grammar",
        label = "Light grammar",
        summary = "Fixes agreement and obvious transcription slips",
        defaultOn = true,
        instruction = """
            LIGHT GRAMMAR. Fix agreement and obvious transcription slips. Do
            not restyle a sentence that is merely informal.
        """.trimIndent(),
    ),

    PARAGRAPHS(
        id = "paragraphs",
        label = "Paragraph breaks",
        summary = "Splits long dictation where the subject changes",
        defaultOn = false,
        instruction = """
            PARAGRAPHS. If the transcript runs long and clearly moves between
            topics, insert paragraph breaks at those boundaries. Never
            reorder, merge or summarise the content.
        """.trimIndent(),
    ),

    LISTS(
        id = "lists",
        label = "Numbered lists",
        summary = "\"first... second... next...\" becomes a numbered list",
        defaultOn = false,
        instruction = """
            NUMBERED LISTS. When the speaker enumerates items with ordinal or
            sequencing words -- "first", "second", "third", "next", "then",
            "finally", "lastly", "after that" -- rewrite the run as a numbered
            list, one item per line, each starting "1. ", "2. " and so on.
            Drop the spoken marker itself, since the number replaces it, and
            keep each item's own words exactly. The shape is:

              1. <the speaker's first item>
              2. <the speaker's second item>

            A list needs at least three items with real content of their own.
            Fewer than three, and it stays prose -- a one-item numbered list is
            always wrong. A sentence that merely begins "first", or a
            "then" that narrates a sequence of events, is ordinary prose and
            must be left alone. If there are sequencing words but no items to
            go with them, return the transcript as it was -- never invent
            items, and never describe what you are doing.
        """.trimIndent(),
    ),

    NUMBERS(
        id = "numbers",
        label = "Numbers and dates",
        summary = "\"twenty five euros\" → \"€25\", \"third of May\" → \"3 May\"",
        defaultOn = false,
        instruction = """
            NUMBERS AND DATES. Write spoken numbers, currencies, times and
            dates in their conventional written form ("twenty five euros" ->
            "EUR 25", "half past nine" -> "9:30"), using the conventions of
            the text's own language. Leave a number alone when spelling it out
            is clearly deliberate.
        """.trimIndent(),
    );

    companion object {
        fun byId(id: String): Correction? = entries.firstOrNull { it.id == id }
        val defaults: Set<Correction> get() = entries.filter { it.defaultOn }.toSet()
    }
}
