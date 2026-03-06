CREATE TABLE document_permissions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission  VARCHAR(10) NOT NULL CHECK (permission IN ('VIEWER', 'EDITOR')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    UNIQUE (document_id, user_id)
);

CREATE INDEX idx_doc_perms_document ON document_permissions(document_id);
CREATE INDEX idx_doc_perms_user ON document_permissions(user_id);
