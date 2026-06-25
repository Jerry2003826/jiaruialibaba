-- Recoverable vector outbox: optimistic-locking version + worker lease for crash recovery.
-- Expand-only and safe against the V2 outbox table (adds columns with defaults, no rewrites of data).

-- 1. Optimistic-locking guard (JPA @Version) so two workers can never finalize the same event.
alter table vector_outbox_events add column row_version bigint not null default 0;

-- 2. Worker lease: a PROCESSING event whose lease has expired is treated as abandoned (the worker
--    crashed) and may be reclaimed by another worker. Nullable: only set while a worker owns the event.
alter table vector_outbox_events add column lease_expires_at timestamp(6) with time zone;

-- 3. Supports the lease-recovery branch of the claim scan (status = PROCESSING and lease expired).
create index idx_vector_outbox_lease on vector_outbox_events (status, lease_expires_at);
