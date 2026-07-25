CREATE TABLE events (
    id        BIGSERIAL    PRIMARY KEY,
    owner_id  BIGINT       NOT NULL REFERENCES users (id),
    title     VARCHAR(200) NOT NULL,
    venue     VARCHAR(200) NOT NULL,
    starts_at TIMESTAMPTZ  NOT NULL,
    ends_at   TIMESTAMPTZ  NOT NULL,
    capacity  INTEGER      NOT NULL,
    published BOOLEAN      NOT NULL DEFAULT FALSE,
    version   BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT ck_events_capacity_positive CHECK (capacity > 0),
    CONSTRAINT ck_events_ends_after_starts CHECK (ends_at > starts_at)
);

CREATE INDEX ix_events_owner_id ON events (owner_id);
CREATE INDEX ix_events_published_starts_at ON events (published, starts_at);
