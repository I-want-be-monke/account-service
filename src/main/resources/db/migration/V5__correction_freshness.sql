ALTER TABLE financial_operation ADD COLUMN expected_correction_count bigint;
ALTER TABLE financial_operation ADD CONSTRAINT operation_correction_revision CHECK (
    (kind='CORRECTION' AND expected_correction_count IS NOT NULL AND expected_correction_count >= 0)
    OR (kind<>'CORRECTION' AND expected_correction_count IS NULL));

-- The account row is locked by the application before any correction is posted.
-- Recheck in the database as a second line of defense against stale or fabricated deltas.
CREATE OR REPLACE FUNCTION protect_posted_operation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original financial_operation%ROWTYPE;
DECLARE correction_count bigint;
DECLARE current_effect numeric;
DECLARE desired_effect numeric;
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
            OR original.kind='CORRECTION' OR original.account_id<>NEW.account_id THEN
            RAISE EXCEPTION 'Invalid correction source' USING ERRCODE='23514';
        END IF;
        SELECT count(*), coalesce(sum(delta),0) INTO correction_count,current_effect
          FROM financial_operation WHERE corrects_operation_id=original.id AND storage_status='POSTED';
        current_effect := current_effect + original.delta;
        desired_effect := original.effect_sign * NEW.target_amount;
        IF correction_count <> NEW.expected_correction_count
            OR desired_effect = current_effect
            OR NEW.amount * NEW.effect_sign <> desired_effect - current_effect THEN
            RAISE EXCEPTION 'Stale or invalid correction effect' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
