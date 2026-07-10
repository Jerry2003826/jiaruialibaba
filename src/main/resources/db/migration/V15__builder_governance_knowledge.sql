alter table knowledge_bases add column purpose varchar(32);
alter table knowledge_bases add column system_managed boolean;

update knowledge_bases
set purpose = 'BUSINESS',
    system_managed = false
where purpose is null
   or system_managed is null;

alter table knowledge_bases alter column purpose set default 'BUSINESS';
alter table knowledge_bases alter column purpose set not null;
alter table knowledge_bases alter column system_managed set default false;
alter table knowledge_bases alter column system_managed set not null;

create unique index uq_knowledge_bases_owner_workflow_builder_managed
    on knowledge_bases (owner_id)
    where system_managed = true and purpose = 'WORKFLOW_BUILDER';

create unique index uq_rag_documents_builder_identity_active
    on rag_documents (owner_id, kb_id, title)
    where source_type = 'BUILDER' and index_status not in ('DELETING', 'DELETED');
