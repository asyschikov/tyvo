"""Labels every word-level edit as acceptable or overreach.

Acceptable: punctuation/case/apostrophes (invisible after word normalisation),
dropping hesitation filler, dropping what a case marks as `drop` (executed
corrections, spoken punctuation, stutters).
Overreach: DROP (a real word removed), SUB (a word replaced), ADD (a word
invented), plus FAIL on correction cases whose `drop` text leaked through.

usage: python3 classify.py results/a.json [results/b.json ...] [-v]
"""
import difflib, json, sys, collections
import importlib, os
CASES = [c for m in os.environ.get('CASES', 'cases').split(',')
         for c in importlib.import_module(m).CASES]
from run import words

FILLER = {"um", "uh", "er", "erm", "ah", "hmm", "like"}
BY_ID = {c["id"]: c for c in CASES}
# Grammar fixes the speaker would make when typing -- fine at the light level.
GRAMMAR_OK = {("was", "were"), ("dont", "doesnt"), ("we", "were"),
              ("nothing", "anything"), ("cannot", "cant")}


def edits(r):
    case = BY_ID[r["id"]]
    iw, ow = words(r["input"]), words(r["output"])
    ok_drop = {w for d in case.get("drop", []) for w in words(d)} | FILLER
    out = []
    for op, i1, i2, j1, j2 in difflib.SequenceMatcher(None, iw, ow, autojunk=False).get_opcodes():
        a, b = iw[i1:i2], ow[j1:j2]
        if op == "equal":
            continue
        if op == "delete":
            # An executed correction removes the cue and the replaced words
            # together; fine as long as nothing the speaker meant went too.
            drops = {w for d in case.get("drop", []) for w in words(d)}
            keeps = {w for k in case["keep"] for alt in k.split("|") for w in words(alt)}
            if drops & set(a) and not keeps & set(a):
                continue
            bad = [w for w in a if w not in ok_drop]
            if bad:
                out.append(("DROP", " ".join(a), ""))
        elif op == "insert":
            out.append(("ADD", "", " ".join(b)))
        else:  # replace
            core = [w for w in a if w not in ok_drop]
            if core == b:  # only filler went
                continue
            if len(core) == len(b) and all(x == y or (x, y) in GRAMMAR_OK for x, y in zip(core, b)):
                continue
            out.append(("SUB", " ".join(a), " ".join(b)))
    first = next((ch for ch in r["output"] if ch.isalpha()), "")
    if first.islower():
        out.append(("CASE", "no capital", ""))
    if r["leaked"]:
        out.append(("FAIL", "kept " + ",".join(r["leaked"]), ""))
    return out


def main():
    verbose = "-v" in sys.argv
    files = [f for f in sys.argv[1:] if f != "-v"]
    rows = [r for f in files for r in json.load(open(f)) if not r["output"].startswith("<<")]
    groups = collections.defaultdict(list)
    for r in rows:
        groups[(r["variant"], r["model"])].append(r)
    print(f"{'variant/model':42} {'n':>3} {'clean':>5} {'DROP':>5} {'SUB':>4} {'ADD':>4} {'FAIL':>4} {'CASE':>4}")
    for (v, m), rs in groups.items():
        cnt = collections.Counter()
        clean = 0
        for r in rs:
            e = edits(r)
            clean += not e
            cnt.update(k for k, *_ in e)
        print(f"{v + '/' + m:42} {len(rs):3} {clean:5} {cnt['DROP']:5} {cnt['SUB']:4} {cnt['ADD']:4} {cnt['FAIL']:4} {cnt['CASE']:4}")
        if verbose:
            for r in rs:
                for k, a, b in edits(r):
                    print(f"    {k:4} {r['id']:18} {a!r} -> {b!r}")


if __name__ == "__main__":
    main()
