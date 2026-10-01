-- Minimal schema for booking-service integration tests (mirrors infra/postgres/init.sql).
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE venues (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    city TEXT NOT NULL,
    country TEXT NOT NULL,
    address TEXT,
    capacity INT NOT NULL,
    seat_map JSONB NOT NULL
);

CREATE TABLE performers (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    category TEXT NOT NULL,
    description TEXT,
    image_url TEXT
);

CREATE TABLE events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    description TEXT,
    category TEXT NOT NULL,
    performer_id UUID NOT NULL REFERENCES performers(id),
    venue_id UUID NOT NULL REFERENCES venues(id),
    starts_at TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL DEFAULT 'ONSALE',
    queue_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE tickets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL REFERENCES events(id),
    section TEXT NOT NULL,
    seat_row TEXT NOT NULL,
    seat_num INT NOT NULL,
    price_cents INT NOT NULL,
    status TEXT NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','SOLD')),
    booking_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (event_id, section, seat_row, seat_num)
);
CREATE INDEX idx_tickets_event_status ON tickets(event_id, status);

CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    event_id UUID NOT NULL REFERENCES events(id),
    status TEXT NOT NULL CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED','FAILED')),
    total_cents INT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE tickets ADD CONSTRAINT fk_tickets_booking
    FOREIGN KEY (booking_id) REFERENCES bookings(id);

CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES bookings(id),
    provider TEXT NOT NULL DEFAULT 'mock-stripe',
    provider_ref TEXT UNIQUE,
    idempotency_key TEXT UNIQUE,
    amount_cents INT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('REQUIRES_CONFIRMATION','SUCCEEDED','FAILED','REFUNDED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- outbox table exists for schema parity with other services; unused in these tests.
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id UUID NOT NULL,
    payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
