CREATE TABLE IF NOT EXISTS customers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);

ALTER TABLE customers ADD COLUMN IF NOT EXISTS role VARCHAR(8) NOT NULL DEFAULT 'CUSTOMER'
    CHECK (role IN ('CUSTOMER', 'ADMIN'));

ALTER TABLE customers ADD COLUMN IF NOT EXISTS stripe_test_customer_id TEXT;

CREATE TABLE IF NOT EXISTS reservations (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    machine VARCHAR(100) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL CHECK (ends_at > starts_at),
    status VARCHAR(30) NOT NULL DEFAULT 'Confirmed'
);

CREATE INDEX IF NOT EXISTS reservations_customer_start
    ON reservations (customer_id, starts_at);


-- UC2: Store washing and drying machines.
CREATE TABLE IF NOT EXISTS machines (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    type VARCHAR(10) NOT NULL
        CHECK (type IN ('WASHER', 'DRYER')),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
        CHECK (status IN (
            'AVAILABLE',
            'RESERVED',
            'IN_USE',
            'OUT_OF_SERVICE'
        ))
);

ALTER TABLE machines ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

-- Seed an empty inventory. Renaming a sample machine must not recreate it on restart.
INSERT INTO machines (name, type, status)
SELECT name, type, status
FROM (VALUES
    ('Washer 01', 'WASHER', 'AVAILABLE'),
    ('Washer 02', 'WASHER', 'AVAILABLE'),
    ('Washer 03', 'WASHER', 'IN_USE'),
    ('Washer 04', 'WASHER', 'OUT_OF_SERVICE'),
    ('Dryer 01', 'DRYER', 'AVAILABLE'),
    ('Dryer 02', 'DRYER', 'AVAILABLE'),
    ('Dryer 03', 'DRYER', 'RESERVED')
) AS samples(name, type, status)
WHERE NOT EXISTS (SELECT 1 FROM machines)
ON CONFLICT (name) DO NOTHING;

CREATE INDEX IF NOT EXISTS machines_type_status
    ON machines (type, status);



-- UC3: Link reservations to machines using their IDs.
ALTER TABLE reservations
ADD COLUMN IF NOT EXISTS machine_id BIGINT
REFERENCES machines(id);

-- Link existing reservations to their matching machines.
UPDATE reservations r
SET machine_id = m.id
FROM machines m
WHERE r.machine = m.name
  AND r.machine_id IS NULL;

-- Enable support for exclusion constraints using BIGINT values.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Prevent overlapping confirmed reservations for the same machine.
-- Use a single-quoted body so Spring keeps the block together.
DO '
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = ''reservations_no_overlap''
          AND conrelid = ''reservations''::regclass
    ) THEN
        ALTER TABLE reservations
        ADD CONSTRAINT reservations_no_overlap
        EXCLUDE USING gist (
            machine_id WITH =,
            tstzrange(starts_at, ends_at, ''[)'') WITH &&
        ) WHERE (status = ''Confirmed'');
    END IF;
END;
';

CREATE TABLE IF NOT EXISTS reservation_payments (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations(id),
    amount_cents INTEGER NOT NULL CHECK (amount_cents > 0),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(9) NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED')),
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS reservation_payments_latest
    ON reservation_payments (reservation_id, id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS reservation_payments_one_success
    ON reservation_payments (reservation_id) WHERE status = 'SUCCEEDED';

ALTER TABLE reservation_payments
    ADD COLUMN IF NOT EXISTS stripe_payment_intent_id TEXT;


-- UC6: Notifications

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customers(id),
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS notifications_customer_created
    ON notifications (customer_id, created_at DESC);


-- UC8: Allow REFUNDED payments. Replace the original status check with one that includes it.
ALTER TABLE reservation_payments DROP CONSTRAINT IF EXISTS reservation_payments_status_check;
ALTER TABLE reservation_payments DROP CONSTRAINT IF EXISTS reservation_payments_status_allowed;
ALTER TABLE reservation_payments ADD CONSTRAINT reservation_payments_status_allowed
    CHECK (status IN ('SUCCEEDED', 'FAILED', 'REFUNDED'));
