"""Native A/V IR extraction tests (INGESTION_V2_FLOWS §8a/§8d).

Covers the **pure** core `build_ir(Cue[]) → SourceIR` and the `from_bundle` v1 adapter (no
whisper, no yt-dlp): SPEECH blocks with millisecond TimeSpans, chapters→TOC structural prior,
and the confidence flags — ASR_LOW (HARD), NON_SPEECH / AUTO_CAPTIONS / NO_STRUCTURE (SOFT).
The `from_av` I/O wrapper needs faster-whisper and isn't covered here.
"""
from ingest import segment
from ingest.extract_av_ir import Cue, build_ir, from_bundle
from ingest.ir import BlockType, Medium, Severity, TimeSpan


def test_cues_become_speech_blocks_with_ms_timespans():
    cues = [Cue(0.0, 1.5, "hello there"), Cue(1.5, 3.0, "welcome back")]
    ir = build_ir(cues, chapters=[{"title": "Intro"}], title="Talk", medium=Medium.VIDEO)
    assert all(b.type == BlockType.SPEECH for b in ir.blocks)
    assert isinstance(ir.blocks[0].locator, TimeSpan)
    assert ir.blocks[0].locator.start_ms == 0 and ir.blocks[0].locator.end_ms == 1500
    assert ir.blocks[1].locator.start_ms == 1500
    assert ir.has_structure()          # chapters → TOC → Stage A has a prior


def test_no_chapters_flags_no_structure_soft():
    ir = build_ir([Cue(0, 1, "a talk with no chapters")], medium=Medium.AUDIO)
    assert not ir.has_structure()
    codes = {(f.code, f.severity) for b in ir.blocks for f in b.flags}
    assert ("NO_STRUCTURE", Severity.SOFT) in codes
    assert not ir.hard_flags()         # SOFT only → auto-flows


def test_low_logprob_is_hard_asr_flag():
    cues = [Cue(0, 2, "grbld speech", avg_logprob=-1.6)]
    ir = build_ir(cues, chapters=[{"title": "x"}], medium=Medium.AUDIO)
    assert any(f.code == "ASR_LOW" and f.severity == Severity.HARD for f in ir.hard_flags())


def test_high_no_speech_prob_is_soft_non_speech():
    cues = [Cue(0, 4, "[music]", no_speech_prob=0.9)]
    ir = build_ir(cues, chapters=[{"title": "x"}], medium=Medium.AUDIO)
    codes = {(f.code, f.severity) for b in ir.blocks for f in b.flags}
    assert ("NON_SPEECH", Severity.SOFT) in codes
    assert not ir.hard_flags()


def test_auto_captions_soft_flag_on_first_block():
    ir = build_ir([Cue(0, 1, "one"), Cue(1, 2, "two")], chapters=[{"title": "x"}],
                  auto_captions=True, medium=Medium.VIDEO)
    assert any(f.code == "AUTO_CAPTIONS" and f.severity == Severity.SOFT
               for f in ir.blocks[0].flags)


def test_from_bundle_v1_av_produces_speech_ir():
    bundle = {
        "source": {"type": "video", "title": "Lecture",
                   "chapters": [{"title": "Part 1"}, {"title": "Part 2"}]},
        "segments": [
            {"loc": {"t_start": 0.0, "t_end": 2.0}, "text": "first"},
            {"loc": {"t_start": 2.0, "t_end": 4.0}, "text": "second"},
        ],
    }
    ir = from_bundle(bundle)
    assert ir.medium == Medium.VIDEO and ir.has_structure()
    assert all(b.type == BlockType.SPEECH for b in ir.blocks)
    assert ir.blocks[0].locator.end_ms == 2000


def test_build_ir_feeds_segment_time_spans():
    cues = [Cue(i * 2.0, i * 2.0 + 2.0, " ".join(["word"] * 80)) for i in range(4)]
    ir = build_ir(cues, chapters=[{"title": "Only"}], medium=Medium.AUDIO)
    units = segment.segment(ir)
    assert units and units[0].locator_span["kind"] == "time"
    assert units[0].locator_span["start_ms"] == 0
