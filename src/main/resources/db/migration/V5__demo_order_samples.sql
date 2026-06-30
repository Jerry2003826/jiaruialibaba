-- Add multiple demo customer orders. queryOrderAPI still requires an explicit order id.

alter table demo_orders add column customer_name varchar(64);

update demo_orders
set customer_name = 'Alice Chen'
where order_id = '20260630001';

insert into demo_orders (
    order_id,
    customer_name,
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
    '20260630002',
    'Bo Li',
    'PENDING_RETURN',
    true,
    159.00,
    'CNY',
    'JD Logistics',
    'JD20260630002',
    date '2026-07-03',
    'Return request submitted, awaiting warehouse review',
    'Tell the customer the return request is under review and provide the current tracking number.',
    current_timestamp,
    current_timestamp
), (
    '20260630003',
    'Mina Zhang',
    'PROCESSING',
    false,
    89.00,
    'CNY',
    null,
    null,
    null,
    'Order created, waiting for payment confirmation',
    'Ask the customer to complete payment before shipment can be arranged.',
    current_timestamp,
    current_timestamp
);
