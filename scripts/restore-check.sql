\set ON_ERROR_STOP on
DO $$
BEGIN
    IF current_database() <> 'account_service_restore_test' THEN
        RAISE EXCEPTION 'restore check must run in the isolated test database';
    END IF;
    IF EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success)
        OR (SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4','5')) <> 5 THEN
        RAISE EXCEPTION 'missing or failed required Flyway migrations';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = 'public'::regnamespace AND NOT convalidated)
        OR EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid
                   WHERE r.relnamespace = 'public'::regnamespace AND t.tgenabled = 'D') THEN
        RAISE EXCEPTION 'invalid constraint or disabled trigger in restored schema';
    END IF;
    IF EXISTS (SELECT 1 FROM personal_account a
               LEFT JOIN financial_operation o ON o.account_id = a.id AND o.storage_status = 'POSTED'
               GROUP BY a.id HAVING a.current_balance <> a.opening_balance + coalesce(sum(o.delta), 0)) THEN
        RAISE EXCEPTION 'financial reconciliation failed';
    END IF;
    IF EXISTS (SELECT 1 FROM personal_account a LEFT JOIN client c ON c.id = a.client_id WHERE c.id IS NULL)
        OR EXISTS (SELECT 1 FROM financial_operation o LEFT JOIN personal_account a ON a.id = o.account_id WHERE a.id IS NULL)
        OR EXISTS (SELECT 1 FROM financial_operation o LEFT JOIN app_user u ON u.id = o.posted_by
                   WHERE o.storage_status = 'POSTED' AND u.id IS NULL)
        OR EXISTS (SELECT 1 FROM audit_event e LEFT JOIN app_user u ON u.id = e.actor_user_id
                   WHERE e.actor_user_id IS NOT NULL AND u.id IS NULL) THEN
        RAISE EXCEPTION 'broken financial or audit reference';
    END IF;
    IF EXISTS (SELECT 1 FROM spring_session) OR EXISTS (SELECT 1 FROM spring_session_attributes) THEN
        RAISE EXCEPTION 'restored archive contains live sessions';
    END IF;
END $$;

SELECT json_build_object(
    'roles', (SELECT count(*) FROM app_role), 'users', (SELECT count(*) FROM app_user),
    'clients', (SELECT count(*) FROM client), 'accounts', (SELECT count(*) FROM personal_account),
    'operations', (SELECT count(*) FROM financial_operation), 'audit', (SELECT count(*) FROM audit_event),
    'statusHistory', (SELECT count(*) FROM account_status_history),
    'flyway', (SELECT json_agg(version ORDER BY installed_rank) FROM flyway_schema_history),
    'debt', (SELECT coalesce(sum(greatest(current_balance, 0)), 0)::text FROM personal_account),
    'advance', (SELECT coalesce(sum(greatest(-current_balance, 0)), 0)::text FROM personal_account),
    'reconciledAccounts', (SELECT count(*) FROM personal_account)
);
