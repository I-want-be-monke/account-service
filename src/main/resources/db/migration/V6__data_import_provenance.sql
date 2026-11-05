CREATE TABLE import_run (
    id uuid PRIMARY KEY,
    source_name varchar(255) NOT NULL CHECK (btrim(source_name) <> ''),
    source_sha256 char(64) NOT NULL UNIQUE CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    format_version varchar(16) NOT NULL CHECK (btrim(format_version) <> ''),
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    initiated_by uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    control_totals jsonb NOT NULL CHECK (jsonb_typeof(control_totals) = 'object')
);

-- The run is inserted after the ledger has been independently reconciled.
ALTER TABLE client ADD COLUMN import_run_id uuid REFERENCES import_run(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE personal_account ADD COLUMN import_run_id uuid REFERENCES import_run(id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE financial_operation ADD COLUMN import_run_id uuid REFERENCES import_run(id) DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION prevent_import_origin_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.import_run_id IS DISTINCT FROM OLD.import_run_id THEN
        RAISE EXCEPTION 'Import provenance is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER client_import_origin BEFORE UPDATE OF import_run_id ON client
    FOR EACH ROW EXECUTE FUNCTION prevent_import_origin_change();
CREATE TRIGGER account_import_origin BEFORE UPDATE OF import_run_id ON personal_account
    FOR EACH ROW EXECUTE FUNCTION prevent_import_origin_change();
CREATE TRIGGER operation_import_origin BEFORE UPDATE OF import_run_id ON financial_operation
    FOR EACH ROW EXECUTE FUNCTION prevent_import_origin_change();

GRANT SELECT, INSERT ON import_run TO account_runtime;
