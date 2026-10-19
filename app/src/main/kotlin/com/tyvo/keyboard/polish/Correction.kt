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
        // Cues are listed for every language the app is tuned for: without
        // native ones, "nee wacht" and "то есть" were left in, undone.
        // A cue on its own is not a correction. Listed as bare edit commands,
        // "sorry", "no" and "I mean" made every model delete apologies,
        // answers and softeners -- "no no no don't do that" came back as
        // "Wait for me." -- so the replacement is now part of the definition.
        instruction = """
            SPOKEN CORRECTIONS. A spoken correction has three parts: something
            the speaker said, a cue, and a replacement for
            what they said. Apply it: delete the cue and the replaced words, keep
            the replacement and every other word of the sentence.
            Cues include, by language: "I mean", "sorry", "no wait", "scratch
            that", "actually make that", "or rather"; "ich meine", "äh nein",
            "nee warte", "streich das"; "ik bedoel", "nee wacht", "of nee", "laat
            maar"; "то есть", "в смысле", "ой нет", "нет, стоп", "вернее".
              "I am going to cook pasta, sorry no, lasagna"
                -> "I am going to cook lasagna."
              "let's meet at five, I mean six" -> "Let's meet at six."
              "email Sarah - scratch that - email Tom" -> "Email Tom."
            If there is no replacement, it is not a correction, and the words
            stay. "Sorry" as an apology, "no" as an answer, "I mean" as a
            softener, "rather" as a preference are ordinary words:
              "sorry no I can't make it" -> "Sorry, no, I can't make it."
              "no no no don't do that" -> "No no no, don't do that."
              "I mean it's not that bad" -> "I mean, it's not that bad."
        """.trimIndent(),
    ),

    FILLER(
        id = "filler",
        label = "Filler and stutters",
        summary = "Drops \"um\", \"uh\" and accidental stutters",
        defaultOn = true,
        // A closed list, then worked examples of what survives. An open
        // "abandoned half-sentences" licence cut "I was gonna say", and
        // lists of words to keep did not stop small models collapsing "so so
        // good" or dropping "lmao" -- input -> output pairs did.
        instruction = """
            FILLER. Remove hesitation sounds ("um", "uh", "er", "erm"), "like"
            when it is pure hesitation, and stutters where a short word comes
            out twice by accident ("I I I don't know" -> "I don't know", "the
            the plan" -> "the plan"). A word repeated for effect is not a
            stutter: keep every copy ("wait wait wait", "really really"). Nothing
            else is filler:
              "um so I was gonna say we should go"
                -> "So I was gonna say we should go."
              "it was so so good" -> "It was so so good."
              "cool cool cool see you then" -> "Cool cool cool, see you then."
              "and she's like whatever and I'm like okay"
                -> "And she's like whatever, and I'm like okay."
              "that's not gonna work sorry dude"
                -> "That's not gonna work, sorry dude."
              "omg ugh the train is late honestly"
                -> "Omg ugh, the train is late, honestly."
        """.trimIndent(),
    ),

    PUNCTUATION(
        id = "punctuation",
        label = "Punctuation and capitalisation",
        summary = "Adds what speech does not carry",
        defaultOn = true,
        // "Always", because the word-for-word rules otherwise read as "touch
        // nothing": without it, small models left a third of outputs
        // uncapitalised.
        instruction = """
            PUNCTUATION AND CAPITALISATION. Always do this, even when every
            word stays: a capital letter at the start of each sentence and for
            names, plus full stops, commas, question marks and apostrophes.
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
        // The bar is what the speaker would type themselves. Told only to
        // "fix agreement", models also put storytelling into the past tense
        // ("I walk in" -> "I walked in"), which is restyling, not grammar.
        instruction = """
            GRAMMAR. Fix grammar and transcription slips the way the speaker
            would if they were typing this themselves: agreement, a misheard
            word, a missing little word. Keep their words and the shape of the
            sentence; informal is not wrong.
              "they was out of milk" -> "They were out of milk."
              "yo meet me at ten" -> "Yo, meet me at ten."
            People tell stories in the present tense, and it stays present:
              "so yesterday I walk in and he goes hi"
                -> "So yesterday I walk in and he goes hi."
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
