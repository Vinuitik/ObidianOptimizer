"""PDF extraction — PyMuPDF text blocks; Tesseract OCR for scanned pages;
embedded images > 200×200 exported as media (INGEST_AGENT_ARCH stage 2).
"""
import base64
import logging
import re
from pathlib import Path

log = logging.getLogger("embedder.ingest.pdf")

MIN_OCR_WORDS = 20         # below this a "text" page is treated as scanned
MIN_IMG_SIDE = 200


def extract(ref: str, resolved_path: Path) -> dict:
    import fitz  # PyMuPDF

    doc = fitz.open(resolved_path)
    segments, media = [], []
    for page_no, page in enumerate(doc, start=1):
        text = page.get_text("text").strip()
        if len(text.split()) < MIN_OCR_WORDS:
            ocr = _ocr_page(page)
            if ocr:
                text = ocr
        if text:
            segments.append({"loc": {"page": page_no}, "text": _clean(text)})
        media.extend(_page_images(doc, page, page_no, resolved_path.stem))
    title = (doc.metadata or {}).get("title") or _title_heuristic(segments) \
        or resolved_path.stem
    chapters = _toc_chapters(doc, len(doc))
    doc.close()
    return {
        "source": {"type": "pdf", "ref": ref, "title": title,
                   "duration_s": 0, "chapters": chapters},
        "segments": segments,
        "media": _keep_diagrams(media),
    }


def _toc_chapters(doc, page_count: int) -> list[dict]:
    """Real chapter boundaries from the PDF's embedded outline/bookmarks (free, no LLM).
    Top-level (level 1) entries only — deeper nesting would over-fragment. Each chapter's
    end page = the next top-level entry's start page - 1 (last chapter runs to page_count).
    Most arXiv-style papers ship no outline at all → returns [] (caller falls back to flat,
    no invented chapters)."""
    try:
        toc = [t for t in doc.get_toc(simple=True) if t[0] == 1 and t[2] >= 1]
    except Exception as e:
        log.warning("PDF TOC read failed: %s", e)
        return []
    if not toc:
        return []
    chapters = []
    for i, (_level, title, start_page) in enumerate(toc):
        end_page = (toc[i + 1][2] - 1) if i + 1 < len(toc) else page_count
        if end_page < start_page:
            end_page = start_page
        chapters.append({"title": title.strip(), "start_page": start_page, "end_page": end_page})
    return chapters


def _keep_diagrams(media: list[dict]) -> list[dict]:
    """Drop decorative images (logos, headshots) — keep diagrams/charts/figures
    via CLIP zero-shot, the user's 'important diagrams only' requirement.
    Best-effort: CLIP unavailable → keep all (mask is all-True)."""
    if not media:
        return media
    from ingest import keyframes
    pngs = [base64.standard_b64decode(m["data_b64"]) for m in media]
    mask = keyframes.diagram_keep_mask(pngs)
    kept = [m for m, keep in zip(media, mask) if keep]
    log.info("pdf diagrams: kept %d of %d embedded images", len(kept), len(media))
    return kept


def _ocr_page(page) -> str:
    """150dpi render → Tesseract. Returns '' if tesseract is unavailable."""
    try:
        import pytesseract
        from PIL import Image
        import io
        pix = page.get_pixmap(dpi=150)
        img = Image.open(io.BytesIO(pix.tobytes("png")))
        return pytesseract.image_to_string(img).strip()
    except Exception as e:
        log.warning("OCR failed on page %s: %s", page.number, e)
        return ""


def _page_images(doc, page, page_no: int, slug: str) -> list[dict]:
    out = []
    for i, info in enumerate(page.get_images(full=True)):
        xref = info[0]
        try:
            import fitz
            pix = fitz.Pixmap(doc, xref)
            if pix.width < MIN_IMG_SIDE or pix.height < MIN_IMG_SIDE:
                continue
            if pix.colorspace and pix.colorspace.n > 3:
                pix = fitz.Pixmap(fitz.csRGB, pix)
            name = f"{slug}-p{page_no}-{i}.png"
            out.append({
                "path": name,                      # filename; stored via publish
                "loc": {"page": page_no},
                "trigger": "embedded",
                "data_b64": base64.standard_b64encode(pix.tobytes("png")).decode(),
            })
        except Exception as e:
            log.warning("image export failed p%s #%s: %s", page_no, i, e)
    return out


def _clean(text: str) -> str:
    text = re.sub(r"-\n(\w)", r"\1", text)        # de-hyphenate line breaks
    text = re.sub(r"[ \t]*\n[ \t]*", "\n", text)
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def _title_heuristic(segments: list[dict]) -> str | None:
    if not segments:
        return None
    first = segments[0]["text"].splitlines()
    return first[0][:120] if first else None
