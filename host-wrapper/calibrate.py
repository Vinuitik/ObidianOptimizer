"""Live provider calibration — burns (small amounts of) real quota by design.

Three experiments, all against the REAL provider APIs using the keys in the
root .env. Results are printed as tables and appended as JSON to
host-wrapper/calibration/ so runs can be compared over time.

  python calibrate.py probe
      One tiny text call + one single-image vision call per configured
      provider. Answers: does this key work, and can this model actually
      accept an attached image over the API?

  python calibrate.py batch [--counts 1,2,4,8] [--providers gemini,groq]
      Sends N vault images in ONE request and asks for a JSON array with one
      transcription per image. Measures wall latency, per-image latency, and
      whether the model kept the images separate (array length == N) —
      the data needed to pick images-per-request per provider.

  python calibrate.py prompts [--providers ...] [--image path]
      Runs every PROMPT_VARIANTS entry against the same image on each
      provider, side by side, for human judgment of extraction quality.

Run from host-wrapper/:  python calibrate.py probe
"""
import argparse
import base64
import difflib
import json
import os
import random
import sys
import time
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv

# Windows consoles often default to cp1251/cp437 which can't print ✓ → etc.
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

_ROOT_ENV = Path(__file__).resolve().parent.parent / ".env"
load_dotenv(_ROOT_ENV)
load_dotenv(Path(__file__).resolve().parent / ".env", override=True)

import requests  # noqa: E402

import llm_router  # noqa: E402
from llm_router import _build_providers  # noqa: E402

RESULTS_DIR = Path(__file__).resolve().parent / "calibration"
VAULT = Path((os.environ.get("VAULT_HOST_PATH")
              or os.environ["HOST_VAULT_PATH"]).replace("\\", "/"))
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".gif"}
MEDIA_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
               ".gif": "image/gif", ".webp": "image/webp"}

# Candidate extraction prompts — judged side by side via `prompts` mode.
PROMPT_VARIANTS = {
    "current": (
        "Extract all visible text exactly as written. "
        "If this is a diagram, chart, or visual structure, describe its key elements "
        "and relationships concisely. Output only the extracted content, no preamble."
    ),
    "ocr-strict": (
        "Transcribe every piece of visible text verbatim, preserving line breaks, "
        "code formatting, and mathematical notation. Do not summarize, do not "
        "describe, do not add anything that is not literally written in the image."
    ),
    "structured-md": (
        "Convert this image to markdown. Text becomes markdown text (headings as #, "
        "code as fenced blocks, tables as markdown tables, math as $...$). Diagrams "
        "become a nested bullet outline of nodes and their connections. "
        "Output only the markdown."
    ),
    "diagram-deep": (
        "If the image contains text, transcribe it exactly. If it contains a diagram, "
        "chart or graph: name the diagram type, list every labeled element, then "
        "describe each relationship/arrow/grouping as 'A -> B: label'. Be exhaustive "
        "about structure, terse in wording. No preamble."
    ),
}

BATCH_PROMPT = (
    "You are given {n} images. Transcribe each one separately. "
    "Return ONLY a JSON array of {n} strings, where element i is the full "
    "extracted text/description of image i in the order the images appear. "
    "No markdown fences, no commentary — raw JSON only."
)


# ── plumbing ─────────────────────────────────────────────────────────────

def configured_providers(names=None, capability="vision"):
    provs = [p for p in _build_providers().values()
             if p.configured and p.kind != "cli" and p.supports(capability)]
    if names:
        provs = [p for p in provs if p.name in names]
    return provs


def b64_image(path):
    return base64.standard_b64encode(path.read_bytes()).decode()


def media_type(path):
    return MEDIA_TYPES.get(path.suffix.lower(), "image/png")


def call_vision_multi(provider, prompt, image_paths, max_tokens=4096):
    """One request, N images. Returns (text, latency_s)."""
    start = time.time()
    if provider.kind == "anthropic":
        content = [{"type": "image",
                    "source": {"type": "base64", "media_type": media_type(p),
                               "data": b64_image(p)}} for p in image_paths]
        content.append({"type": "text", "text": prompt})
        text = llm_router._anthropic_messages(
            provider, [{"role": "user", "content": content}], max_tokens=max_tokens)
    else:
        parts = [{"type": "text", "text": prompt}]
        parts += [{"type": "image_url",
                   "image_url": {"url": f"data:{media_type(p)};base64,{b64_image(p)}"}}
                  for p in image_paths]
        text = llm_router._openai_chat(
            provider, provider.vision_model,
            [{"role": "user", "content": parts}], max_tokens)
    return text, time.time() - start


def call_text(provider, prompt, max_tokens=100):
    start = time.time()
    if provider.kind == "anthropic":
        text = llm_router._anthropic_messages(
            provider, [{"role": "user", "content": prompt}], max_tokens=max_tokens)
    else:
        text = llm_router._openai_chat(
            provider, provider.text_model,
            [{"role": "user", "content": prompt}], max_tokens)
    return text, time.time() - start


def sample_vault_images(n, seed=42):
    imgs = [p for p in (VAULT / "resources").rglob("*")
            if p.suffix.lower() in IMAGE_EXTS and p.stat().st_size < 4_000_000]
    if len(imgs) < n:
        raise SystemExit(f"vault only has {len(imgs)} usable images under "
                         f"{VAULT/'resources'}, need {n}")
    random.Random(seed).shuffle(imgs)
    return imgs[:n]


def parse_json_array(text):
    """Best-effort: strip fences, find the outermost array."""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("```")[1]
        if t.startswith("json"):
            t = t[4:]
    try:
        out = json.loads(t)
        return out if isinstance(out, list) else None
    except json.JSONDecodeError:
        lo, hi = t.find("["), t.rfind("]")
        if 0 <= lo < hi:
            try:
                out = json.loads(t[lo:hi + 1])
                return out if isinstance(out, list) else None
            except json.JSONDecodeError:
                return None
    return None


def save(kind, payload):
    RESULTS_DIR.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = RESULTS_DIR / f"{kind}-{stamp}.json"
    out.write_text(json.dumps(payload, indent=2, ensure_ascii=False, default=str),
                   encoding="utf-8")
    print(f"\nsaved → {out}")


def pace(provider):
    """Respect free-tier RPM between consecutive calls to the same provider."""
    time.sleep(provider.min_interval)


# ── experiment 1: probe ──────────────────────────────────────────────────

def cmd_probe(args):
    img = sample_vault_images(1)[0]
    print(f"probe image: {img}\n")
    rows = []
    for p in configured_providers(args.providers, capability="text") \
            + [q for q in configured_providers(args.providers, "vision")
               if not q.supports("text")]:
        row = {"provider": p.name, "text_model": p.text_model,
               "vision_model": p.vision_model}
        try:
            text, dt = call_text(p, "Reply with the single word: ok")
            row["text"] = f"OK {dt:.1f}s ({text.strip()[:30]!r})"
        except Exception as e:
            row["text"] = f"FAIL {str(e)[:120]}"
        pace(p)
        if p.supports("vision"):
            try:
                text, dt = call_vision_multi(
                    p, PROMPT_VARIANTS["current"], [img], max_tokens=1024)
                row["vision"] = f"OK {dt:.1f}s ({len(text)} chars)"
            except Exception as e:
                row["vision"] = f"FAIL {str(e)[:120]}"
        else:
            row["vision"] = "— (no vision model)"
        rows.append(row)
        print(f"{p.name:10s} text: {row['text']}")
        print(f"{'':10s} vision: {row['vision']}\n")
    save("probe", {"image": str(img), "rows": rows})


# ── experiment 2: batch size sweep ───────────────────────────────────────

def cmd_batch(args):
    counts = [int(c) for c in args.counts.split(",")]
    images = sample_vault_images(max(counts))
    print("images: " + ", ".join(p.name for p in images) + "\n")
    results = []

    for p in configured_providers(args.providers, "vision"):
        # baseline: each image alone — quality reference + per-request overhead
        baselines = []
        for img in images:
            try:
                text, dt = call_vision_multi(
                    p, PROMPT_VARIANTS["current"], [img], max_tokens=1024)
                baselines.append({"image": img.name, "text": text, "latency_s": dt})
            except Exception as e:
                baselines.append({"image": img.name, "error": str(e)[:200]})
            pace(p)
        ok_base = [b for b in baselines if "text" in b]
        base_latency = (sum(b["latency_s"] for b in ok_base) / len(ok_base)
                        if ok_base else None)
        print(f"{p.name}: single-image baseline "
              f"{base_latency and f'{base_latency:.1f}s/img' or 'ALL FAILED'}")

        for n in counts:
            if n == 1:
                continue
            entry = {"provider": p.name, "n": n}
            try:
                text, dt = call_vision_multi(
                    p, BATCH_PROMPT.format(n=n), images[:n], max_tokens=1024 * n)
                arr = parse_json_array(text)
                entry["latency_s"] = round(dt, 2)
                entry["per_image_s"] = round(dt / n, 2)
                entry["kept_separate"] = bool(arr) and len(arr) == n
                if arr and len(arr) == n:
                    # quality proxy: similarity of each batched transcription
                    # to that image's solo baseline
                    sims = []
                    for i, b in enumerate(baselines[:n]):
                        if "text" in b:
                            sims.append(difflib.SequenceMatcher(
                                None, b["text"], str(arr[i])).ratio())
                    entry["similarity_to_solo"] = round(sum(sims) / len(sims), 3) if sims else None
                    entry["responses"] = [str(a)[:400] for a in arr]
                else:
                    entry["raw_head"] = text[:400]
            except Exception as e:
                entry["error"] = str(e)[:200]
            results.append(entry)
            flag = ("✓ separate" if entry.get("kept_separate")
                    else "✗ merged/garbled" if "latency_s" in entry else "FAIL")
            print(f"  n={n}: {entry.get('latency_s', '—')}s total, "
                  f"{entry.get('per_image_s', '—')}s/img, {flag}, "
                  f"sim={entry.get('similarity_to_solo', '—')}")
            pace(p)

        results.append({"provider": p.name, "n": 1,
                        "per_image_s": base_latency and round(base_latency, 2),
                        "baselines": baselines})
        print()
    save("batch", {"counts": counts, "images": [str(i) for i in images],
                   "results": results})


# ── experiment 3: prompt variants ────────────────────────────────────────

def cmd_prompts(args):
    img = Path(args.image) if args.image else sample_vault_images(1)[0]
    print(f"image: {img}\n")
    results = []
    for p in configured_providers(args.providers, "vision"):
        for name, prompt in PROMPT_VARIANTS.items():
            entry = {"provider": p.name, "variant": name}
            try:
                text, dt = call_vision_multi(p, prompt, [img], max_tokens=1024)
                entry.update(latency_s=round(dt, 2), chars=len(text), text=text)
                print(f"── {p.name} / {name} ({dt:.1f}s, {len(text)} chars) " + "─" * 20)
                print(text[:600] + ("…" if len(text) > 600 else ""), "\n")
            except Exception as e:
                entry["error"] = str(e)[:200]
                print(f"── {p.name} / {name}: FAIL {str(e)[:120]}\n")
            results.append(entry)
            pace(p)
    save("prompts", {"image": str(img), "results": results})


# ── main ─────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("probe", cmd_probe), ("batch", cmd_batch),
                     ("prompts", cmd_prompts)):
        s = sub.add_parser(name)
        s.add_argument("--providers", type=lambda v: v.split(","), default=None)
        s.set_defaults(fn=fn)
        if name == "batch":
            s.add_argument("--counts", default="1,2,4,8")
        if name == "prompts":
            s.add_argument("--image", default=None)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
