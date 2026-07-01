-- Add owner scoping to user-visible data. Existing demo/local rows are assigned to the
-- built-in workbench owner used by the local dev token.

alter table conversation_messages add column owner_id varchar(128);
update conversation_messages set owner_id = 'workbench-dev' where owner_id is null;
alter table conversation_messages alter column owner_id set not null;
create index idx_conversation_messages_owner_conversation_created
    on conversation_messages (owner_id, conversation_id, created_at, id);

alter table rag_documents add column owner_id varchar(128);
update rag_documents set owner_id = 'workbench-dev' where owner_id is null;
alter table rag_documents alter column owner_id set not null;
create index idx_rag_documents_owner_status_created
    on rag_documents (owner_id, index_status, created_at, id);

alter table runs add column owner_id varchar(128);
update runs set owner_id = 'workbench-dev' where owner_id is null;
alter table runs alter column owner_id set not null;
create index idx_runs_owner_status_started_at on runs (owner_id, status, started_at);
create index idx_runs_owner_type_started_at on runs (owner_id, type, started_at);

alter table run_steps add column owner_id varchar(128);
update run_steps set owner_id = 'workbench-dev' where owner_id is null;
alter table run_steps alter column owner_id set not null;
create index idx_run_steps_owner_run_started_at on run_steps (owner_id, run_id, started_at);

alter table workflow_definitions add column owner_id varchar(128);
update workflow_definitions set owner_id = 'workbench-dev' where owner_id is null;
alter table workflow_definitions alter column owner_id set not null;
create index idx_workflow_definitions_owner_created
    on workflow_definitions (owner_id, created_at, id);

alter table workflow_definition_revisions add column owner_id varchar(128);
update workflow_definition_revisions set owner_id = 'workbench-dev' where owner_id is null;
alter table workflow_definition_revisions alter column owner_id set not null;
create index idx_workflow_revisions_owner_definition_version
    on workflow_definition_revisions (owner_id, definition_id, version);

alter table workflow_run_records add column owner_id varchar(128);
update workflow_run_records set owner_id = 'workbench-dev' where owner_id is null;
alter table workflow_run_records alter column owner_id set not null;
create index idx_workflow_run_records_owner_definition_started
    on workflow_run_records (owner_id, definition_id, started_at);

alter table demo_orders add column owner_id varchar(128);
update demo_orders set owner_id = 'workbench-dev' where owner_id is null;
alter table demo_orders alter column owner_id set not null;
create index idx_demo_orders_owner_updated_at on demo_orders (owner_id, updated_at);
