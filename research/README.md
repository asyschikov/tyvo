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

Re-run on Mistral Small 4 (October 2026, after it became the default):
per-language prompts 55/66 clean against 49/66 for generic+, but 20/24
against 19/24 on held-out cases. The whole gap is capitalisation: generic+
left 7 outputs lowercase, 5 of them Dutch; per-language left none. The
transcriber can pick the prompt: with the undocumented `return_language=true`
it reports the language (see below).

End to end, though, the gap is gone. Spoken through `say`, transcribed by
Voxtral Small 3 (language reported right on 134/134) and cleaned up by
Small 4, generic+ scored 115/134 and the native prompts 112/134; 33/39
against 31/39 held out. Voxtral already capitalises and punctuates, so the
lowercase failures the text-only cases provoked never arise. Generic+ stays.

### Written (heavy)

Spoken narration became written narration reliably, and corrections never
came back. The first instruction translated slang into plain English
("lowkey fire" → "actually really good") and invented details ("she sighed").
The shipped wording forbids swapping slang and adding anything, and lists no
example slang. Tone was lost in 5/25 cases on 8b, mostly a trailing "dude".

## Transcription: Voxtral 3

Voxtral 3 came out on 2026-10-20. Each model transcribed 13 clips synthesized
with macOS `say`, in English, German, Dutch and Russian (slang, corrections,
spoken numbers), once each (`transcribe_eval.py`):

| model | mean WER | slang lost | median latency | price |
|---|---|---|---|---|
| `voxtral-mini-latest` (= 2602, old default) | 0.127 | haste, ff, ваще | 0.46 s | |
| `voxtral-mini-transcribe-3` | 0.109 | haste, щас | **0.38 s** | $0.0035/min ($0.21/h) |
| **`voxtral-small-transcribe-3`** (new default) | **0.076** | haste | 0.53 s | $0.004/min ($0.24/h) |

- `voxtral-mini-latest` still points at 2602, so v3 has to be named
  explicitly.
- All three models write spoken numbers as digits (ten → 10, "nine, I mean
  ten" → "9. I mean 10."). That accounts for most of the WER, equally across
  models.
- `language` is null unless the request sends `return_language=true`, a
  field the docs leave out (it showed up in a validation error's echo of the
  request). With it, all three models named the language right on 13/13
  clips: en, de, nl, ru.
- The same echo shows a `verbatim` flag. On 5 clips it kept spoken numbers
  as words ("nine. I mean ten") but also dropped a German "äh". Not adopted.
- v3 accepts the app's `language` and repeated `context_bias` fields. Putting
  "щас" in `context_bias` made both v3 models keep it instead of writing
  "сейчас", so the app's vocabulary setting can protect slang.
- Clean synthetic speech says nothing about noise or accents.

## Other findings

- **`open-mistral-nemo` is served as `ministral-8b-2512`.** Its outputs were
  identical to 8b's in every run.
- **ministral-3b is unreliable with any prompt.** It drops half sentences and
  misses corrections.
- **mistral-small and mistral-medium** returned 429 on every call on our key's
  tier.
- **Voxtral normalises before clean-up ever runs**: "щас" → "сейчас",
  "ten" → "10".
- **Mistral's transcription response has a `language` field, null by
  default.** It is filled only when the request sends `return_language=true`.
- **The prompts were sent with a ragged 8-space margin**, because multi-line
  values were interpolated before `trimIndent()`. Fixed.

## Speed

Median clean-up call, 20 calls each (`speed.py`): Ministral 3B 0.43 s, 8B
0.51 s, 14B 0.48 s, Small 4 0.59 s, Medium 0.57 s, Large 4 1.23 s. Everything
below Large is within about 0.15 s, which is noise next to the transcription
call. Large 4 reasons by default and took 22.6 s until sent
`reasoning_effort: "none"`; it also rejects `temperature: 0` without
`top_p: 1`. The app now sends both.

## Known gaps

- Present-tense storytelling still becomes past tense on 8b ("I go in" →
  "I went in"). 14b keeps it.
- "um so like uh I was gonna say…" still loses "I was gonna say" on 8b.
- Russian capitalisation on 8b (see Languages).
- Each case was run once; differences of one or two cases are noise.

## Re-running

`cleanup-eval/` reads the prompts straight from the Kotlin sources, so it
always tests what the app would send.

The Mistral key comes from secretspec (the `research` scope). It is never
written to disk or exported in the shell.

```sh
secretspec set MISTRAL_API_KEY      # once; prompts for the value
cd research/cleanup-eval

secretspec run -S research -- python3 run.py current ministral-8b-latest ministral-14b-latest
secretspec run -S research -- python3 run.py variants/v4.txt ministral-8b-latest
CASES=lang_cases LANG_ONLY=russian secretspec run -S research -- python3 run.py current ministral-8b-latest

python3 classify.py results/<file>.json [-v]   # DROP / SUB / ADD / FAIL / CASE per model; no key needed
secretspec run -S research -- python3 heavy.py results/<light>.json ministral-8b-latest ministral-8b-latest
```

| file | what it is |
|---|---|
| `prompts.py` | rebuilds the clean-up and quick-action prompts from the Kotlin sources |
| `cases.py` | 68 English and Russian dictations: slang, dialect, profanity, emphasis, false corrections, real corrections, narration, held-out |
| `lang_cases.py` | 66 German, Dutch and Russian dictations, including 24 held out from every prompt |
| `run.py` | calls Mistral for every case and model, and writes `results/<variant>__<models>.json` |
| `classify.py` | sorts each word-level edit into acceptable or overreach |
| `heavy.py` | runs the Written action on light output and checks tone, narration and corrections |
| `transcribe_eval.py` | compares transcription models on synthesized clips (needs macOS `say`) |
| `speed.py` | times real clean-up calls per model |
| `e2e_lang.py` | speaks every case, transcribes it with the language reported, and cleans it up with generic+ and the native prompt |
| `variants/` | every prompt we tested: v1–v4, per-language, generic+, Written v2/v3 |
| `stats.md` | per-run scores behind every number above; the raw outputs are not kept |

Rate limits make a 4-model × 68-case run take 5–10 minutes.
