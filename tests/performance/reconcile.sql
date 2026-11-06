\set ON_ERROR_STOP on
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '10min';

DO $guard$
BEGIN
    IF current_database() <> 'account_service_e11'
       OR current_setting('accounts.e11_dedicated', true) IS DISTINCT FROM 'account-service-e11' THEN
        RAISE EXCEPTION 'E11 reconciliation requires the dedicated account_service_e11 database';
    END IF;
END;
$guard$;

WITH bounds AS (
    SELECT :'reportMonth'::date AS from_on,
        (:'reportMonth'::date + interval '1 month' - interval '1 day')::date AS to_on
), ledger AS MATERIALIZED (
    SELECT a.id,a.status,a.history_start_on,a.current_balance,a.opening_balance,
        a.opening_balance+coalesce(sum(o.delta),0) AS calculated,
        a.opening_balance+coalesce(sum(o.delta) FILTER (
            WHERE o.operation_date>=a.history_start_on AND o.operation_date<b.from_on),0) AS period_opening,
        coalesce(sum(o.delta) FILTER (WHERE o.operation_date>=a.history_start_on
            AND o.operation_date BETWEEN b.from_on AND b.to_on),0) AS period_change
    FROM personal_account a CROSS JOIN bounds b
    LEFT JOIN financial_operation o ON o.account_id=a.id AND o.storage_status='POSTED'
    GROUP BY a.id
), month_rows AS MATERIALIZED (
    SELECT o.account_id,o.kind,o.delta,source.kind AS source_kind
    FROM financial_operation o JOIN personal_account a ON a.id=o.account_id
    CROSS JOIN bounds b
    LEFT JOIN financial_operation source ON source.id=o.corrects_operation_id
    WHERE o.storage_status='POSTED' AND o.operation_date BETWEEN b.from_on AND b.to_on
      AND o.operation_date>=a.history_start_on
), monthly AS (
    SELECT count(DISTINCT account_id) FILTER (WHERE kind='CHARGE' OR source_kind='CHARGE') AS charge_accounts,
           count(DISTINCT account_id) FILTER (WHERE kind='PAYMENT' OR source_kind='PAYMENT') AS payment_accounts,
           coalesce(sum(delta) FILTER (WHERE kind='CHARGE'),0) AS charge,
           coalesce(sum(delta) FILTER (WHERE kind='CORRECTION' AND source_kind='CHARGE'),0) AS charge_correction,
           -coalesce(sum(delta) FILTER (WHERE kind='PAYMENT'),0) AS payment,
           -coalesce(sum(delta) FILTER (WHERE kind='CORRECTION' AND source_kind='PAYMENT'),0) AS payment_correction,
           coalesce(sum(delta) FILTER (WHERE kind='CORRECTION'),0) AS corrections,
           coalesce(sum(greatest(delta,0)),0) AS increase,
           coalesce(sum(greatest(-delta,0)),0) AS decrease
    FROM month_rows
), historical AS (
    SELECT count(*) AS accounts,coalesce(sum(greatest(period_opening+period_change,0)),0) AS debt,
           coalesce(sum(greatest(-period_opening-period_change,0)),0) AS advance,
           coalesce(sum(period_opening),0) AS opening,coalesce(sum(period_change),0) AS change
    FROM ledger CROSS JOIN bounds b WHERE history_start_on<=b.to_on
), corrections AS (
    SELECT o.*,
        row_number() OVER (PARTITION BY corrects_operation_id ORDER BY expected_correction_count,posted_at,id)-1 AS prior_count,
        coalesce(sum(delta) OVER (PARTITION BY corrects_operation_id ORDER BY expected_correction_count,posted_at,id
             ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING),0) AS prior_effect
    FROM financial_operation o WHERE storage_status='POSTED' AND kind='CORRECTION'
), posting_audit AS MATERIALIZED (
    SELECT object_id,count(*) AS copies,
           count(*) FILTER (WHERE action='OPERATION_POSTED') AS originals,
           count(*) FILTER (WHERE action='OPERATION_CORRECTION_POSTED') AS corrections
    FROM audit_event WHERE result='SUCCESS' AND object_type='OPERATION'
      AND action IN ('OPERATION_POSTED','OPERATION_CORRECTION_POSTED') GROUP BY object_id
), observed_months AS (
    SELECT to_char(operation_date,'YYYY-MM') AS month,count(*) AS posted
    FROM financial_operation WHERE storage_status='POSTED' GROUP BY to_char(operation_date,'YYYY-MM')
), snapshot AS (
    SELECT count(*) AS accounts, count(*) FILTER (WHERE status='OPEN') AS open_accounts,
           count(*) FILTER (WHERE status='SUSPENDED') AS suspended_accounts,
           count(*) FILTER (WHERE status='CLOSED') AS closed_accounts,
           count(*) FILTER (WHERE calculated=0) AS zero_accounts,
           count(*) FILTER (WHERE calculated>0) AS debt_accounts,
           count(*) FILTER (WHERE calculated<0) AS advance_accounts,
           count(*) FILTER (WHERE current_balance<>calculated) AS fin09_mismatches,
           coalesce(sum(greatest(calculated,0)),0) AS debt,
           coalesce(sum(greatest(-calculated,0)),0) AS advance
    FROM ledger
)
SELECT jsonb_build_object(
    'database',current_database(),'postgresql',current_setting('server_version'),
    'databaseBytes',pg_database_size(current_database()),
    'reportMonth',to_char(b.from_on,'YYYY-MM'),
    'fixture',(SELECT details FROM audit_event WHERE action='E11_SEED_APPLIED' AND result='SUCCESS'
               ORDER BY id DESC LIMIT 1),
    'counts',jsonb_build_object('accounts',s.accounts,'clients',(SELECT count(*) FROM client),
        'syntheticClients',(SELECT count(*) FROM client WHERE identifier_key LIKE 'E11-%'),
        'syntheticUsers',(SELECT count(*) FROM app_user WHERE login LIKE 'e11-%'),
        'posted',(SELECT count(*) FROM financial_operation WHERE storage_status='POSTED'),
        'prepared',(SELECT count(*) FROM financial_operation WHERE storage_status='PREPARED'),
        'charges',(SELECT count(*) FROM financial_operation WHERE storage_status='POSTED' AND kind='CHARGE'),
        'payments',(SELECT count(*) FROM financial_operation WHERE storage_status='POSTED' AND kind='PAYMENT'),
        'corrections',(SELECT count(*) FROM corrections),'auditEvents',(SELECT count(*) FROM audit_event),
        'postingSuccesses',(SELECT coalesce(sum(copies),0) FROM posting_audit),
        'deniedAudits',(SELECT count(*) FROM audit_event WHERE result='DENIED'),
        'failedAudits',(SELECT count(*) FROM audit_event WHERE result='FAILED'),
        'seedPostingAudits',(SELECT count(*) FROM audit_event WHERE details->>'source'='E11_SEED')),
    'accountDistribution',jsonb_build_object('open',s.open_accounts,'suspended',s.suspended_accounts,
        'closed',s.closed_accounts,'zero',s.zero_accounts,'debt',s.debt_accounts,'advance',s.advance_accounts),
    'current',jsonb_build_object('debt',round(s.debt,2)::text,'advance',round(s.advance,2)::text,
        'balance',round(s.debt-s.advance,2)::text),
    'postedByMonth',coalesce((SELECT jsonb_object_agg(month,posted) FROM observed_months),'{}'::jsonb),
    'violations',jsonb_build_object(
        'fin09Mismatches',s.fin09_mismatches,
        'missingOrWrongPostingAudit',(SELECT count(*) FROM financial_operation o LEFT JOIN posting_audit audit
            ON audit.object_id=o.id::text WHERE o.storage_status='POSTED' AND
            (audit.object_id IS NULL OR CASE WHEN o.kind='CORRECTION' THEN audit.corrections<>1 ELSE audit.originals<>1 END)),
        'duplicatePostingAudit',(SELECT count(*) FROM posting_audit WHERE copies<>1),
        'orphanedPostingAudit',(SELECT count(*) FROM posting_audit a WHERE NOT EXISTS
            (SELECT 1 FROM financial_operation o WHERE o.id::text=a.object_id AND o.storage_status='POSTED')),
        'duplicateIdempotencyKeys',(SELECT count(*) FROM (SELECT idempotency_key FROM financial_operation
            WHERE idempotency_key IS NOT NULL GROUP BY idempotency_key HAVING count(*)>1) d),
        'duplicateExternalDocuments',(SELECT count(*) FROM (SELECT external_source,kind,external_number_key
            FROM financial_operation WHERE storage_status='POSTED' AND kind IN ('CHARGE','PAYMENT')
              AND external_number_key IS NOT NULL GROUP BY external_source,kind,external_number_key HAVING count(*)>1) d),
        'invalidPostedState',(SELECT count(*) FROM financial_operation WHERE storage_status='POSTED'
            AND (posted_by IS NULL OR posted_at IS NULL OR idempotency_key IS NULL OR request_fingerprint IS NULL)),
        'invalidCorrections',(SELECT count(*) FROM corrections c LEFT JOIN financial_operation original
            ON original.id=c.corrects_operation_id WHERE original.id IS NULL OR original.storage_status<>'POSTED'
               OR original.kind NOT IN ('CHARGE','PAYMENT') OR original.account_id<>c.account_id
               OR c.operation_date<original.operation_date OR c.expected_correction_count IS DISTINCT FROM c.prior_count
               OR c.delta<>original.effect_sign*c.target_amount-original.delta-c.prior_effect)),
    'reports',jsonb_build_object(
        'CHARGES',jsonb_build_object('totalRows',m.charge_accounts,'originalCharges',round(m.charge,2)::text,
            'chargeCorrections',round(m.charge_correction,2)::text,'netCharges',round(m.charge+m.charge_correction,2)::text),
        'PAYMENTS',jsonb_build_object('totalRows',m.payment_accounts,'originalPayments',round(m.payment,2)::text,
            'paymentCorrections',round(m.payment_correction,2)::text,'netPayments',round(m.payment+m.payment_correction,2)::text),
        'DEBT',jsonb_build_object('totalRows',h.accounts,'debt',round(h.debt,2)::text,
            'advance',round(h.advance,2)::text,'balance',round(h.debt-h.advance,2)::text),
        'MOVEMENT',jsonb_build_object('totalRows',h.accounts,'openingBalance',round(h.opening,2)::text,
            'originalCharges',round(m.charge,2)::text,'originalPayments',round(m.payment,2)::text,
            'corrections',round(m.corrections,2)::text,'increase',round(m.increase,2)::text,
            'decrease',round(m.decrease,2)::text,'change',round(h.change,2)::text,
            'closingBalance',round(h.opening+h.change,2)::text)))::text
FROM snapshot s CROSS JOIN bounds b CROSS JOIN monthly m CROSS JOIN historical h;
ROLLBACK;
