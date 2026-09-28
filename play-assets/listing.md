# Play Store listing — draft

Copy for the Play Console fields. Everything here is a starting point; the
character limits are Google's.

## App name (30 max)

```
Tyvo — Voice Keyboard
```

## Short description (80 max)

```
Speak into any text field. Tyvo writes what you meant, not what you said.
```

(73 characters.)

## Full description (4000 max)

```
Tyvo is a keyboard you talk to. Speak into any text field and it writes what
you meant — corrections applied, filler gone, punctuation added.

Say "I am going to cook pasta, sorry no, lasagna" and you get:

    I am going to cook lasagna.

It catches the corrections you make out loud. "Meet at five, I mean six"
becomes "meet at six". "Email Sarah — scratch that — email Tom" becomes
"Email Tom". The words you threw away do not make it into your message.

RESHAPE IT WITHOUT RETYPING

Once the text is there, one tap makes it shorter, more formal, more direct,
warmer, or a bulleted list. Every
change is one tap to undo, and each one works from your original text rather
than stacking on the last edit.

NO SUBSCRIPTION, NO SERVER

Tyvo has no backend. Your voice goes straight from your phone to the AI
provider you choose, using your own API key, and nowhere else. No account, no
sign-up, no analytics, no telemetry.

That also means no monthly fee. You pay your provider directly for what you
use — around $0.003 a minute of audio, so ten minutes of dictation a day comes
to a few dollars a year. A quiet week costs nothing.

You will need an API key from OpenAI, Mistral, xAI or Google. Tyvo does not
work without one, and does not provide one.

WORKS IN ANY LANGUAGE YOU SPEAK

Language is detected automatically — no setting to change when you switch. The
clean-up never translates: dictate in Russian and you get Russian back.

NOTHING GETS LOST

If a dictation cannot be transcribed — no signal, a provider hiccup — the
recording is kept so you can retry it, and the app tells you it is waiting.
Audio is deleted once it has been transcribed successfully.

OPEN SOURCE

Tyvo is GPLv3. The source is at github.com/asyschikov/tyvo, including the exact
commands to verify the privacy claims above for yourself.
```

## Category

Tools

## Tags

`keyboard`, `dictation`, `voice`, `productivity`

## Privacy policy URL

Needs a public URL. Options:

- GitHub raw: `https://github.com/asyschikov/tyvo/blob/main/PRIVACY.md`
- GitHub Pages, if you want something tidier

## Data safety answers

Tyvo's architecture makes this short:

| Question | Answer |
|---|---|
| Does your app collect or share user data? | **Yes** — shared, not collected |
| Audio: collected? | No |
| Audio: shared? | **Yes** — with the user's chosen AI provider, for transcription |
| Text/messages: collected? | No |
| Text/messages: shared? | **Yes** — with the same provider, for clean-up |
| Is data encrypted in transit? | **Yes** — HTTPS |
| Can users request deletion? | **Yes** — History has per-item delete; audio auto-expires |
| Is any data processed ephemerally? | Audio is deleted after transcription |

There is no "collected" data because nothing reaches a server belonging to this
app. Say so in the free-text box if Google offers one.

## Reviewer notes

Important — without this the app will be rejected as non-functional:

```
Tyvo requires the user's own AI provider API key; it has no backend and no
bundled credentials, so it does nothing until a key is entered.

To test:
1. Open the app and complete the welcome screen.
2. Paste the API key below into the provider field.
3. Grant microphone access and enable Tyvo in system keyboard settings.
4. Open any app with a text field, switch to Tyvo with the globe key, and
   hold the mic button while speaking.

Test key (Mistral): <paste a throwaway key here>
```

Generate a key for this and revoke it after review.

## Screenshots needed

At least 2, 1080×1920 or similar. Worth capturing:

1. The keyboard mid-dictation, waveform visible
2. The review strip with the quick-action chips
3. History
4. The welcome screen

`adb exec-out screencap -p > shot.png` is enough — Play accepts plain
screenshots.
