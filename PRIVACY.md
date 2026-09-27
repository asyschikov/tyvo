# Privacy Policy

**Tyvo — voice keyboard**
Last updated: 27 September 2026

## The short version

Tyvo has no server. Nothing you say or type is sent to us, because there is no
"us" to send it to — no account, no sign-up, no analytics, no crash reporting,
no telemetry of any kind.

Your recordings and transcripts stay on your phone. Audio goes directly from
your device to the AI provider you chose, using an API key you supplied, and
nowhere else.

## What Tyvo sends, and to whom

When you dictate, two things leave your device, both going straight to the one
provider you selected in settings:

1. **The audio recording**, to be transcribed into text.
2. **The resulting text**, to be cleaned up — punctuation, filler removal, the
   corrections you spoke aloud.

The provider is your choice: OpenAI, Mistral, xAI, or Google. Their handling of
that data is governed by their own privacy policy and the terms of your account
with them, not by this one. You are their customer directly; Tyvo is not an
intermediary.

If you turn clean-up off, only the audio is sent.

## What stays on your device

- **Recordings.** Audio is kept until it has been transcribed, then deleted.
  Anything that failed to transcribe keeps its audio so you can retry, and is
  deleted after 24 hours unless you turn that off in settings.
- **Transcripts.** Kept in the app's History until you delete them.
- **Your API key.** Stored in Android's `EncryptedSharedPreferences`, which is
  backed by the device keystore. It is sent only to the provider it belongs to.
- **Settings and usage counts.** Never leave the device.

None of this is backed up off-device: the app sets `allowBackup="false"`, so
Android will not copy it to Google Drive.

## What Tyvo collects

Nothing.

There is no analytics SDK, no crash reporter, and no identifier of any kind.
The only network requests the app makes are to your chosen provider: one to
transcribe the audio, one to clean up the text.

You can verify this yourself — the source is public:

```sh
grep -rhoE 'https?://[a-zA-Z0-9.-]+' app/src/main/kotlin/ | sort -u
```

That lists `api.openai.com`, `api.mistral.ai`, `api.x.ai` and
`generativelanguage.googleapis.com` — the four providers, of which only the one
you select is ever contacted — plus `gnu.org`, which appears in the licence
header of every source file and is never requested.

## Permissions, and why

- **Microphone** — to record what you dictate. Recording happens only while you
  are holding the button, never in the background.
- **Internet** — to reach your chosen AI provider.
- **Notifications** — to tell you when a dictation could not be transcribed and
  is waiting to be retried. Optional.
- **Vibration** — haptic feedback when recording starts and stops.

Tyvo asks for no access to contacts, storage, location, camera, or device
identifiers.

## A note on keyboards

Android warns you that a keyboard may be able to see everything you type. That
warning is accurate and you should take it seriously for any keyboard.

Tyvo reads the text around your cursor only to place dictated text correctly and
to apply the quick actions you tap. That text is sent to your chosen provider
only when you ask for a rewrite. It is never logged, stored, or transmitted
anywhere else.

Tyvo is not a general typing keyboard, and does not observe ordinary typing —
switch back to your usual keyboard with the globe key and Tyvo sees nothing.

## Children

Tyvo is not directed at children and collects no data from anyone.

## Changes

Any change to this policy will appear in this file, and its history is public in
the repository.

## Contact

asyschikov+tyvo@gmail.com
