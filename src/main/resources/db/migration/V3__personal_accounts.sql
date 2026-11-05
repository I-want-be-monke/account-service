CREATE SEQUENCE personal_account_number_seq AS bigint START WITH 1 INCREMENT BY 1
    MINVALUE 1 MAXVALUE 9999999999 NO CYCLE;

CREATE TABLE personal_account (
    id uuid PRIMARY KEY,
    account_number varchar(32) NOT NULL UNIQUE
        CHECK (account_number ~ '^LS-[0-9]{10}$'),
    client_id uuid NOT NULL REFERENCES client(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL CHECK (status IN ('OPEN','SUSPENDED','CLOSED')),
    opened_on date NOT NULL,
    closed_on date,
    history_start_on date NOT NULL,
    opening_balance numeric(19,2) NOT NULL DEFAULT 0.00,
    current_balance numeric(19,2) NOT NULL DEFAULT 0.00,
    currency char(3) NOT NULL DEFAULT 'RUB' CHECK (currency = 'RUB'),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_history_from_open CHECK (history_start_on >= opened_on),
    CONSTRAINT account_closed_date CHECK (
        (status = 'CLOSED' AND closed_on IS NOT NULL AND closed_on >= opened_on) OR
        (status <> 'CLOSED' AND closed_on IS NULL)),
    CONSTRAINT account_closed_zero CHECK (status <> 'CLOSED' OR current_balance = 0)
);
CREATE INDEX account_client_status ON personal_account(client_id,status,id);
CREATE INDEX account_status_id ON personal_account(status,id);

CREATE TABLE account_status_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES personal_account(id) ON DELETE RESTRICT,
    old_status varchar(16) CHECK (old_status IN ('OPEN','SUSPENDED','CLOSED')),
    new_status varchar(16) NOT NULL CHECK (new_status IN ('OPEN','SUSPENDED','CLOSED')),
    changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    changed_by uuid REFERENCES app_user(id) ON DELETE RESTRICT,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    request_id uuid NOT NULL,
    CONSTRAINT history_initial_open CHECK (old_status IS NOT NULL OR new_status = 'OPEN'),
    CONSTRAINT history_changed CHECK (old_status IS NULL OR old_status <> new_status)
);
CREATE INDEX account_history_order ON account_status_history(account_id,changed_at,id);

-- Keys and opening state are immutable even to a runtime SQL update.
CREATE FUNCTION prevent_account_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_number IS DISTINCT FROM OLD.account_number
       OR NEW.client_id IS DISTINCT FROM OLD.client_id
       OR NEW.opened_on IS DISTINCT FROM OLD.opened_on
       OR NEW.history_start_on IS DISTINCT FROM OLD.history_start_on
       OR NEW.opening_balance IS DISTINCT FROM OLD.opening_balance
       OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'Account ownership and opening fields are immutable'
            USING ERRCODE = '23514';
    END IF;
    IF OLD.status = 'CLOSED' AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'Closed accounts cannot be reopened' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER personal_account_immutable BEFORE UPDATE ON personal_account
FOR EACH ROW EXECUTE FUNCTION prevent_account_identity_change();

GRANT SELECT, INSERT, UPDATE ON personal_account TO account_runtime;
GRANT SELECT, INSERT ON account_status_history TO account_runtime;
GRANT USAGE, SELECT ON SEQUENCE personal_account_number_seq, account_status_history_id_seq TO account_runtime;
