CREATE TABLE idempotency_keys (
    id              BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    user_id         BIGINT       NOT NULL REFERENCES users (id),
    endpoint        VARCHAR(200) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    resource_id     BIGINT,
    response_hash   VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_idempotency_scope UNIQUE (user_id, idempotency_key, endpoint),
    CONSTRAINT ck_idempotency_completed_has_resource
        CHECK (status <> 'COMPLETED' OR resource_id IS NOT NULL)
);

CREATE INDEX ix_idempotency_expiry ON idempotency_keys (expires_at);
