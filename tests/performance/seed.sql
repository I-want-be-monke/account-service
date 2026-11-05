\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '20min';

DO $guard$
BEGIN
    IF current_database() <> 'account_service_e11'
       OR current_setting('accounts.e11_dedicated', true) IS DISTINCT FROM 'account-service-e11' THEN
        RAISE EXCEPTION 'E11 seed requires the dedicated account_service_e11 database';
    END IF;
    PERFORM pg_advisory_xact_lock(110120261106);
    IF current_setting('session_replication_role') <> 'origin'
       OR (SELECT count(*) FROM pg_trigger WHERE tgrelid='financial_operation'::regclass
           AND tgname IN ('financial_operation_prepare','financial_operation_immutable')
           AND tgenabled IN ('O','A')) <> 2
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid='personal_account'::regclass
           AND tgname='personal_account_immutable' AND tgenabled IN ('O','A')) THEN
        RAISE EXCEPTION 'E11 seed requires enabled ledger and account triggers';
    END IF;
    IF (SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4','5','6')) <> 6
       OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
        RAISE EXCEPTION 'E11 seed requires successful Flyway V1-V6';
    END IF;
    IF EXISTS (SELECT 1 FROM personal_account) OR EXISTS (SELECT 1 FROM financial_operation)
       OR EXISTS (SELECT 1 FROM import_run) OR EXISTS (SELECT 1 FROM audit_event WHERE action='E11_SEED_APPLIED') THEN
        RAISE EXCEPTION 'E11 seed requires an empty ledger; it never clears or reseeds a database';
    END IF;
    IF (SELECT count(*) FROM app_user WHERE login IN ('demo-admin','demo-operator','demo-accountant','demo-manager','demo-client')
        AND status='ACTIVE' AND login='demo-'||lower(role_code)) <> 5
       OR EXISTS (SELECT 1 FROM app_user WHERE login LIKE 'e11-%') THEN
        RAISE EXCEPTION 'Start the isolated dev application first to prepare the five demo roles';
    END IF;
END;
$guard$;

CREATE TEMP TABLE e11_parameters ON COMMIT DROP AS
SELECT :seed::bigint AS seed, :'profile'::text AS profile, :accounts::integer AS accounts,
       :clients::integer AS clients, :operations::integer AS operations,
       :'reportMonth'::date AS report_month,
       (:'reportMonth'::date - interval '11 months')::date AS history_start,
       (:'reportMonth'::date + interval '1 month' - interval '1 day')::date AS report_end,
       (SELECT id FROM app_user WHERE login='demo-accountant') AS accountant,
       md5('e11:'||:seed::text||':seed')::uuid AS request_id;

DO $parameters$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM e11_parameters
        WHERE seed BETWEEN 1 AND 999999999 AND report_month= date_trunc('month', report_month)::date
          AND report_end < (current_timestamp AT TIME ZONE 'Europe/Moscow')::date
          AND (profile,accounts,clients,operations) IN (('pilot',200000,150000,1000000),
              ('reduced',50000,37500,250000),('smoke',1200,900,6000))
    ) THEN
        RAISE EXCEPTION 'E11 seed parameters do not match a known profile or completed reporting month';
    END IF;
END;
$parameters$;

INSERT INTO client(id,client_type,display_name,identifier_type,identifier_value,identifier_key,status)
SELECT md5('e11:'||p.seed||':client:'||n)::uuid,
       CASE WHEN n % 8=0 THEN 'ORGANIZATION' ELSE 'PERSON' END,
       'E11 клиент '||lpad(n::text,6,'0'), 'DEMO', 'E11-'||p.seed||'-'||n, 'E11-'||p.seed||'-'||n, 'ACTIVE'
FROM e11_parameters p CROSS JOIN LATERAL generate_series(1,p.clients) AS g(n);

CREATE TEMP TABLE e11_accounts ON COMMIT DROP AS
SELECT n,md5('e11:'||p.seed||':account:'||n)::uuid AS id,
       'LS-'||(8000000000+n)::text AS number,
       md5('e11:'||p.seed||':client:'||(1+((n-1)%p.clients)))::uuid AS client_id,
       CASE WHEN n<=300 THEN 'OPEN' WHEN n % 25=0 THEN 'CLOSED'
            WHEN n % 17=0 THEN 'SUSPENDED' ELSE 'OPEN' END AS final_status
FROM e11_parameters p CROSS JOIN LATERAL generate_series(1,p.accounts) AS g(n);

INSERT INTO personal_account(id,account_number,client_id,status,opened_on,history_start_on,opening_balance,current_balance,currency)
SELECT a.id,a.number,a.client_id,'OPEN',p.history_start,p.history_start,0,0,'RUB'
FROM e11_accounts a CROSS JOIN e11_parameters p;

WITH roles(role_code,users,first_account) AS (
    VALUES ('CLIENT',180,1),('ACCOUNTANT',40,181),('OPERATOR',60,221),('MANAGER',18,281),('ADMIN',2,299)
), identities AS (
    SELECT r.role_code, r.first_account+n-1 AS account_n,
           'e11-'||lower(r.role_code)||'-'||lpad(n::text,3,'0') AS login
    FROM roles r CROSS JOIN LATERAL generate_series(1,r.users) g(n)
)
INSERT INTO app_user(id,login,full_name,password_hash,role_code,client_id,status)
SELECT md5('e11:'||p.seed||':user:'||i.login)::uuid,i.login,'E11 '||i.login,template.password_hash,i.role_code,
       CASE WHEN i.role_code='CLIENT' THEN a.client_id END,'ACTIVE'
FROM identities i JOIN app_user template ON template.login='demo-'||lower(i.role_code)
JOIN e11_accounts a ON a.n=i.account_n CROSS JOIN e11_parameters p;

CREATE TEMP TABLE e11_targets ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY n) AS slot,id,n
FROM e11_accounts WHERE final_status<>'CLOSED' AND (n<=300 OR n % 9<>0);
CREATE UNIQUE INDEX ON e11_targets(slot);
ANALYZE e11_targets;

-- Sixty percent of draws use the first tenth of eligible accounts, with at least
-- 300 hot accounts. Every fourth draw falls in the report month.
CREATE TEMP TABLE e11_journal ON COMMIT DROP AS
WITH population AS (SELECT count(*) AS total FROM e11_targets), draws AS (
    SELECT n::bigint AS ordinal,p.seed,
           CASE WHEN (n+p.seed)%10 < 6 THEN 1+(n::bigint*7919+p.seed)%greatest(300,pop.total/10)
                ELSE 1+(n::bigint*104729+p.seed)%pop.total END AS slot,
           (CASE WHEN n%4=0 THEN p.report_month
                 ELSE (p.history_start+((n::bigint*37+p.seed)%11)*interval '1 month')::date END)
              + ((n::bigint*17+p.seed)%27)::integer AS operation_date,
           CASE WHEN (n+p.seed)%3=0 THEN 'PAYMENT' ELSE 'CHARGE' END AS kind
    FROM e11_parameters p CROSS JOIN population pop
    CROSS JOIN LATERAL generate_series(1,p.operations*95/100) g(n)
)
SELECT d.ordinal,md5('e11:'||d.seed||':original:'||d.ordinal)::uuid AS id,t.id AS account_id,
       d.operation_date,d.kind,
       ((10000+(d.ordinal*97+d.seed)%10000)
           * CASE WHEN d.kind='PAYMENT' AND t.n%3=0 THEN 5 ELSE 1 END / 100.00)::numeric(19,2) AS amount,
       CASE WHEN d.kind='PAYMENT' THEN -1 ELSE 1 END AS effect_sign
FROM draws d JOIN e11_targets t ON t.slot=d.slot;

INSERT INTO financial_operation(id,account_id,kind,storage_status,operation_date,amount,effect_sign,period_start,basis,
                                payment_method,external_source,external_number,external_number_key,created_by,
                                created_at,updated_at)
SELECT j.id,j.account_id,j.kind,'PREPARED',j.operation_date,j.amount,j.effect_sign,
       CASE WHEN j.kind='CHARGE' THEN date_trunc('month',j.operation_date)::date END,
       'E11 synthetic original',CASE WHEN j.kind='PAYMENT' THEN 'MANUAL_TRANSFER' END,
       'E11_SEED','E11-'||p.seed||'-'||j.ordinal,lower('E11-'||p.seed||'-'||j.ordinal),p.accountant,
       (j.operation_date+time '10:00') AT TIME ZONE 'UTC', (j.operation_date+time '10:00') AT TIME ZONE 'UTC'
FROM e11_journal j CROSS JOIN e11_parameters p;

UPDATE financial_operation o SET storage_status='POSTED',posted_by=o.created_by,
    posted_at=(o.operation_date+time '11:00') AT TIME ZONE 'UTC',
    updated_at=(o.operation_date+time '11:00') AT TIME ZONE 'UTC',
    idempotency_key=md5('e11:post:'||o.id)::uuid,
    request_fingerprint=md5('e11:fp1:'||o.id)||md5('e11:fp2:'||o.id),version=1
WHERE o.storage_status='PREPARED' AND o.external_source='E11_SEED';

-- One correction for every nineteenth original yields exactly five percent
-- corrections in the final ledger. The target is one ruble below the original.
INSERT INTO financial_operation(id,account_id,kind,storage_status,operation_date,amount,effect_sign,basis,
                                corrects_operation_id,target_amount,correction_reason,expected_correction_count,created_by,
                                created_at,updated_at)
SELECT md5('e11:'||p.seed||':correction:'||j.ordinal)::uuid,j.account_id,'CORRECTION','PREPARED',
       least(p.report_end,j.operation_date+7+(j.ordinal%28)::integer),1.00,-j.effect_sign,
       'E11 synthetic correction',j.id,j.amount-1.00,'E11 fixture decreases the registered amount by one ruble',
       0,p.accountant,
       (least(p.report_end,j.operation_date+7+(j.ordinal%28)::integer)+time '11:30') AT TIME ZONE 'UTC',
       (least(p.report_end,j.operation_date+7+(j.ordinal%28)::integer)+time '11:30') AT TIME ZONE 'UTC'
FROM e11_journal j CROSS JOIN e11_parameters p WHERE j.ordinal%19=0;

UPDATE financial_operation o SET storage_status='POSTED',posted_by=o.created_by,
    posted_at=(o.operation_date+time '12:00') AT TIME ZONE 'UTC',
    updated_at=(o.operation_date+time '12:00') AT TIME ZONE 'UTC',
    idempotency_key=md5('e11:post:'||o.id)::uuid,
    request_fingerprint=md5('e11:fp1:'||o.id)||md5('e11:fp2:'||o.id),version=1
WHERE o.storage_status='PREPARED' AND o.kind='CORRECTION';

WITH balances AS (SELECT account_id,sum(delta) AS net,count(*) AS documents
                  FROM financial_operation WHERE storage_status='POSTED' GROUP BY account_id)
UPDATE personal_account a SET current_balance=a.opening_balance+b.net,version=b.documents
FROM balances b WHERE a.id=b.account_id;

INSERT INTO account_status_history(account_id,old_status,new_status,changed_at,changed_by,reason,request_id)
SELECT a.id,null,'OPEN',p.history_start::timestamp AT TIME ZONE 'UTC',null,'E11 synthetic opening',p.request_id
FROM e11_accounts a CROSS JOIN e11_parameters p;

UPDATE personal_account a SET status=fixture.final_status,
    closed_on=CASE WHEN fixture.final_status='CLOSED' THEN p.report_end END,version=a.version+1
FROM e11_accounts fixture CROSS JOIN e11_parameters p WHERE a.id=fixture.id AND fixture.final_status<>'OPEN';

INSERT INTO account_status_history(account_id,old_status,new_status,changed_at,changed_by,reason,request_id)
SELECT a.id,'OPEN',a.final_status,(p.report_end+time '16:00') AT TIME ZONE 'UTC',null,'E11 synthetic status',p.request_id
FROM e11_accounts a CROSS JOIN e11_parameters p WHERE a.final_status<>'OPEN';

INSERT INTO audit_event(occurred_at,actor_user_id,actor_type,actor_login_snapshot,category,action,
                        object_type,object_id,result,request_id,details)
SELECT o.posted_at,p.accountant,'SYSTEM','e11-seed','BUSINESS',
       CASE WHEN o.kind='CORRECTION' THEN 'OPERATION_CORRECTION_POSTED' ELSE 'OPERATION_POSTED' END,
       'OPERATION',o.id::text,'SUCCESS',md5('e11:audit:'||o.id)::uuid,
       jsonb_build_object('source','E11_SEED','seed',p.seed,'accountId',o.account_id,'delta',o.delta::text)
FROM financial_operation o CROSS JOIN e11_parameters p WHERE o.storage_status='POSTED';

DO $verify$
BEGIN
    IF (SELECT count(*) FROM personal_account)<>(SELECT accounts FROM e11_parameters)
       OR (SELECT count(*) FROM client WHERE identifier_key LIKE 'E11-%')<>(SELECT clients FROM e11_parameters)
       OR (SELECT count(*) FROM financial_operation WHERE storage_status='POSTED')<>(SELECT operations FROM e11_parameters)
       OR (SELECT count(*) FROM financial_operation WHERE kind='CORRECTION')<>(SELECT operations/20 FROM e11_parameters)
       OR EXISTS (SELECT 1 FROM financial_operation WHERE storage_status<>'POSTED')
       OR (SELECT count(*) FROM app_user WHERE login LIKE 'e11-%')<>300
       OR (SELECT count(*) FROM audit_event WHERE details->>'source'='E11_SEED')<>(SELECT operations FROM e11_parameters)
       OR EXISTS (SELECT 1 FROM personal_account a LEFT JOIN financial_operation o ON o.account_id=a.id
                  AND o.storage_status='POSTED' GROUP BY a.id
                  HAVING a.current_balance<>a.opening_balance+coalesce(sum(o.delta),0))
       OR (SELECT count(DISTINCT date_trunc('month',operation_date)) FROM financial_operation)<>12
       OR (SELECT count(DISTINCT status) FROM personal_account)<>3
       OR (SELECT count(DISTINCT sign(current_balance)) FROM personal_account)<>3 THEN
        RAISE EXCEPTION 'E11 generated data did not satisfy its control counts, history or balances';
    END IF;
END;
$verify$;

INSERT INTO audit_event(actor_user_id,actor_type,actor_login_snapshot,category,action,object_type,object_id,
                        result,request_id,details)
SELECT p.accountant,'SYSTEM','e11-seed','OPERATIONS','E11_SEED_APPLIED','E11_DATASET',p.request_id::text,
       'SUCCESS',p.request_id,jsonb_build_object('profile',p.profile,'seed',p.seed,'accounts',p.accounts,
            'clients',p.clients,'operations',p.operations,'reportMonth',p.report_month)
FROM e11_parameters p;

ANALYZE client;
ANALYZE personal_account;
ANALYZE financial_operation;
ANALYZE audit_event;
COMMIT;
