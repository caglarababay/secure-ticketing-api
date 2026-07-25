ALTER TABLE reservations ADD COLUMN expires_at TIMESTAMPTZ;

UPDATE reservations
SET expires_at = created_at
WHERE status = 'PENDING';

CREATE INDEX ix_reservations_hold_expiry ON reservations (status, expires_at);
