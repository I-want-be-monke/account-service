-- Only executed by the official PostgreSQL image on an EMPTY volume.
\set ON_ERROR_STOP on
\getenv app_password ACCOUNT_DB_PASSWORD
CREATE ROLE account_runtime NOLOGIN;
SELECT format('CREATE ROLE account_app LOGIN PASSWORD %L IN ROLE account_runtime', :'app_password') \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
