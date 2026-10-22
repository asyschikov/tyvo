"""Compares Mistral transcription models on synthesized dictation clips.

Clips come from macOS `say`, so they are clean studio speech: this measures
whether a model keeps what was said (slang, spoken numbers, corrections), not
how it copes with noise or accents.

usage: secretspec run -S research -- python3 transcribe_eval.py <audio-dir> <model> [<model> ...]
"""
import json, os, re, subprocess, sys, time, urllib.error, urllib.request, uuid
from pathlib import Path

from run import need_key, KEY

# (id, macOS voice, text spoken, words that must come back as said)
CLIPS = [
    ("en-gonna", "Samantha", "yo I'm gonna be like ten minutes late, gotta grab food first", ["gonna", "gotta", "ten"]),
    ("en-corr", "Samantha", "meet me at the bar at nine, I mean ten, bro", ["nine", "i mean", "ten", "bro"]),
    ("en-story", "Samantha", "so I'm like dude no way, and she goes yeah way", ["i'm like", "dude", "she goes"]),
    ("en-num", "Samantha", "it costs twenty five euros, and it's the third of May", ["twenty five", "third"]),
    ("de-alter", "Anna", "Alter, das war halt echt krass gestern, ne", ["alter", "halt", "krass", "ne"]),
    ("de-haste", "Anna", "haste mal kurz Zeit? Ich hab heute keinen Bock", ["haste", "hab", "keinen bock"]),
    ("de-corr", "Anna", "wir treffen uns um acht, äh nein, um neun", ["acht", "neun"]),
    ("nl-ff", "Xander", "ik heb er ff geen zin in, joh", ["ff", "geen zin", "joh"]),
    ("nl-quote", "Xander", "en ik zo wat, en hij zo boeiend", ["ik zo", "hij zo", "boeiend"]),
    ("nl-corr", "Xander", "we eten om zes, nee wacht, om zeven", ["zes", "zeven"]),
    ("ru-shchas", "Milena", "короче, я щас приду, подожди", ["короче", "щас"]),
    ("ru-vashche", "Milena", "ну чё ты как ваще, норм всё?", ["чё", "ваще", "норм"]),
    ("ru-corr", "Milena", "встречаемся в семь, то есть в восемь", ["семь", "то есть", "восемь"]),
]


def norm(s):
    return re.findall(r"[\w']+", s.lower().replace("’", "'").replace("ё", "е"))


def wer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
    return d[len(h)] / max(1, len(r))


def kept(hyp, phrase):
    return " " + " ".join(norm(phrase)) + " " in " " + " ".join(norm(hyp)) + " "


def synthesize(audio_dir: Path):
    audio_dir.mkdir(parents=True, exist_ok=True)
    for cid, voice, text, _ in CLIPS:
        wav = audio_dir / f"{cid}.wav"
        if wav.exists():
            continue
        aiff = audio_dir / f"{cid}.aiff"
        subprocess.run(["say", "-v", voice, "-o", str(aiff), text], check=True)
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@16000", str(aiff), str(wav)], check=True)
        aiff.unlink()


def transcribe(model, wav: Path):
    boundary = uuid.uuid4().hex
    body = b"".join([
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n{model}\r\n".encode(),
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{wav.name}\"\r\n"
        "Content-Type: audio/wav\r\n\r\n".encode(), wav.read_bytes(), b"\r\n",
        f"--{boundary}--\r\n".encode(),
    ])
    req = urllib.request.Request(
        "https://api.mistral.ai/v1/audio/transcriptions", body,
        {"Authorization": f"Bearer {KEY}", "Content-Type": f"multipart/form-data; boundary={boundary}"})
    for attempt in range(5):
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                d = json.load(r)
                return dict(text=d.get("text", "").strip(), language=d.get("language"),
                            seconds=round(time.monotonic() - t0, 2), status=200)
        except urllib.error.HTTPError as e:
            if e.code == 429 and attempt < 4:
                time.sleep(2 ** attempt)
                continue
            return dict(text="", language=None, seconds=None, status=e.code,
                        error=e.read()[:200].decode(errors="replace"))
    return dict(text="", language=None, seconds=None, status=429)


def main():
    need_key()
    audio_dir, models = Path(sys.argv[1]), sys.argv[2:]
    synthesize(audio_dir)
    rows = []
    for model in models:
        for cid, _, text, must in CLIPS:
            r = transcribe(model, audio_dir / f"{cid}.wav")
            r.update(model=model, id=cid, reference=text,
                     wer=round(wer(text, r["text"]), 3) if r["status"] == 200 else None,
                     lost=[m for m in must if not kept(r["text"], m)] if r["status"] == 200 else must)
            rows.append(r)
    Path("results").mkdir(exist_ok=True)
    Path("results/transcribe.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))

    print(f"{'model':32} {'ok':>5} {'mean WER':>9} {'words lost':>11} {'median s':>9} {'language set':>13}")
    for model in models:
        x = [r for r in rows if r["model"] == model]
        ok = [r for r in x if r["status"] == 200]
        secs = sorted(r["seconds"] for r in ok)
        print(f"{model:32} {len(ok):>2}/{len(x):<2} "
              f"{(sum(r['wer'] for r in ok) / len(ok) if ok else float('nan')):9.3f} "
              f"{sum(len(r['lost']) for r in ok):11} "
              f"{(secs[len(secs) // 2] if secs else float('nan')):9.2f} "
              f"{sum(r['language'] is not None for r in ok):>8}/{len(ok)}")
        for r in x:
            if r["status"] != 200:
                print(f"    {r['id']:11} HTTP {r['status']} {r.get('error', '')[:100]}")
            elif r["lost"]:
                print(f"    {r['id']:11} lost {r['lost']}: {r['text']!r}")


if __name__ == "__main__":
    main()
