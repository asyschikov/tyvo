"""End to end: speak each case, transcribe it asking Voxtral for the language,
then clean up the transcript twice -- with generic+, and with the native
prompt for the language Voxtral reported (generic+ for any other language).

Both prompts see the same transcript, so transcription errors cost them
equally and only the prompt choice differs.

usage: secretspec run -S research -- python3 e2e_lang.py <audio-dir> [<clean-up model>]
"""
import json, re, sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cases, lang_cases, prompts
from run import call, need_key, score
from transcribe_eval import synthesize_one, transcribe

HERE = Path(__file__).parent
TRANSCRIBER = "voxtral-small-transcribe-3"
NATIVE = {"en": "english", "de": "german", "nl": "dutch", "ru": "russian"}
VOICE = {"german": "Anna", "dutch": "Xander", "russian": "Milena", "english": "Samantha"}


def lang_of(case):
    if case.get("lang"):
        return case["lang"]
    return "russian" if re.search("[а-яё]", case["text"], re.I) else "english"


def main():
    need_key()
    audio_dir = Path(sys.argv[1])
    model = sys.argv[2] if len(sys.argv) > 2 else "mistral-small-2603"
    all_cases = cases.CASES + lang_cases.CASES
    for c in all_cases:
        synthesize_one(audio_dir, c["id"], VOICE[lang_of(c)], c["text"])

    with ThreadPoolExecutor(3) as ex:
        heard = list(ex.map(lambda c: transcribe(TRANSCRIBER, audio_dir / f"{c['id']}.wav",
                                                 return_language=True), all_cases))

    generic = prompts.clean_up_prompt()
    native = {code: (HERE / f"variants/lang_{name}.txt").read_text() for code, name in NATIVE.items()}

    def both(i):
        c, h = all_cases[i], heard[i]
        system = native.get(h["language"], generic)
        return call(model, generic, h["text"]), call(model, system, h["text"])

    with ThreadPoolExecutor(3) as ex:
        outs = list(ex.map(both, range(len(all_cases))))

    rows = []
    for c, h, (g, n) in zip(all_cases, heard, outs):
        for variant, out in (("generic+", g), ("native", n)):
            rows.append(dict(variant=variant, model=model, id=c["id"], cat=c["cat"],
                             lang=lang_of(c), detected=h["language"], input=h["text"],
                             spoken=c["text"], output=out, **score(c, out)))
    (HERE / "results").mkdir(exist_ok=True)
    path = HERE / "results" / f"e2e_lang__{model}.json"
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=1))

    wrong = [(c["id"], lang_of(c), h["language"]) for c, h in zip(all_cases, heard)
             if NATIVE.get(h["language"]) != lang_of(c)]
    print(f"language: {len(all_cases) - len(wrong)}/{len(all_cases)} detected right; wrong: {wrong}")
    print(f"saved {path}")


if __name__ == "__main__":
    main()
