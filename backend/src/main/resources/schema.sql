CREATE TABLE IF NOT EXISTS customers (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);

ALTER TABLE customers ADD COLUMN IF NOT EXISTS role VARCHAR(8) NOT NULL DEFAULT 'CUSTOMER'
    CHECK (role IN ('CUSTOMER', 'ADMIN'));

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

-- Add sample machines for testing UC2.
INSERT INTO machines (name, type, status)
VALUES
    ('Washer 01', 'WASHER', 'AVAILABLE'),
    ('Washer 02', 'WASHER', 'AVAILABLE'),
    ('Washer 03', 'WASHER', 'IN_USE'),
    ('Washer 04', 'WASHER', 'OUT_OF_SERVICE'),
    ('Dryer 01', 'DRYER', 'AVAILABLE'),
    ('Dryer 02', 'DRYER', 'AVAILABLE'),
    ('Dryer 03', 'DRYER', 'RESERVED')
ON CONFLICT (name) DO NOTHING;

CREATE INDEX IF NOT EXISTS machines_type_status
    ON machines (type, status);
