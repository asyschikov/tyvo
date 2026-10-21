"""Runs the Written quick action over light clean-up output, as the app does.

Heavy is judged differently from light: it may restructure, so instead of
counting changed words it checks that tone markers survive, that spoken
narration became written, that executed corrections stay executed, and that
length stays in the same ballpark.

usage: python3 heavy.py <light-results.json> <source-model> <model> [<model> ...]
"""
import json, sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import prompts
from run import call, has, need_key, words

# id -> (tone words that must survive, spoken forms that should be gone)
EXPECT = {
    "swear-1": (["shit", "fucked"], []),
    "swear-2": (["crap", "fucking awesome", "dude"], []),
    "swear-3": (["damn", "bro"], []),
    "no-cap": (["bro", "no cap|deadass"], []),
    "ngl-lowkey": (["fire", "slapped"], []),
    "brit": (["mate", "knackered"], []),
    "yeah-nah": (["nah", "dude"], []),
    "omg-ugh": (["annoying"], []),
    "ramble": (["bro", "dude", "what the hell", "sandwich"], ["im like", "he goes"]),
    "like-quote": (["whatever"], ["shes like", "im like"]),
    "quote-like": (["no way", "yes way"], ["im like", "shes like"]),
    "quote-goes": (["are you serious", "obviously"], ["he goes", "i go"]),
    "quote-was-like": (["wait what", "whatever"], ["was all like"]),
    "h-all-like": (["what do you mean"], ["im all like", "hes like"]),
    "h-tells-me": (["quit", "good for you"], []),
    "hedges": (["fine"], []),
    "i-mean-discourse": (["not that bad"], []),
    "real-correction": (["ten"], []),
    "corr-no-wait": (["mike"], []),
    "h-corr-slang": (["sprite"], []),
    "ru-quote": (["такая|такой|говорит|сказал"], []),
    "h-ru-takaya": (["отстань"], []),
    "ru-2": (["капец|в шоке"], []),
    "imperative": (["joke"], []),
    "question-to-friend": (["pic"], []),
}
FORBID = {"real-correction": ["nine"], "corr-no-wait": ["john"], "h-corr-slang": ["coke"]}


def main():
    need_key()
    src, src_model, models = sys.argv[1], sys.argv[2], sys.argv[3:]
    light = {r["id"]: r for r in json.load(open(src)) if r["model"] == src_model}
    import os
    instr = Path(os.environ["INSTR"]).read_text().strip() if os.environ.get("INSTR") else prompts.quick_action("written")
    system = prompts.transform_prompt(instr)
    jobs = [(m, cid) for m in models for cid in EXPECT if cid in light]
    with ThreadPoolExecutor(3) as ex:
        outs = list(ex.map(lambda j: call(j[0], system, light[j[1]]["output"]), jobs))
    rows = []
    for (m, cid), out in zip(jobs, outs):
        tone, spoken = EXPECT[cid]
        lost = [t for t in tone if not has(out, t)]
        kept_spoken = [s for s in spoken if has(out, s)]
        leaked = [f for f in FORBID.get(cid, []) if has(out, f)]
        ratio = len(words(out)) / max(1, len(words(light[cid]["output"])))
        rows.append(dict(model=m, id=cid, light=light[cid]["output"], output=out,
                         lost=lost, spoken=kept_spoken, leaked=leaked, ratio=round(ratio, 2)))
    Path(f"results/heavy_{Path(os.environ.get('INSTR','shipped')).stem}.json").write_text(json.dumps(rows, ensure_ascii=False, indent=1))
    for m in models:
        rs = [r for r in rows if r["model"] == m]
        print(f"\n### written / {m}: tone lost in {sum(bool(r['lost']) for r in rs)}, "
              f"spoken narration kept in {sum(bool(r['spoken']) for r in rs)}, "
              f"corrections undone {sum(bool(r['leaked']) for r in rs)}, "
              f"length outside 0.6-1.3x {sum(not .6 <= r['ratio'] <= 1.3 for r in rs)}  (n={len(rs)})")
        for r in rs:
            flags = [f"lost={r['lost']}"] * bool(r["lost"]) + [f"still-spoken={r['spoken']}"] * bool(r["spoken"]) \
                + [f"UNDID={r['leaked']}"] * bool(r["leaked"]) + [f"len×{r['ratio']}"] * (not .6 <= r["ratio"] <= 1.3)
            print(f"  {r['id']:16} {r['light'][:70]!r}\n  {'':16} => {r['output']!r}  {' '.join(flags)}")


if __name__ == "__main__":
    main()
