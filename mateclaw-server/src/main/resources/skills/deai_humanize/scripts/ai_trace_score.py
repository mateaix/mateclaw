#!/usr/bin/env python3
"""Heuristic scorer for AI-writing traces in Chinese text (stdlib only).

Reads a single JSON argument from argv[1], or from stdin if no argument
is given:

    {"text": "...", "platform": "gzh" | "xhs"}

Prints a JSON report to stdout:

    {
      "score": 0-100,          # higher = more AI-like
      "signals": [ {name, value, weight, note}, ... ],
      "spans":  ["offending substring", ...],
      "verdict": "human-like" | "some-ai" | "strong-ai"
    }

The score is a deterministic, explainable quality signal built from
surface features of the text. It is a writing-quality heuristic to guide
rewriting, NOT a guarantee about the output of any external AI detector.
"""

import sys
import json
import re

# --- Feature vocabularies ---------------------------------------------------

# Discourse connectors: a few are natural, but a high density reads as
# machine-organized "listy" prose.
CONNECTORS = [
    "首先", "其次", "再次", "然后", "最后", "综上所述", "总而言之",
    "总的来说", "总体而言", "值得注意的是", "需要注意的是",
    "由此可见", "换句话说", "简而言之", "一方面", "另一方面",
    "此外", "与此同时", "不仅如此",
]

# Cliché templates. Entries with "[^...]{0,n}" allow a bounded gap so
# "在<任意短语>的今天" and friends match without crossing a clause boundary.
CLICHE_PATTERNS = [
    r"在[^，。！？；\n]{0,12}的今天",
    r"在这个[^，。！？；\n]{0,10}的时代",
    r"随着[^，。！？；\n]{0,16}的(发展|到来|普及|推进)",
    r"让我们",
    r"赋能",
    r"注入(新的)?活力",
    r"[^，。！？；\n]{0,8}是[^，。！？；\n]{0,6}的关键",
    r"众所周知",
    r"不难发现",
    r"不可否认",
    r"数字化转型",
    r"深度融合",
    r"保驾护航",
    r"打造[^，。！？；\n]{0,6}新(生态|格局|范式|标杆)",
    r"开启[^，。！？；\n]{0,6}新(篇章|征程)",
]

# Concreteness markers: first-person voice, digits, and time words signal
# lived, specific writing rather than abstract summary.
FIRST_PERSON = ["我们", "咱们", "我", "咱"]
TIME_WORDS = [
    "今天", "昨天", "明天", "前天", "后天", "上周", "下周", "上午",
    "下午", "早上", "中午", "晚上", "凌晨", "刚才", "去年", "今年",
    "星期", "周一", "周二", "周三", "周四", "周五", "周六", "周日",
    "分钟", "小时", "点钟",
]

CJK_RE = re.compile(r"[一-鿿]")
SENT_SPLIT_RE = re.compile(r"[。！？；!?;\n]+")
DIGIT_RE = re.compile(r"[0-9０-９]")
DASH_RE = re.compile(r"[—–\-]{1,2}|•|·")

# Per-platform signal weights. They sum to 100 within each platform.
# xhs (Xiaohongshu) prizes short, bursty fragments, so sentence-length and
# paragraph-uniformity carry less weight there.
WEIGHTS = {
    "gzh": {
        "cliche": 28, "connector": 26, "concreteness": 14,
        "burstiness": 12, "list_dash": 8, "para_uniform": 12,
    },
    "xhs": {
        "cliche": 32, "connector": 24, "concreteness": 20,
        "burstiness": 6, "list_dash": 8, "para_uniform": 10,
    },
}

NEUTRAL = 0.3            # sub-score used when a signal lacks enough data
MIN_SENTS_FOR_BURST = 4  # burstiness needs enough sentences to be meaningful
MIN_PARAS_FOR_UNIFORM = 3


def clamp01(x):
    """Clamp a float into the [0, 1] range."""
    if x < 0.0:
        return 0.0
    if x > 1.0:
        return 1.0
    return x


def cjk_len(s):
    """Count CJK characters in a string."""
    return len(CJK_RE.findall(s))


def std(values):
    """Population standard deviation of a list of numbers."""
    n = len(values)
    if n == 0:
        return 0.0
    mean = sum(values) / n
    variance = sum((v - mean) ** 2 for v in values) / n
    return variance ** 0.5


def count_occurrences(text, needles):
    """Total non-overlapping occurrences of any needle, plus matched spans."""
    total = 0
    spans = []
    for needle in needles:
        start = 0
        while True:
            idx = text.find(needle, start)
            if idx < 0:
                break
            total += 1
            spans.append(needle)
            start = idx + len(needle)
    return total, spans


def score_text(text, platform):
    """Compute the AI-trace report for a piece of text."""
    weights = WEIGHTS.get(platform, WEIGHTS["gzh"])
    signals = []
    spans = []

    total_cjk = cjk_len(text)
    # Guard against empty / whitespace-only input.
    if total_cjk == 0:
        return {
            "score": 0,
            "signals": [],
            "spans": [],
            "verdict": "human-like",
        }
    per100 = total_cjk / 100.0  # divisor to express "hits per 100 CJK chars"

    sentences = [s for s in SENT_SPLIT_RE.split(text) if s.strip()]

    # 1) Connector density -------------------------------------------------
    conn_hits, conn_spans = count_occurrences(text, CONNECTORS)
    conn_value = clamp01((conn_hits / per100) / 2.5)  # target ~2.5 / 100 chars
    signals.append({
        "name": "connector_density",
        "value": round(conn_value, 3),
        "weight": weights["connector"],
        "note": f"{conn_hits} discourse connectors",
    })
    spans.extend(conn_spans)

    # 2) Cliché phrase hits ------------------------------------------------
    cliche_hits = 0
    for pattern in CLICHE_PATTERNS:
        for m in re.finditer(pattern, text):
            cliche_hits += 1
            spans.append(m.group(0))
    cliche_value = clamp01((cliche_hits / per100) / 1.2)  # target ~1.2 / 100
    signals.append({
        "name": "cliche_phrases",
        "value": round(cliche_value, 3),
        "weight": weights["cliche"],
        "note": f"{cliche_hits} cliché template hits",
    })

    # 3) Concreteness deficit ----------------------------------------------
    digits = len(DIGIT_RE.findall(text))
    fp_hits, _ = count_occurrences(text, FIRST_PERSON)
    time_hits, _ = count_occurrences(text, TIME_WORDS)
    concrete_raw = digits + fp_hits + time_hits
    richness = clamp01((concrete_raw / per100) / 6.0)  # target ~6 / 100 chars
    concrete_value = 1.0 - richness  # deficit: less concreteness = more AI
    signals.append({
        "name": "concreteness_deficit",
        "value": round(concrete_value, 3),
        "weight": weights["concreteness"],
        "note": f"{digits} digits, {fp_hits} first-person, {time_hits} time words",
    })

    # 4) Sentence-length burstiness ----------------------------------------
    lengths = [cjk_len(s) for s in sentences]
    lengths = [n for n in lengths if n > 0]
    if len(lengths) >= MIN_SENTS_FOR_BURST:
        mean = sum(lengths) / len(lengths)
        cv = (std(lengths) / mean) if mean > 0 else 0.0
        # Low coefficient of variation = uniform lengths = more AI-like.
        burst_value = clamp01(1.0 - cv / 0.5)
        note = f"{len(lengths)} sentences, cv={round(cv, 3)}"
    else:
        burst_value = NEUTRAL
        note = f"{len(lengths)} sentences (too few to judge)"
    signals.append({
        "name": "sentence_burstiness",
        "value": round(burst_value, 3),
        "weight": weights["burstiness"],
        "note": note,
    })

    # 5) List / dash overuse -----------------------------------------------
    dash_hits = len(DASH_RE.findall(text))
    bullet_lines = sum(
        1 for line in text.splitlines()
        if re.match(r"^\s*([-*•·\d]+[.、)]?)\s+", line)
    )
    list_raw = dash_hits + bullet_lines
    list_value = clamp01((list_raw / per100) / 2.0)  # target ~2 / 100 chars
    signals.append({
        "name": "list_dash_overuse",
        "value": round(list_value, 3),
        "weight": weights["list_dash"],
        "note": f"{dash_hits} dashes, {bullet_lines} bullet lines",
    })

    # 6) Paragraph-length uniformity ---------------------------------------
    paragraphs = [p for p in re.split(r"\n\s*\n", text) if p.strip()]
    para_lengths = [cjk_len(p) for p in paragraphs]
    para_lengths = [n for n in para_lengths if n > 0]
    if len(para_lengths) >= MIN_PARAS_FOR_UNIFORM:
        mean = sum(para_lengths) / len(para_lengths)
        cv = (std(para_lengths) / mean) if mean > 0 else 0.0
        para_value = clamp01(1.0 - cv / 0.5)
        note = f"{len(para_lengths)} paragraphs, cv={round(cv, 3)}"
    else:
        para_value = NEUTRAL
        note = f"{len(para_lengths)} paragraphs (too few to judge)"
    signals.append({
        "name": "paragraph_uniformity",
        "value": round(para_value, 3),
        "weight": weights["para_uniform"],
        "note": note,
    })

    # --- Aggregate --------------------------------------------------------
    score = sum(s["value"] * s["weight"] for s in signals)
    score = int(round(clamp01(score / 100.0) * 100))

    if score < 40:
        verdict = "human-like"
    elif score < 60:
        verdict = "some-ai"
    else:
        verdict = "strong-ai"

    # De-duplicate spans while preserving order, and cap the list length.
    seen = set()
    unique_spans = []
    for sp in spans:
        if sp not in seen:
            seen.add(sp)
            unique_spans.append(sp)
    unique_spans = unique_spans[:30]

    return {
        "score": score,
        "signals": signals,
        "spans": unique_spans,
        "verdict": verdict,
    }


def load_input():
    """Load the JSON payload from argv[1] or stdin."""
    if len(sys.argv) > 1 and sys.argv[1].strip():
        raw = sys.argv[1]
    else:
        raw = sys.stdin.read()
    if not raw or not raw.strip():
        return {"text": "", "platform": "gzh"}
    return json.loads(raw)


def main():
    try:
        payload = load_input()
    except (ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"error": f"invalid JSON input: {exc}"}, ensure_ascii=False))
        sys.exit(1)

    text = payload.get("text", "") or ""
    platform = payload.get("platform", "gzh") or "gzh"
    if platform not in WEIGHTS:
        platform = "gzh"

    report = score_text(text, platform)
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
