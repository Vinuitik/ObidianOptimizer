"""Shared failure ledger for every backend pipeline (ingest, sync, cards, ...).

A dead-lettered/swallowed failure is useless for debugging without the exact input that
triggered it. record_failure() persists {source, stage, input, error, bundle_ref} so a human
can query pipeline_failures, see precisely what was submitted, and replay it — instead of a
bare "something failed" flag. Written directly from Python (the embedder already owns a
psycopg connection for search/flashcards, see flashcards/generate.py); Java queues (capture,
sync_queue, pending_image_jobs) write to the SAME table for their own dead-letters, so
debugging any pipeline's failures looks identical regardless of which one produced them.
See architecture_plans/QUEUE_UNIFICATION_PLAN.md.
"""
import json
import logging
import os

import psycopg

log = logging.getLogger("embedder.failures")

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql://obsidian:obsidian@postgres:5432/obsidian")


def ensure_schema(conn) -> None:
    conn.execute("""
        CREATE TABLE IF NOT EXISTS pipeline_failures (
            id BIGSERIAL PRIMARY KEY,
            occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            source TEXT NOT NULL,
            stage TEXT NOT NULL,
            input_payload JSONB NOT NULL,
            error_type TEXT,
            error_message TEXT,
            bundle_ref TEXT,
            resolved_at TIMESTAMPTZ
        )
    """)
    conn.execute(
        "CREATE INDEX IF NOT EXISTS pipeline_failures_open_idx "
        "ON pipeline_failures (stage) WHERE resolved_at IS NULL"
    )
    conn.commit()


def record_failure(source: str, stage: str, input_payload: dict,
                    error: Exception, bundle_ref: str | None = None) -> None:
    """Best-effort — recording a failure must never itself mask the original error."""
    try:
        with psycopg.connect(DATABASE_URL) as conn:
            ensure_schema(conn)
            conn.execute(
                "INSERT INTO pipeline_failures "
                "(source, stage, input_payload, error_type, error_message, bundle_ref) "
                "VALUES (%s, %s, %s, %s, %s, %s)",
                (source, stage, json.dumps(input_payload), type(error).__name__,
                 str(error)[:2000], bundle_ref),
            )
            conn.commit()
    except Exception:
        log.exception("failed to record pipeline failure (source=%s stage=%s)", source, stage)
