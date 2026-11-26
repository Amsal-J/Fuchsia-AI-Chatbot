-- Files from folder1. This table IS folder2 now.
CREATE TABLE documents (
    id              UUID            PRIMARY KEY DEFAULT uuidv7(),
    filename        TEXT            NOT NULL UNIQUE,
    source_type     TEXT            NOT NULL DEFAULT 'upload' CHECK (source_type IN ('upload', 'email')),
    content         BYTEA           NOT NULL,
    size_bytes      BIGINT          NOT NULL CHECK(size_bytes > 0),
    sha256          TEXT            NOT NULL,
    content_type    TEXT            NOT NULL,
    scan_status     TEXT            NOT NULL DEFAULT 'unscanned' CHECK (scan_status IN ('unscanned', 'scanned')),
    uploaded_at     TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Question/answer pairs pulled from a document, waiting for human review
CREATE TABLE qa_entries (
    id              UUID            PRIMARY KEY DEFAULT uuidv7(),
    document_id     UUID            NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    question        TEXT            NOT NULL CHECK (question ~ '\S'),
    answer          TEXT            NOT NULL CHECK (answer ~ '\S'),
    product_id      TEXT            NOT NULL,
    process_status  TEXT            NOT NULL DEFAULT 'unprocessed' CHECK (process_status IN ('unprocessed', 'processed')),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX qa_entries_document_id_dix ON qa_entries (document_id);