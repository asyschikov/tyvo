# Releasing

## One-time setup

### 1. Create the upload keystore

This key signs every release. **If you lose it you can never update the app
again** — Play will reject a bundle signed by anything else. Back it up
somewhere durable before you do anything else.

```sh
mkdir -p ~/keys
keytool -genkeypair -v \
  -keystore ~/keys/tyvo-upload.jks \
  -alias tyvo \
  -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and a name; the rest can be left blank. Keep it outside
the repo — a stray `git add -f` is all it takes to publish a signing key.

### 2. Put the key and its passwords in SecretSpec

`secretspec.toml` in the repo declares what is needed; the values live in your
OS keychain. Once per machine:

```sh
secretspec config global init --provider keyring --profile default
```

Then store the secrets. The keystore *file itself* goes in, not just a path to
it — so the `.jks` never has to sit in the working tree:

```sh
secretspec set TYVO_KEYSTORE_PATH --from-file ~/keys/tyvo-upload.jks
printf '%s' 'your-keystore-password' | secretspec set TYVO_KEYSTORE_PASSWORD --from-file -
printf '%s' 'your-key-password'      | secretspec set TYVO_KEY_PASSWORD --from-file -
printf '%s' 'tyvo'                   | secretspec set TYVO_KEY_ALIAS --from-file -
```

Use `printf '%s' | ... --from-file -`, not a here-string: `<<<` appends a
newline and it gets stored as part of the password.

At build time `as_path` decodes the keystore to a temp file and hands Gradle
its path. The path is different on every run, so nothing can cache it.

```sh
secretspec check          # all four resolve?
```

Anything that exports environment variables works just as well — the build
only reads `System.getenv`.

## Building a release

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

secretspec run -- ./gradlew :app:bundleRelease
```

The bundle lands at `app/build/outputs/bundle/release/app-release.aab` — about
3.4 MB, versus 61 MB for a debug APK, because release builds are minified.

Without the signing variables the same command still builds, just unsigned.
That is deliberate: a plain `./gradlew assembleRelease` should not fail on a
machine that has no keys.

### Bump the version first

`app/build.gradle.kts`:

```kotlin
versionCode = 2        // must increase for every upload
versionName = "0.2.0"  // what users see
```

## Before you upload

Minification can break things that compile perfectly. Test the *release* build,
not the debug one:

```sh
secretspec run -- ./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Then check the things R8 is most likely to have stripped:

- The keyboard appears in system keyboard settings (`adb shell ime list -a -s`)
- Settings opens and saves an API key — the encrypted store uses Tink, which is
  reflection-heavy
- A dictation completes end to end

The keep rules in `app/proguard-rules.pro` exist because R8 cannot see
manifest-declared classes. The IME service is referenced only from the
manifest, so without its rule the release build produces a keyboard that is
simply absent — and nothing warns you.

## Play Console

The parts only you can do:

1. **Account** — $25 one-off, plus ID verification. New personal accounts must
   run a closed test with **12+ testers for 14 days** before production is
   unlocked. This is the longest step; start it early.
2. **Play App Signing** — accept it. Google holds the app signing key; yours
   becomes the upload key, which can be reset if lost. The upload key still
   deserves a backup.
3. **Listing** — icon (512×512), feature graphic (1024×500), at least two
   screenshots, short and full description, and a privacy policy at a public
   URL.
4. **Data safety** — declare that audio and transcripts go to the user's chosen
   AI provider. Tyvo collects nothing itself, which makes most of this form
   "not collected" and the provider calls "shared with third parties".
5. **Permissions** — `RECORD_AUDIO` on a keyboard draws scrutiny. Say plainly
   that audio goes to the user's own API provider and nowhere else; the
   no-backend architecture is the strongest thing you have here.

### Expect a review question about the API key

The app does nothing until the user supplies their own key, so a reviewer will
open it and hit a wall. Put that in the reviewer notes with a test key, or they
will reject it as non-functional.
