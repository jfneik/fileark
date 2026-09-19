-- FileArk 初始 schema（SQLite）
CREATE TABLE scan (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    root_path   TEXT NOT NULL,
    status      TEXT NOT NULL,
    stats_json  TEXT,
    started_at  TEXT NOT NULL,
    finished_at TEXT
);

CREATE TABLE file_entry (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_id      INTEGER NOT NULL REFERENCES scan(id),
    path         TEXT NOT NULL,
    size         INTEGER NOT NULL,
    mtime        TEXT NOT NULL,
    sample_hash  TEXT,
    content_hash TEXT,
    mime_type    TEXT,
    category     TEXT NOT NULL,
    skip_reason  TEXT,
    UNIQUE (scan_id, path)
);
CREATE INDEX idx_file_entry_scan ON file_entry(scan_id);

CREATE TABLE duplicate_group (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scan_id    INTEGER NOT NULL REFERENCES scan(id),
    hash       TEXT NOT NULL,
    status     TEXT NOT NULL,
    file_count INTEGER NOT NULL DEFAULT 0,
    total_size INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_dup_group_scan ON duplicate_group(scan_id);

CREATE TABLE duplicate_member (
    group_id      INTEGER NOT NULL REFERENCES duplicate_group(id) ON DELETE CASCADE,
    file_entry_id INTEGER NOT NULL REFERENCES file_entry(id),
    keep_flag     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, file_entry_id)
);

CREATE TABLE rule (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    priority        INTEGER NOT NULL,
    enabled         INTEGER NOT NULL DEFAULT 1,
    conditions_json TEXT NOT NULL,
    action_json     TEXT NOT NULL
);

CREATE TABLE plan (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    status         TEXT NOT NULL,
    description    TEXT,
    error_msg      TEXT,
    created_at     TEXT NOT NULL,
    executed_at    TEXT,
    rolled_back_at TEXT
);

CREATE TABLE plan_operation (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id        INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
    seq            INTEGER NOT NULL,
    op_type        TEXT NOT NULL,
    src            TEXT NOT NULL,
    dst            TEXT,
    expected_size  INTEGER,
    expected_mtime TEXT,
    status         TEXT NOT NULL DEFAULT 'PENDING',
    error_msg      TEXT
);
CREATE INDEX idx_plan_op_plan ON plan_operation(plan_id);

CREATE TABLE undo_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_id      INTEGER NOT NULL,
    operation_id INTEGER NOT NULL,
    kind         TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    applied      INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL
);
CREATE INDEX idx_undo_op ON undo_log(plan_id, operation_id);

CREATE TABLE quarantine_item (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    original_path   TEXT NOT NULL,
    quarantine_path TEXT NOT NULL UNIQUE,
    size            INTEGER NOT NULL,
    hash            TEXT,
    quarantined_at  TEXT NOT NULL,
    purge_after     TEXT NOT NULL,
    purged          INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
