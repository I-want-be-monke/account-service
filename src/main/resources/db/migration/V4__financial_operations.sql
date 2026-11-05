CREATE TABLE financial_operation (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES personal_account(id) ON DELETE RESTRICT,
    kind varchar(16) NOT NULL CHECK (kind IN ('CHARGE','PAYMENT','CORRECTION')),
    storage_status varchar(16) NOT NULL CHECK (storage_status IN ('PREPARED','POSTED')),
    operation_date date NOT NULL,
    amount numeric(19,2) NOT NULL CHECK (amount > 0),
    effect_sign smallint NOT NULL CHECK (effect_sign IN (-1,1)),
    delta numeric(19,2) GENERATED ALWAYS AS (amount * effect_sign) STORED,
    period_start date,
    basis varchar(500) NOT NULL CHECK (btrim(basis) <> ''),
    payment_method varchar(32),
    external_source varchar(64),
    external_number varchar(100),
    external_number_key varchar(100),
    corrects_operation_id uuid,
    target_amount numeric(19,2),
    correction_reason varchar(1000),
    created_by uuid NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    posted_by uuid REFERENCES app_user(id) ON DELETE RESTRICT,
    posted_at timestamptz,
    idempotency_key uuid UNIQUE,
    request_fingerprint char(64),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT operation_account_key UNIQUE(id,account_id),
    CONSTRAINT operation_original_account FOREIGN KEY (corrects_operation_id,account_id)
        REFERENCES financial_operation(id,account_id) ON DELETE RESTRICT,
    CONSTRAINT operation_kind_sign CHECK (
        (kind='CHARGE' AND effect_sign=1 AND period_start IS NOT NULL
            AND extract(day from period_start)=1)
        OR (kind='PAYMENT' AND effect_sign=-1 AND period_start IS NULL)
        OR (kind='CORRECTION' AND period_start IS NULL)),
    CONSTRAINT operation_correction_fields CHECK (
        (kind='CORRECTION' AND corrects_operation_id IS NOT NULL AND target_amount >= 0
            AND correction_reason IS NOT NULL AND btrim(correction_reason) <> '')
        OR (kind<>'CORRECTION' AND corrects_operation_id IS NULL
            AND target_amount IS NULL AND correction_reason IS NULL)),
    CONSTRAINT operation_payment_fields CHECK (
        (kind='PAYMENT' AND payment_method IN ('MANUAL_TRANSFER','CASH_REGISTRATION')
            AND external_source IS NOT NULL AND btrim(external_source) <> ''
            AND external_number IS NOT NULL AND btrim(external_number) <> ''
            AND external_number_key = lower(btrim(external_number)) AND external_number_key <> '')
        OR (kind<>'PAYMENT' AND payment_method IS NULL)),
    CONSTRAINT operation_document_fields CHECK (
        (external_number IS NULL AND external_number_key IS NULL)
        OR (external_number IS NOT NULL AND external_source IS NOT NULL
            AND btrim(external_source) <> ''
            AND external_number_key = lower(btrim(external_number))
            AND external_number_key <> '')),
    CONSTRAINT operation_posted_fields CHECK (
        (storage_status='PREPARED' AND posted_by IS NULL AND posted_at IS NULL
            AND idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (storage_status='POSTED' AND posted_by IS NOT NULL AND posted_at IS NOT NULL
            AND idempotency_key IS NOT NULL
            AND request_fingerprint ~ '^[0-9a-f]{64}$'))
);
CREATE UNIQUE INDEX financial_document_unique ON financial_operation
    (external_source,kind,external_number_key)
    WHERE storage_status='POSTED' AND kind IN ('CHARGE','PAYMENT') AND external_number_key IS NOT NULL;
CREATE INDEX financial_account_history ON financial_operation(account_id,operation_date,posted_at,id)
    WHERE storage_status='POSTED';
CREATE INDEX financial_corrections ON financial_operation(corrects_operation_id)
    WHERE storage_status='POSTED' AND corrects_operation_id IS NOT NULL;
CREATE INDEX financial_month_kind ON financial_operation(operation_date,kind,account_id)
    WHERE storage_status='POSTED';
CREATE INDEX financial_account_drafts ON financial_operation(account_id,storage_status,id);

CREATE FUNCTION protect_posted_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original financial_operation%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.storage_status='POSTED' THEN
            RAISE EXCEPTION 'Posted operations are immutable' USING ERRCODE='23514';
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.storage_status='POSTED' THEN
        RAISE EXCEPTION 'Posted operations are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.account_id IS DISTINCT FROM OLD.account_id
        OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION 'Draft identity and kind are immutable' USING ERRCODE='23514';
    END IF;
    IF NEW.storage_status='POSTED' AND NEW.kind='CORRECTION' THEN
        SELECT * INTO original FROM financial_operation WHERE id=NEW.corrects_operation_id;
        IF original.id IS NULL OR original.storage_status<>'POSTED'
            OR original.kind='CORRECTION' OR original.account_id<>NEW.account_id
            OR (original.kind='CHARGE' AND NEW.effect_sign NOT IN (-1,1))
            OR (original.kind='PAYMENT' AND NEW.effect_sign NOT IN (-1,1)) THEN
            RAISE EXCEPTION 'Invalid correction source' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER financial_operation_immutable BEFORE UPDATE OR DELETE ON financial_operation
    FOR EACH ROW EXECUTE FUNCTION protect_posted_operation();

-- Runtime creates a PREPARED row first; a direct POSTED insert would bypass balance and audit.
CREATE FUNCTION require_prepared_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.storage_status <> 'PREPARED' THEN
        RAISE EXCEPTION 'Operations must be prepared before posting' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER financial_operation_prepare BEFORE INSERT ON financial_operation
    FOR EACH ROW EXECUTE FUNCTION require_prepared_operation();

GRANT SELECT, INSERT, UPDATE, DELETE ON financial_operation TO account_runtime;
