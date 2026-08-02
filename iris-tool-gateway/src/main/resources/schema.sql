CREATE TABLE IF NOT EXISTS audit_log (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    incident_id         TEXT NOT NULL,
    agent_role          TEXT NOT NULL,
    tool_name           TEXT NOT NULL,
    template_or_raw     TEXT NOT NULL,
    request_params      TEXT,
    response_summary    TEXT,
    degraded            INTEGER NOT NULL DEFAULT 0,
    http_status         INTEGER NOT NULL,
    elapsed_ms          INTEGER NOT NULL,
    created_at          TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_audit_log_incident_id ON audit_log (incident_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at ON audit_log (created_at);
