"""Times real clean-up calls: the app's prompt and a dictation, per model.

Requests go one at a time, models interleaved, so a slow minute on the API
hits every model alike. Rate-limited attempts are retried and not timed.

usage: secretspec run -S research -- python3 speed.py <rounds> <model> [<model> ...]
"""
import json, statistics, sys, time, urllib.error, urllib.request

import prompts
from run import KEY, need_key

SAMPLES = [
    "yo I'm gonna be like ten minutes late gotta grab food first",
    "can you send me the report by friday sorry no thursday",
    "um so I'm like dude no way and she goes yeah way lol",
    "wir treffen uns um acht äh nein um neun",
    "so basically the meeting got moved and um I think we should just push the release to next week "
    "because the QA team still hasn't signed off on the payment flow and honestly I don't want to "
    "ship that broken again like last time",
]


def timed(model, system, text):
    extra = {"reasoning_effort": "none"} if model.startswith(("mistral-small", "mistral-medium", "mistral-large")) else {}
    body = json.dumps({"model": model, "temperature": 0, "top_p": 1, **extra, "messages": [
        {"role": "system", "content": system},
        {"role": "user", "content": prompts.wrap_input(text)}]}).encode()
    for attempt in range(6):
        req = urllib.request.Request("https://api.mistral.ai/v1/chat/completions", body,
                                     {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"})
        t0 = time.monotonic()
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                d = json.load(r)
                return time.monotonic() - t0, d["usage"]["completion_tokens"]
        except urllib.error.HTTPError as e:
            if e.code == 429 or e.code >= 500:
                time.sleep(2 ** attempt)
                continue
            print(f"{model}: HTTP {e.code} {e.read()[:160]!r}", file=sys.stderr)
            return None, 0
    return None, 0


def main():
    need_key()
    rounds, models = int(sys.argv[1]), sys.argv[2:]
    system = prompts.clean_up_prompt()
    times = {m: [] for m in models}
    toks = {m: [] for m in models}
    for _ in range(rounds):
        for text in SAMPLES:
            for m in models:
                t, n = timed(m, system, text)
                if t is not None:
                    times[m].append(t)
                    toks[m].append(n)
    print(f"{'model':28} {'n':>3} {'median s':>9} {'p90 s':>7} {'min s':>6} {'max s':>6} {'out tok/s':>10}")
    for m in models:
        ts = sorted(times[m])
        if not ts:
            print(f"{m:28} no successful calls")
            continue
        p90 = ts[min(len(ts) - 1, int(len(ts) * 0.9))]
        rate = sum(toks[m]) / sum(ts)
        print(f"{m:28} {len(ts):3} {statistics.median(ts):9.2f} {p90:7.2f} {ts[0]:6.2f} {ts[-1]:6.2f} {rate:10.0f}")


if __name__ == "__main__":
    main()
