# Tyvo

An Android keyboard that dictates, cleans up what you said, and then lets you
reshape it — without leaving the keyboard.

Say:

> "I am going to book pasta, sorry no, lasagna"

and the field gets:

> I am going to book lasagna.

## How it works

```
mic ──▶ WAV (16 kHz mono) ──▶ transcription API ──▶ raw text
                                                      │
                                              inserted immediately
                                                      │
                                                      ▼
                                            LLM clean-up pass
                                                      │
                                              swapped in place
                                                      │
                                                      ▼
                                     review: quick actions · undo · custom
```

Raw text is committed as soon as transcription returns, then replaced by the
polished version. Waiting for both round-trips before showing anything makes
the keyboard feel broken.

## Providers

You pick **one** provider; it handles both stages, so you only ever hold a
single API key.

| Provider  | Transcription | Clean-up |
|-----------|---------------|----------|
| OpenAI    | `gpt-4o-mini-transcribe` | `gpt-4o-mini` |
| Mistral   | `voxtral-mini-latest`    | `ministral-3b-latest` |

Keys and model choices are stored **per provider**, so switching to the other
one and back does not cost you a key you already pasted in.

Note on model names: of the Voxtral family, only `voxtral-mini-*` reports
`audio_transcription` capability. `voxtral-small-latest` does **not**
transcribe despite the name, so overriding the Mistral model carelessly will
break dictation. A unit test guards the default.

## Quick actions

Offered on the review strip after dictation, each one re-editable and undoable:

**Fix** — Proofread · Punctuate · Tighten · → English
**Tone** — Formal · Casual · Warmer · Direct
**Length** — Shorter · Expand · Bullets
**Form** — Email · Chat · Commit · Prompt

Plus a free-text box: type any instruction ("make it sound less annoyed",
"turn this into a Jira ticket") and it applies to the current text.

Every transformation is undoable one step at a time, up to 20 steps.

## Setup

1. Install the APK and open **Tyvo**.
2. Grant microphone access.
3. Enable Tyvo in system keyboard settings, then switch to it.
4. Paste API keys. They are stored in `EncryptedSharedPreferences` and sent
   only to the provider you selected.
5. Hit **Test connection** to confirm the key and model work.

## Accuracy settings

- **Language hint** — ISO code; blank auto-detects.
- **Vocabulary** — names and jargon the transcriber keeps mangling. Sent as a
  biasing prompt (OpenAI) or `context_bias` terms (Mistral).

## Build

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Design notes

**Why not a full keyboard?** Tyvo does one thing. The globe key returns you to
your usual IME for ordinary typing.

**Text safety.** When replacing polished text, the service checks the field
still ends with exactly what it committed. If you edited it meanwhile, Tyvo
appends instead of overwriting your edit.

**Prompt discipline.** Both prompts explicitly forbid the model answering,
acting on, or translating the content — dictating "what time is the meeting?"
must yield that question, not an answer to it.

## Model selection

Both stages offer a dropdown of curated models plus a **Custom…** option for
any model ID. Blank means "provider default", so defaults can improve without
stranding you on an old pin.

### A note on Mistral tiers

Mistral gates larger models by subscription tier, and reports it as
`HTTP 429 Rate limit exceeded` — which reads like throttling but really means
"not included in your plan". Empirically, on a low tier:

| Model | Result |
|---|---|
| `ministral-3b/8b/14b-latest`, `open-mistral-nemo` | ✅ work |
| `mistral-small-latest`, `mistral-medium-latest`, `magistral-small-latest` | ❌ 429 |
| `mistral-large-latest` | ❌ 403 "not available in your subscription tier" |

The 403 on `large` is what gives the game away: the 429s are the same gating
with a friendlier status code. This is observed behaviour, not documented
policy. **Test connection** in settings tells you which bucket you are in.

## Status

Verified working on a Pixel 10 (Android 17):

- IME registers, is selectable, renders without crashing
- Settings, permissions, encrypted key storage
- Live HTTPS calls to Mistral; errors surface as readable text
- WAV format accepted by the transcription endpoint (HTTP 200)
- Clean-up prompt: 8/8 on a tricky-transcript suite against the real API
- 22 unit tests

Not yet verified end to end: speaking into the mic and getting polished text
back, which needs a working transcription tier plus a human voice.
