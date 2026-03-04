CREATE TABLE documents (
    id          UUID PRIMARY KEY,
    title       VARCHAR(500) NOT NULL DEFAULT 'Untitled',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE document_updates (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    update_data BYTEA NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_document_updates_doc_id ON document_updates(doc_id);

CREATE TABLE document_snapshots (
    doc_id      UUID PRIMARY KEY REFERENCES documents(id) ON DELETE CASCADE,
    state_data  BYTEA NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
