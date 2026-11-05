-- Synthetic §9.2 data for a disposable PostgreSQL database after Flyway and dev seeding.
-- Run inside a transaction after SET LOCAL accounts.e9_fixture = 'isolated'.
DO $e9_fixture$
DECLARE
    client_one constant uuid := 'e9000000-0000-4000-8000-000000000101';
    client_two constant uuid := 'e9000000-0000-4000-8000-000000000102';
    account_a constant uuid := 'e9000000-0000-4000-8000-000000000201';
    account_b constant uuid := 'e9000000-0000-4000-8000-000000000202';
    account_c constant uuid := 'e9000000-0000-4000-8000-000000000203';
    charge_a constant uuid := 'e9000000-0000-4000-8000-000000000301';
    payment_a constant uuid := 'e9000000-0000-4000-8000-000000000302';
    accountant uuid;
    document record;
BEGIN
    IF current_setting('accounts.e9_fixture', true) IS DISTINCT FROM 'isolated' THEN
        RAISE EXCEPTION 'E9 control fixture requires an explicitly isolated database';
    END IF;
    SELECT id INTO STRICT accountant FROM app_user WHERE login = 'demo-accountant';

    INSERT INTO client(id, client_type, display_name, identifier_type, identifier_value, identifier_key, phone, email)
    VALUES (client_one, 'PERSON', 'E9 — контрольный клиент C1', 'DEMO', 'PRIVATE_E9_C1', 'private_e9_c1',
            '+7-000-000-00-01', 'e9-c1@example.invalid'),
           (client_two, 'PERSON', 'E9 — контрольный клиент C2', 'DEMO', 'PRIVATE_E9_C2', 'private_e9_c2',
            '+7-000-000-00-02', 'e9-c2@example.invalid');

    INSERT INTO personal_account(id, account_number, client_id, status, opened_on, history_start_on,
                                 opening_balance, current_balance, currency)
    SELECT item.id, 'LS-' || lpad(nextval('personal_account_number_seq')::text, 10, '0'), item.client_id,
           'OPEN', date '2026-09-01', date '2026-09-01', 0, 0, 'RUB'
    FROM (VALUES (1, account_a, client_one), (2, account_b, client_two), (3, account_c, client_one))
         AS item(ordinal, id, client_id)
    ORDER BY item.ordinal;

    -- Backdated corrections model historical fixture data only. The production UI keeps its current-day rule.
    FOR document IN
        SELECT * FROM (VALUES
            (1, charge_a, account_a, 'CHARGE', date '2026-09-05', 1000.00, 1, NULL::text, NULL::uuid, NULL::numeric, NULL::text),
            (2, payment_a, account_a, 'PAYMENT', date '2026-10-02', 400.00, -1, 'PAY-0001', NULL, NULL, NULL),
            (3, 'e9000000-0000-4000-8000-000000000303'::uuid, account_a, 'CORRECTION', date '2026-10-03',
                 100.00, -1, NULL, charge_a, 900.00, 'ошибка суммы'),
            (4, 'e9000000-0000-4000-8000-000000000304'::uuid, account_a, 'CORRECTION', date '2026-10-04',
                 50.00, 1, NULL, payment_a, 350.00, 'ошибка ввода'),
            (5, 'e9000000-0000-4000-8000-000000000305'::uuid, account_b, 'CHARGE', date '2026-10-01',
                 200.00, 1, NULL, NULL, NULL, NULL),
            (6, 'e9000000-0000-4000-8000-000000000306'::uuid, account_b, 'PAYMENT', date '2026-10-02',
                 500.00, -1, 'PAY-0002', NULL, NULL, NULL)
        ) AS input(ordinal, id, account_id, kind, operation_date, amount, effect_sign,
                   external_number, original_id, target_amount, reason)
        ORDER BY ordinal
    LOOP
        INSERT INTO financial_operation(id, account_id, kind, storage_status, operation_date, amount, effect_sign,
                period_start, basis, payment_method, external_source, external_number, external_number_key,
                corrects_operation_id, target_amount, correction_reason, expected_correction_count, created_by,
                created_at, updated_at)
        VALUES (document.id, document.account_id, document.kind, 'PREPARED', document.operation_date,
                document.amount, document.effect_sign,
                CASE WHEN document.kind = 'CHARGE' THEN date_trunc('month', document.operation_date)::date END,
                'Контрольный набор E9 §9.2', CASE WHEN document.kind = 'PAYMENT' THEN 'MANUAL_TRANSFER' END,
                CASE WHEN document.external_number IS NOT NULL THEN 'MANUAL' END,
                document.external_number, lower(document.external_number), document.original_id,
                document.target_amount, document.reason, CASE WHEN document.kind = 'CORRECTION' THEN 0 END,
                accountant, document.operation_date + time '11:00', document.operation_date + time '11:00');

        UPDATE financial_operation
        SET storage_status = 'POSTED', posted_by = accountant, posted_at = document.operation_date + time '12:00',
            updated_at = document.operation_date + time '12:00', idempotency_key = gen_random_uuid(),
            request_fingerprint = repeat('0', 64)
        WHERE id = document.id;

        UPDATE personal_account
        SET current_balance = current_balance + document.amount * document.effect_sign,
            version = version + 1
        WHERE id = document.account_id;
    END LOOP;
END;
$e9_fixture$;
