-- Claim token for vector outbox leases. This prevents a worker whose lease expired from
-- finalizing a later worker's PROCESSING claim for the same event.
alter table vector_outbox_events add column claim_id varchar(36);

create index idx_vector_outbox_claim_id on vector_outbox_events (claim_id);
