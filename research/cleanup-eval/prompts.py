"""Builds the clean-up prompt exactly as the app does, by reading the Kotlin
sources and emulating Kotlin's string templates + trimIndent()."""
import re
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / "app/src/main/kotlin/com/tyvo/keyboard/polish"


def trim_indent(s: str) -> str:
    lines = s.split("\n")
    if lines and lines[0].strip() == "":
        lines = lines[1:]
    if lines and lines[-1].strip() == "":
        lines = lines[:-1]
    nonblank = [l for l in lines if l.strip()]
    m = min((len(l) - len(l.lstrip(" \t")) for l in nonblank), default=0)
    return "\n".join(l[m:] if l.strip() else l[m:] if len(l) >= m else "" for l in lines)


def corrections():
    src = (SRC / "Correction.kt").read_text()
    out = []
    for m in re.finditer(
        r'\n    ([A-Z_]+)\(\s*id = "([a-z_]+)".*?defaultOn = (true|false),\s*(?://[^\n]*\n\s*)*instruction = """(.*?)"""\.trimIndent\(\)',
        src, re.S):
        out.append(dict(name=m[1], id=m[2], default=m[3] == "true", instruction=trim_indent(m[4])))
    return out


def _prompts_src():
    return (SRC / "Prompts.kt").read_text()


def clean_up_prompt(enabled_ids=None) -> str:
    cs = corrections()
    if enabled_ids is None:
        enabled_ids = {c["id"] for c in cs if c["default"]}
    enabled = [c for c in cs if c["id"] in enabled_ids]
    src = _prompts_src()
    body = src[src.index("fun cleanUp("):src.index("fun transform(")]
    if not enabled:
        steps = ("Return the transcript unchanged apart from obvious transcription\n"
                 "errors. Do not restyle, shorten or punctuate it.")
    else:
        steps = "\n".join(f"{i + 1}. " + c["instruction"].replace("\n", "\n   ")
                          for i, c in enumerate(enabled))
    block = lambda name: trim_indent(re.search(
        rf'val {name} = (?:if \(.*?\) \{{\s*)?"""(.*?)"""\.trimIndent\(\)', body, re.S)[1])
    parts = [block("intro")]
    if "spoken_edits" in enabled_ids:
        parts.append(block("headline"))
    note = re.search(r'val note = ((?:\s*"(?:[^"\\]|\\.)*"\s*\+?)+)\n\n', body)
    if note:
        parts.append("".join(re.findall(r'"((?:[^"\\]|\\.)*)"', note[1])))
    parts += ["Apply, in order:\n\n" + steps, block("rules")]
    return "\n\n".join(parts)


def transform_prompt(instruction: str) -> str:
    src = _prompts_src()
    body = src[src.index("fun transform("):]
    lang = trim_indent(re.search(r'\} else \{\s*"""(.*?)"""\.trimIndent\(\)', body, re.S)[1])
    tpl = re.search(r'return """(.*?)"""\.trimIndent\(\)', body, re.S)[1]
    return trim_indent(tpl.replace("$rule", lang.replace("\n", "\n        "))
                       .replace("$instruction", instruction))


def quick_action(action_id: str) -> str:
    src = (SRC.parent / "actions" / "QuickAction.kt").read_text()
    m = re.search(rf'id = "{action_id}",.*?instruction = (.*?),\n\s*group', src, re.S)
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', m[1])
    return "".join(parts).encode().decode("unicode_escape").encode("latin-1").decode("utf-8")


def wrap_input(text: str) -> str:
    src = _prompts_src()
    tpl = re.search(r'fun wrapInput\(text: String\): String = """(.*?)"""\.trimIndent\(\)', src, re.S)[1]
    return trim_indent(tpl.replace("$text", text))


def strip_dashes(text: str) -> str:
    text = re.sub(r"\s+[—–]\s+", ", ", text)
    text = re.sub(r"(?<=[a-z,;:])[—–](?=[A-Z])", ". ", text)
    text = text.replace("—", ", ").replace("–", ", ")
    text = re.sub(r",\s*,", ",", text)
    text = re.sub(r"\s+,", ",", text)
    return text


if __name__ == "__main__":
    print(clean_up_prompt())
    print("=" * 60)
    print(wrap_input("hello there"))
