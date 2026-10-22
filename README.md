# Tyvo

**Tired of yet another subscription? Upset with rug pulls? Hate paying for an LLM wrapper?**

An Android keyboard and bubble that dictates, cleans up what you said, and lets you
reshape it without leaving the keyboard. Free, fully private, and open source, BYOK. Stop paying for wrappers!

Say "I am going to cook pasta, sorry no, lasagna" — the field gets
"I am going to cook lasagna."

There is no server and no subscription: Tyvo talks to your AI provider and
nothing else. Bring your own key and pay the provider per second of audio —
a heavy day costs cents, a quiet week costs nothing.

## Features

- **Dictation with clean-up** — raw text first, polished text swapped in
- **Quick actions** — proofread, tone and length rewrites from the review strip
- **Floating mic** — dictate into any field, with your usual keyboard
- **Context-aware fields** — number pad for numeric fields; keys for search, passwords, addresses
- **History** — every dictation kept; failed ones retryable, with their audio
- **Four providers** — Mistral, OpenAI, Gemini, xAI; one key each
- **Switchable corrections** — spoken fixes, fillers, punctuation, grammar
- **Language and vocabulary** — as many languages as your model supports, plus names and jargon
- **No telemetry** — no account, no analytics, no crash reporting

### Dictation with clean-up

Audio is recorded as 16 kHz mono WAV and transcribed by your provider. The
raw text is committed the moment transcription returns, then swapped for a
cleaned-up version. Lock the phone mid-sentence and the dictation still
finishes, waiting to be inserted when you are back in a field.

### Quick actions

The review strip offers **Fix** (proofread, punctuate, tighten, → English),
**Tone** (formal, casual, warmer, direct) and **Length** (shorter, expand,
bullets). Each action rewrites the base text rather than stacking on the
previous one, and Undo returns to the base.

### Floating mic

An optional accessibility service shows a draggable mic beside whatever
keyboard is up, so you can dictate into any field without switching
keyboards. Tap to dictate at the cursor, hold for the review strip. Password
fields are skipped, and it stays out of the way when Tyvo's own keyboard is
showing.

### Context-aware fields

Voice does not fit every field. Number, phone and date/time fields get a
number pad; search boxes, password fields and web addresses get compact text
keys. Each context is configurable — voice, keys, or handing the field back
to your usual keyboard. The pad is the default: a keyboard that has handed
the field away cannot switch itself back on the next one.

### History

Every dictation is listed, unfinished ones pinned to the top. A failed
transcription keeps its audio and can be retried or exported; a successful
one keeps its text forever and drops the audio.

### Providers

One provider handles both transcription and clean-up, so you hold a single
key: Mistral (default, most tested), OpenAI, Google Gemini or xAI. Keys and
model choices are stored per provider in `EncryptedSharedPreferences`.

### Corrections

What clean-up may do — spoken corrections ("pasta, sorry no, lasagna"),
filler removal, punctuation, light grammar — is switchable in Settings. The
optional extras (paragraph breaks, lists, numbers and dates) cost accuracy on
small models.

### Language and vocabulary

One language slot from a searchable list of common languages; blank means
auto-detect. The list is a picker, not a limit — transcription supports as
many languages as your model does. A vocabulary list sends names and jargon
to the transcriber as a biasing hint.

### No telemetry

No account, no analytics, no crash reporting. Recordings and transcripts
stay on the device, and nothing passes through infrastructure belonging to
this project, because there isn't any. See [PRIVACY.md](PRIVACY.md).

## Get started

Download the APK from [Releases](https://github.com/asyschikov/tyvo/releases),
enable Tyvo in Android's keyboard settings, paste an API key.

Full documentation — providers, corrections, durability, design notes, build
instructions: [docs/README.md](docs/README.md). Licence: GPLv3, see
[LICENSE](LICENSE).
