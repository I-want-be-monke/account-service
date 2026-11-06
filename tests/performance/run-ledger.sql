\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '10min';

DO $guard$
BEGIN
    IF current_database() <> 'account_service_e11'
       OR current_setting('accounts.e11_dedicated', true) IS DISTINCT FROM 'account-service-e11' THEN
        RAISE EXCEPTION 'E11 ledger requires the dedicated account_service_e11 database';
    END IF;
END;
$guard$;

COPY (
    SELECT o.id AS operation_id, o.kind, o.storage_status, o.external_number, o.delta,
           u.login AS posted_by, a.request_id, a.actor_login_snapshot AS audit_actor,
           a.action AS audit_action, a.result AS audit_result,
           a.details->>'document' AS audit_document, a.after_data->>'delta' AS audit_delta
    FROM financial_operation o
    LEFT JOIN app_user u ON u.id=o.posted_by
    LEFT JOIN audit_event a ON a.object_type='OPERATION' AND a.object_id=o.id::text
        AND a.action IN ('OPERATION_POSTED','OPERATION_CORRECTION_POSTED') AND a.result='SUCCESS'
    WHERE starts_with(o.external_number,'E11-LOAD-' || :'runId' || '-')
    ORDER BY o.id, a.id
) TO STDOUT WITH (FORMAT csv, HEADER true);
ROLLBACK;
