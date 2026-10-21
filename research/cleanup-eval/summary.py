import json, sys, collections
rs = [r for f in sys.argv[1:] for r in json.load(open(f)) if not r["output"].startswith("<<")]
models = list(dict.fromkeys(r["model"] for r in rs))
cats = list(dict.fromkeys(r["cat"] for r in rs))
print("model".ljust(24), "pass".rjust(6), "untouched".rjust(10), "w-removed".rjust(10), "w-added".rjust(8), "| fails by category")
for m in models:
    x = [r for r in rs if r["model"] == m]
    bycat = collections.Counter(r["cat"] for r in x if not r["ok"])
    print(f"{x[0]['variant']+'/'+m:24} {sum(r['ok'] for r in x):3}/{len(x):<3} {sum(not r['removed'] and not r['added'] for r in x):10} {sum(len(r['removed']) for r in x):10} {sum(len(r['added']) for r in x):8} | " + ", ".join(f"{c}:{bycat[c]}" for c in cats if bycat[c]))
lost = collections.Counter(k for r in rs for k in r["lost"])
print("\nmost-lost protected words:", lost.most_common(25))
