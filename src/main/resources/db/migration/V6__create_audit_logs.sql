CREATE TABLE audit_logs (
    id            BIGSERIAL    PRIMARY KEY,
    actor_id      BIGINT,
    action        VARCHAR(40)  NOT NULL,
    resource_type VARCHAR(40)  NOT NULL,
    resource_id   BIGINT,
    ip            VARCHAR(45),
    user_agent    VARCHAR(255),
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_audit_actor_created ON audit_logs (actor_id, created_at DESC);
CREATE INDEX ix_audit_created ON audit_logs (created_at DESC);
