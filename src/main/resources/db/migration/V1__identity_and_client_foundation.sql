-- E1 foundation; client CRUD and audit writers belong to E2/E3.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO account_runtime;

CREATE TABLE app_role (
    code varchar(24) PRIMARY KEY CHECK (code IN ('ADMIN','OPERATOR','ACCOUNTANT','MANAGER','CLIENT')),
    display_name varchar(80) NOT NULL UNIQUE CHECK (btrim(display_name) <> '')
);
INSERT INTO app_role(code, display_name) VALUES
 ('ADMIN','Администратор'), ('OPERATOR','Оператор'),
 ('ACCOUNTANT','Бухгалтер-расчетчик'), ('MANAGER','Руководитель'), ('CLIENT','Клиент');

CREATE TABLE client (
    id uuid PRIMARY KEY,
    client_type varchar(16) NOT NULL CHECK (client_type IN ('PERSON','ORGANIZATION')),
    display_name varchar(200) NOT NULL CHECK (btrim(display_name) <> ''),
    identifier_type varchar(32) NOT NULL CHECK (btrim(identifier_type) <> ''),
    identifier_value varchar(100) NOT NULL CHECK (btrim(identifier_value) <> ''),
    identifier_key varchar(100) NOT NULL CHECK (btrim(identifier_key) <> ''),
    phone varchar(32), email varchar(254),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ARCHIVED')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT client_identifier_unique UNIQUE(client_type,identifier_type,identifier_key)
);
CREATE INDEX client_name_prefix ON client (lower(display_name) text_pattern_ops);
CREATE INDEX client_status_id ON client(status,id);

CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    login varchar(100) NOT NULL CHECK (login = lower(btrim(login)) AND login <> ''),
    full_name varchar(200) NOT NULL CHECK (btrim(full_name) <> ''),
    password_hash varchar(255) NOT NULL CHECK (password_hash ~ '^\$2[aby]\$12\$[./A-Za-z0-9]{53}$'),
    role_code varchar(24) NOT NULL REFERENCES app_role(code) ON DELETE RESTRICT,
    client_id uuid UNIQUE REFERENCES client(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','BLOCKED')),
    auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_client_role CHECK (
       (role_code = 'CLIENT' AND client_id IS NOT NULL) OR
       (role_code <> 'CLIENT' AND client_id IS NULL))
);
CREATE UNIQUE INDEX app_user_login_unique ON app_user(lower(login));
CREATE INDEX app_user_role_status ON app_user(role_code,status,id);

CREATE TABLE audit_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id uuid REFERENCES app_user(id) ON DELETE RESTRICT,
    actor_type varchar(16) NOT NULL CHECK (actor_type IN ('USER','ANONYMOUS','SYSTEM')),
    actor_login_snapshot varchar(100),
    category varchar(16) NOT NULL CHECK (category IN ('SECURITY','BUSINESS','ACCESS','OPERATIONS')),
    action varchar(64) NOT NULL,
    object_type varchar(32), object_id varchar(64),
    result varchar(16) NOT NULL CHECK (result IN ('SUCCESS','DENIED','FAILED','REPLAYED')),
    reason_code varchar(64), request_id uuid NOT NULL,
    before_data jsonb, after_data jsonb, details jsonb
);
CREATE INDEX audit_time ON audit_event(occurred_at DESC,id DESC);
CREATE INDEX audit_actor ON audit_event(actor_user_id,occurred_at DESC);
CREATE INDEX audit_object ON audit_event(object_type,object_id,occurred_at DESC);
CREATE INDEX audit_request ON audit_event(request_id);
CREATE INDEX audit_category ON audit_event(category,occurred_at DESC);

GRANT SELECT ON app_role TO account_runtime;
GRANT SELECT, INSERT, UPDATE ON app_user, client TO account_runtime;
GRANT SELECT, INSERT ON audit_event TO account_runtime;
GRANT USAGE, SELECT ON SEQUENCE audit_event_id_seq TO account_runtime;
