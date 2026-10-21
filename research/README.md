# Clean-up prompt research

October 2026. How aggressive the dictation clean-up was, what we changed, and
what we measured. Everything here was run against the live Mistral API at
temperature 0, the way the app calls it.

## The problem

The clean-up prompt rewrote casual speech. Under the old prompt, more than
half of 41 slang-heavy dictations lost a word the speaker meant, on every
Mistral model. The causes:

- **Spoken-correction cues fired on ordinary words.** "sorry", "no" and
  "I mean" were listed as bare edit commands, so "no no no don't do that"
  came back as "Wait for me." on all four models.
- **"Light grammar" corrected dialect**: "they was" → "they were",
  "didn't do nothing" → "anything".
- **Slang was expanded**: thx → thanks, wyd → "what you doing", sus →
  suspicious, ваще → вообще.
- **Interjections and tags were treated as filler**: lmao, ugh, deadass, bro,
  honestly.
- **Repetition was treated as a stutter**: "cool cool cool" → "Cool".
- **Narration was rewritten**: "I'm like" → "I said", "he goes" → "he went".

## The bar

Two levels, set by the product owner:

- **Clean-up (automatic, light)**: "I would type it that way if I were
  typing." Corrections, filler, punctuation and grammar fixes, but the
  speaker's words, slang, quotatives, tense and sentence shape stay.
- **Written (quick action, heavy)**: turn speech into written form, keeping
  meaning and tone.

## Results

The light prompt went through versions v1–v4; v4 shipped, later extended to
generic+ (see below). "Clean" means only acceptable edits were made:
punctuation, capitals, um/uh, stutters, real corrections, light grammar.

| 68 English cases | old prompt | shipped |
|---|---|---|
| ministral-8b (default) | 29 clean | 59 clean |
| ministral-14b | 31 / 65 | 61 / 65 |

Held-out cases, whose wording appears in no prompt example, improved from
3/14 to 12/14 on 8b, so the gain is not just the model copying examples.

What made the difference, in order of effect:

1. **Input → output examples instead of keep-lists.** Telling small models
   which words to keep did not work; showing them did.
2. **A correction needs a replacement.** A cue with nothing to replace is an
   ordinary word.
3. **Filler is a closed list** (um, uh, er, erm, hesitation "like",
   accidental stutters), with what stays named explicitly.
4. **"Always capitalise and punctuate"** had to be said outright. The stricter
   word rules otherwise made models stop touching anything, and a third of
   outputs came back lowercase.
5. **No example slang in instructions.** Words listed as examples ("no cap")
   got pasted into outputs that never contained them.

### Languages

We built per-language prompts (native examples for English, German, Dutch,
Russian, chosen by on-device language detection) and compared them with a
single generic prompt.

| 132 German/Dutch/Russian cases | clean | corrections left undone |
|---|---|---|
| per-language prompts | 92/132 | 6 |
| generic | 78/132 | 13 |
| **generic+** (shipped) | **91/130** | 9 |

Two generic+ cases were lost to rate limits, so it was scored on 130.

The per-language prompts only won through the cue and colloquial words they
listed ("то есть", "nee wacht", "щас"). On held-out wording they tied the
generic prompt at 29/48. Generic+ lists those words for all four languages in
one prompt and matches the per-language prompts without detecting the
language. The one gap left is Russian on 8b, where native examples also fixed
capitalisation (9/14 clean against 5/14, 0 lowercase outputs against 4).

### Written (heavy)

Spoken narration became written narration reliably, and corrections never
came back. The first instruction translated slang into plain English
("lowkey fire" → "actually really good") and invented details ("she sighed").
The shipped wording forbids swapping slang and adding anything, and lists no
example slang. Tone was lost in 5/25 cases on 8b, mostly a trailing "dude".

## Other findings

- **`open-mistral-nemo` is served as `ministral-8b-2512`.** Its outputs were
  identical to 8b's in every run.
- **ministral-3b is unreliable with any prompt.** It drops half sentences and
  misses corrections.
- **mistral-small and mistral-medium** returned 429 on every call on our key's
  tier.
- **Voxtral normalises before clean-up ever runs**: "щас" → "сейчас",
  "ten" → "10".
- **Mistral's transcription response has a `language` field, but it is always
  null**, even when a language is passed in.
- **The prompts were sent with a ragged 8-space margin**, because multi-line
  values were interpolated before `trimIndent()`. Fixed.

## Known gaps

- Present-tense storytelling still becomes past tense on 8b ("I go in" →
  "I went in"). 14b keeps it.
- "um so like uh I was gonna say…" still loses "I was gonna say" on 8b.
- Russian capitalisation on 8b (see Languages).
- Each case was run once; differences of one or two cases are noise.

## Re-running

`cleanup-eval/` reads the prompts straight from the Kotlin sources, so it
always tests what the app would send.

The Mistral key comes from secretspec's `research` profile. It is never
written to disk or exported in the shell.

```sh
secretspec set -P research MISTRAL_API_KEY      # once; prompts for the value
cd research/cleanup-eval

secretspec run -P research -- python3 run.py current ministral-8b-latest ministral-14b-latest
secretspec run -P research -- python3 run.py variants/v4.txt ministral-8b-latest
CASES=lang_cases LANG_ONLY=russian secretspec run -P research -- python3 run.py current ministral-8b-latest

python3 classify.py results/<file>.json [-v]   # DROP / SUB / ADD / FAIL / CASE per model; no key needed
secretspec run -P research -- python3 heavy.py results/<light>.json ministral-8b-latest ministral-8b-latest
```

| file | what it is |
|---|---|
| `prompts.py` | rebuilds the clean-up and quick-action prompts from the Kotlin sources |
| `cases.py` | 68 English and Russian dictations: slang, dialect, profanity, emphasis, false corrections, real corrections, narration, held-out |
| `lang_cases.py` | 66 German, Dutch and Russian dictations, including 24 held out from every prompt |
| `run.py` | calls Mistral for every case and model, and writes `results/<variant>__<models>.json` |
| `classify.py` | sorts each word-level edit into acceptable or overreach |
| `heavy.py` | runs the Written action on light output and checks tone, narration and corrections |
| `variants/` | every prompt we tested: v1–v4, per-language, generic+, Written v2/v3 |
| `stats.md` | per-run scores behind every number above; the raw outputs are not kept |

Rate limits make a 4-model × 68-case run take 5–10 minutes.
