create index if not exists idx_rag_documents_owner_kb_id
on rag_documents (owner_id, kb_id, id);

create index if not exists idx_rag_documents_owner_kb_status_id
on rag_documents (owner_id, kb_id, index_status, id);
