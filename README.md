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

| Provider  | Transcription | Polish |
|-----------|---------------|--------|
| OpenAI    | ✅ `gpt-4o-mini-transcribe` | ✅ `gpt-4o-mini` |
| Mistral   | ✅ `voxtral-mini-latest`    | ✅ `mistral-small-latest` |
| Anthropic | ❌ *not possible*           | ✅ `claude-haiku-4-5` (default) |

**Anthropic cannot transcribe.** Claude's Messages API accepts text, images
and documents — there is no audio content block and no speech-to-text
endpoint. So audio must go to OpenAI or Mistral; Claude handles the language
work it is actually good at. Every provider is swappable per stage, and any
model can be overridden by name in settings.

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
