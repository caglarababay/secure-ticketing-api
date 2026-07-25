ALTER TABLE events ADD COLUMN reserved_seats INTEGER NOT NULL DEFAULT 0;

ALTER TABLE events ADD CONSTRAINT ck_events_reserved_within_capacity
    CHECK (reserved_seats >= 0 AND reserved_seats <= capacity);

CREATE TABLE reservations (
    id         BIGSERIAL   PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events (id),
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    status     VARCHAR(20) NOT NULL,
    seats      INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_reservations_seats_positive CHECK (seats > 0)
);

CREATE INDEX ix_reservations_event_status ON reservations (event_id, status);
CREATE INDEX ix_reservations_user_id ON reservations (user_id);
