-- Keep the confirmed builder specification with both the live draft and every revision.
-- Nullable columns preserve compatibility with workflows created before the specification gate.

alter table workflow_definitions add column locked_spec_json text;
alter table workflow_definition_revisions add column locked_spec_json text;
