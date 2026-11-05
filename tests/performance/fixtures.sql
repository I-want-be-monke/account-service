\set ON_ERROR_STOP on
BEGIN TRANSACTION READ ONLY;
DO $guard$
BEGIN
    IF current_database() <> 'account_service_e11'
       OR current_setting('accounts.e11_dedicated', true) IS DISTINCT FROM 'account-service-e11' THEN
        RAISE EXCEPTION 'E11 fixtures require the dedicated account_service_e11 database';
    END IF;
END;
$guard$;
COPY (
    WITH offsets(role_code,base) AS (VALUES ('CLIENT',0),('ACCOUNTANT',180),('OPERATOR',220),('MANAGER',280),('ADMIN',298))
    SELECT u.role_code AS role,u.login,a.id AS "accountId",a.account_number AS "accountNumber",
           a.history_start_on AS "historyStartOn"
    FROM app_user u JOIN offsets x ON x.role_code=u.role_code
    JOIN personal_account a ON a.account_number='LS-'||(8000000000+x.base+right(u.login,3)::integer)::text
    WHERE u.login ~ '^e11-(client|accountant|operator|manager|admin)-[0-9]{3}$' AND a.status='OPEN'
      AND (u.role_code<>'CLIENT' OR a.client_id=u.client_id)
    ORDER BY u.role_code,u.login
) TO STDOUT WITH (FORMAT csv, HEADER true);
ROLLBACK;
