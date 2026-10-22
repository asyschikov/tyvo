"""Runs every case through Mistral models and scores how much was changed.

usage: python3 run.py <variant> <model> [<model> ...]
  variant: 'current' (the app's prompt) or a path to a .txt system prompt
key: $MISTRAL_API_KEY, injected by `secretspec run -S research -- python3 run.py ...`
"""
import json, os, re, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import prompts
import importlib, os
CASES = [c for m in os.environ.get('CASES', 'cases').split(',')
         for c in importlib.import_module(m).CASES]

HERE = Path(__file__).parent
KEY = os.environ.get("MISTRAL_API_KEY")


def need_key():
    if not KEY:
        sys.exit("MISTRAL_API_KEY is not set. Run via: secretspec run -S research -- python3 run.py ...")


def call(model, system, user):
    extra = {"reasoning_effort": "none"} if model.startswith(("mistral-small", "mistral-medium", "mistral-large")) else {}
    body = json.dumps({"model": model, "temperature": 0, "top_p": 1, **extra, "messages": [
        {"role": "system", "content": system},
        {"role": "user", "content": prompts.wrap_input(user)}]}).encode()
    for attempt in range(6):
        req = urllib.request.Request("https://api.mistral.ai/v1/chat/completions", body,
                                     {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                out = json.load(r)["choices"][0]["message"]["content"].strip()
                return prompts.strip_dashes(out) or user
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(2 ** attempt)
                continue
            return f"<<HTTP {e.code}: {e.read()[:200]!r}>>"
    return "<<gave up>>"


def norm(s):
    return s.lower().replace("’", "'").replace("‘", "'")


def words(s):
    return re.findall(r"[\w']+", norm(s).replace("'", ""))


def has(out, phrase):
    o = " " + " ".join(words(out)) + " "
    return any(" " + " ".join(words(alt)) + " " in o for alt in phrase.split("|"))


def score(case, out):
    lost = [k for k in case["keep"] if not has(out, k)]
    leaked = [d for d in case.get("drop", []) if has(out, d.strip())]
    forbid = [f for f in case.get("forbid", []) if has(out, f)]
    iw, ow = words(case["text"]), words(out)
    removed = [w for w in iw if w not in ow]
    added = [w for w in ow if w not in iw]
    return dict(lost=lost, leaked=leaked, forbid=forbid, removed=removed, added=added,
                ok=not (lost or leaked or forbid))


def main():
    need_key()
    variant, models = sys.argv[1], sys.argv[2:]
    system = prompts.clean_up_prompt() if variant == "current" else Path(variant).read_text()
    vname = ("current" if variant == "current" else Path(variant).stem) + os.environ.get("TAG", "")
    results = []
    only = os.environ.get("LANG_ONLY")
    cat = os.environ.get("CAT_ONLY")
    jobs = [(m, c) for m in models for c in CASES if (not only or c.get("lang") == only) and (not cat or c["cat"] == cat)]
    with ThreadPoolExecutor(3) as ex:
        outs = list(ex.map(lambda mc: call(mc[0], system, mc[1]["text"]), jobs))
    for (m, c), out in zip(jobs, outs):
        results.append(dict(variant=vname, model=m, id=c["id"], cat=c["cat"],
                            input=c["text"], output=out, **score(c, out)))
    (HERE / "results").mkdir(exist_ok=True)
    path = HERE / "results" / f"{vname}__{'+'.join(models)}.json"
    path.write_text(json.dumps(results, ensure_ascii=False, indent=1))
    for m in models:
        rs = [r for r in results if r["model"] == m]
        print(f"\n### {vname} / {m}: {sum(r['ok'] for r in rs)}/{len(rs)} pass, "
              f"{sum(len(r['removed']) for r in rs)} words removed, "
              f"{sum(len(r['added']) for r in rs)} added")
        for r in rs:
            if not r["ok"] or r["removed"] or r["added"]:
                flag = "FAIL" if not r["ok"] else "diff"
                print(f"[{flag}] {r['id']}: {r['output']!r}")
                det = []
                if r["lost"]: det.append(f"lost={r['lost']}")
                if r["leaked"]: det.append(f"leaked={r['leaked']}")
                if r["forbid"]: det.append(f"forbid={r['forbid']}")
                det.append(f"-{r['removed']} +{r['added']}")
                print("       " + "  ".join(det))
    print(f"\nsaved {path}")


if __name__ == "__main__":
    main()
