ALTER TABLE documents ADD COLUMN parent_id UUID REFERENCES documents(id) ON DELETE CASCADE;
ALTER TABLE documents ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_documents_parent_id ON documents(parent_id);
