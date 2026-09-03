package com.obsidian.obsidian.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java-side writer for the shared {@code pipeline_failures} ledger (see
 * embedder/failures.py and QUEUE_UNIFICATION_PLAN.md). The table already exists once
 * either side has booted; {@link #initSchema} is here too (same
 * {@code CREATE TABLE IF NOT EXISTS} convention as every other repository in this
 * codebase) so a Java-only deploy still gets it, and so debugging a capture dead-letter
 * looks identical to debugging any Python-side ingest failure — one table, one shape,
 * regardless of which pipeline wrote the row.
 */
@Component
public class PipelineFailureRepository {

    private static final Logger log = LoggerFactory.getLogger(PipelineFailureRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public PipelineFailureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
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
            """);
        jdbc.execute(
            "CREATE INDEX IF NOT EXISTS pipeline_failures_open_idx " +
            "ON pipeline_failures (stage) WHERE resolved_at IS NULL");
    }

    /** Best-effort, like {@code embedder/failures.py}'s record_failure — recording a
     *  failure must never itself mask the original one. */
    public void record(String source, String stage, Map<String, Object> inputPayload,
                       String errorType, String errorMessage, String bundleRef) {
        try {
            String json = mapper.writeValueAsString(inputPayload);
            jdbc.update("""
                INSERT INTO pipeline_failures
                    (source, stage, input_payload, error_type, error_message, bundle_ref)
                VALUES (?, ?, ?::jsonb, ?, ?, ?)
                """, source, stage, json, errorType, errorMessage, bundleRef);
        } catch (Exception e) {
            log.warn("[PipelineFailureRepository] failed to record failure (source={} stage={}): {}",
                source, stage, e.toString());
        }
    }

    public record Failure(long id, String occurredAt, String source, String stage,
                          String inputPayload, String errorType, String errorMessage,
                          String bundleRef, String resolvedAt) {}

    /** Newest-first, for the "Pipeline Failures" review page. {@code onlyOpen} filters to
     *  unresolved rows (the page's default view); source/stage narrow it further. */
    public List<Failure> list(boolean onlyOpen, String source, String stage, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM pipeline_failures WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (onlyOpen) sql.append(" AND resolved_at IS NULL");
        if (source != null && !source.isBlank()) { sql.append(" AND source = ?"); params.add(source); }
        if (stage != null && !stage.isBlank()) { sql.append(" AND stage = ?"); params.add(stage); }
        sql.append(" ORDER BY occurred_at DESC LIMIT ?");
        params.add(limit);
        return jdbc.query(sql.toString(), PipelineFailureRepository::map, params.toArray());
    }

    /** User (or a fix landing) is done with this one — stop showing it in the open view.
     *  Rows are never auto-discarded, only explicitly resolved. */
    public boolean resolve(long id) {
        return jdbc.update(
            "UPDATE pipeline_failures SET resolved_at = now() WHERE id = ? AND resolved_at IS NULL",
            id) > 0;
    }

    private static Failure map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new Failure(
            rs.getLong("id"),
            rs.getTimestamp("occurred_at").toInstant().toString(),
            rs.getString("source"),
            rs.getString("stage"),
            rs.getString("input_payload"),
            rs.getString("error_type"),
            rs.getString("error_message"),
            rs.getString("bundle_ref"),
            resolvedAt == null ? null : resolvedAt.toInstant().toString());
    }
}
