-- Persist canvas layout and the workflow variable schema (inputs/outputs) alongside the definition
-- so the editor restores from the backend (not localStorage) and run forms can be generated from
-- the declared inputs. Stored on the live definition; nodes/edges remain the versioned DSL.

alter table workflow_definitions add column layout_json text;
alter table workflow_definitions add column variables_json text;
