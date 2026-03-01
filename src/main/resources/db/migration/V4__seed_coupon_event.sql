INSERT INTO coupon_event (id, name, total_stock, status, start_at, end_at)
VALUES (
    '019577a0-0000-7000-8000-000000000001',
    '선착순 쿠폰 이벤트',
    10000,
    'ACTIVE',
    '2026-01-01 00:00:00',
    '2026-12-31 23:59:59'
) ON CONFLICT (id) DO NOTHING;
