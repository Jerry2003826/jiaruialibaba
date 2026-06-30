-- Demo order data used by the local customer-service workflow.
-- The workflow tool queryOrderAPI reads this table; the tool no longer owns order facts.

create table demo_orders (
    order_id varchar(64) not null primary key,
    status varchar(32) not null,
    paid boolean not null,
    amount numeric(18, 2) not null,
    currency varchar(8) not null,
    carrier varchar(64),
    tracking_number varchar(64),
    estimated_delivery date,
    latest_event varchar(512),
    next_action varchar(512),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

insert into demo_orders (
    order_id,
    status,
    paid,
    amount,
    currency,
    carrier,
    tracking_number,
    estimated_delivery,
    latest_event,
    next_action,
    created_at,
    updated_at
) values (
    '20260630001',
    'SHIPPED',
    true,
    299.00,
    'CNY',
    'SF Express',
    'SF20260630001',
    date '2026-07-02',
    'Package left the Shanghai transit center',
    'Tell the customer the order has shipped and provide the tracking number.',
    current_timestamp,
    current_timestamp
);
