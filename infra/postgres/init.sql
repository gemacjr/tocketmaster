-- TocketMaster canonical schema + demo seed.
-- Executed automatically by the postgres container on first boot.
-- In production this is applied by a migration step in CI (see docs/09-infrastructure.md).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------- core entities

CREATE TABLE venues (
    id         UUID PRIMARY KEY,
    name       TEXT NOT NULL,
    city       TEXT NOT NULL,
    country    TEXT NOT NULL,
    address    TEXT,
    capacity   INT  NOT NULL,
    seat_map   JSONB NOT NULL          -- [{code, rows, seats, tier}] rendered client-side
);

CREATE TABLE performers (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    category    TEXT NOT NULL,
    description TEXT,
    image_url   TEXT
);

CREATE TABLE events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL,
    description   TEXT,
    category      TEXT NOT NULL,
    performer_id  UUID NOT NULL REFERENCES performers(id),
    venue_id      UUID NOT NULL REFERENCES venues(id),
    starts_at     TIMESTAMPTZ NOT NULL,
    status        TEXT NOT NULL DEFAULT 'ONSALE',   -- ONSALE | SOLDOUT | CANCELLED
    queue_enabled BOOLEAN NOT NULL DEFAULT FALSE,   -- virtual waiting room flag
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    version       BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_events_starts_at ON events(starts_at);
CREATE INDEX idx_events_category ON events(category);
CREATE INDEX idx_events_city ON events(venue_id);

-- ---------------------------------------------------------------- inventory / sales

CREATE TABLE tickets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id   UUID NOT NULL REFERENCES events(id),
    section    TEXT NOT NULL,
    seat_row   TEXT NOT NULL,
    seat_num   INT  NOT NULL,
    price_cents INT NOT NULL,
    status     TEXT NOT NULL DEFAULT 'AVAILABLE'
               CHECK (status IN ('AVAILABLE','SOLD')),  -- reservation state lives in Redis holds
    booking_id UUID,
    version    BIGINT NOT NULL DEFAULT 0,
    UNIQUE (event_id, section, seat_row, seat_num)
);

CREATE INDEX idx_tickets_event_status ON tickets(event_id, status);

CREATE TABLE bookings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     TEXT NOT NULL,
    event_id    UUID NOT NULL REFERENCES events(id),
    status      TEXT NOT NULL
                CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED','FAILED')),
    total_cents INT  NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bookings_user ON bookings(user_id, created_at DESC);
CREATE INDEX idx_bookings_sweep ON bookings(status, expires_at);

ALTER TABLE tickets
    ADD CONSTRAINT fk_tickets_booking FOREIGN KEY (booking_id) REFERENCES bookings(id);

CREATE TABLE payments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id      UUID NOT NULL REFERENCES bookings(id),
    provider        TEXT NOT NULL DEFAULT 'mock-stripe',
    provider_ref    TEXT UNIQUE,
    idempotency_key TEXT UNIQUE,
    amount_cents    INT  NOT NULL,
    status          TEXT NOT NULL
                    CHECK (status IN ('REQUIRES_CONFIRMATION','SUCCEEDED','FAILED','REFUNDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- CDC-lite outbox

CREATE TABLE outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type TEXT NOT NULL,           -- EVENT
    aggregate_id   UUID NOT NULL,
    payload        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unprocessed ON outbox(processed_at) WHERE processed_at IS NULL;

-- ---------------------------------------------------------------- seed data

INSERT INTO venues (id, name, city, country, address, capacity, seat_map) VALUES
('11111111-1111-1111-1111-111111111111', 'Ember Dome', 'Austin', 'USA', '200 Cesar Chavez St', 1480,
 '{"sections":[{"code":"FLOOR","rows":20,"seats":20,"tier":"PREMIUM"},{"code":"A","rows":15,"seats":24,"tier":"MID"},{"code":"B","rows":15,"seats":24,"tier":"STD"},{"code":"C","rows":15,"seats":24,"tier":"STD"}]}'),
('22222222-2222-2222-2222-222222222222', 'The Grand Hall', 'New York', 'USA', '88 Concert Way', 328,
 '{"sections":[{"code":"ORCH","rows":8,"seats":20,"tier":"PREMIUM"},{"code":"BOX","rows":4,"seats":6,"tier":"MID"},{"code":"BALC","rows":6,"seats":24,"tier":"STD"}]}');

INSERT INTO performers (id, name, category, description) VALUES
('aaaaaaa1-0000-0000-0000-000000000001', 'The Ember Kings', 'CONCERT', 'Stadium rock royalty on their farewell world tour.'),
('aaaaaaa1-0000-0000-0000-000000000002', 'Marina Voss', 'CONCERT', 'Chart-topping synth-pop vocalist.'),
('aaaaaaa1-0000-0000-0000-000000000003', 'Static Harbor', 'CONCERT', 'Alt-rock quartet touring behind ''Low Tide''.'),
('aaaaaaa1-0000-0000-0000-000000000004', 'Northline FC', 'SPORTS', 'Cross-town derby night under the lights.'),
('aaaaaaa1-0000-0000-0000-000000000005', 'Lunar Ballet Company', 'THEATER', 'The acclaimed staging of ''Selene''.'),
('aaaaaaa1-0000-0000-0000-000000000006', 'Dave Kim', 'COMEDY', 'Deadpan stand-up, one night only.');

INSERT INTO events (id, name, description, category, performer_id, venue_id, starts_at, queue_enabled) VALUES
('eeeeeee1-0000-0000-0000-000000000001', 'The Ember Kings — Farewell Tour',
 'The final tour. One night. All hits.', 'CONCERT',
 'aaaaaaa1-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111',
 now() + interval '45 days', TRUE),
('eeeeeee1-0000-0000-0000-000000000002', 'Marina Voss — Neon Hearts',
 'An evening of synth-pop anthems.', 'CONCERT',
 'aaaaaaa1-0000-0000-0000-000000000002', '22222222-2222-2222-2222-222222222222',
 now() + interval '20 days', FALSE),
('eeeeeee1-0000-0000-0000-000000000003', 'Static Harbor — Low Tide Tour',
 'Alt-rock deep cuts and new singles.', 'CONCERT',
 'aaaaaaa1-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111',
 now() + interval '60 days', FALSE),
('eeeeeee1-0000-0000-0000-000000000004', 'Northline FC vs River Rovers',
 'The derby that splits the city.', 'SPORTS',
 'aaaaaaa1-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111',
 now() + interval '12 days', FALSE),
('eeeeeee1-0000-0000-0000-000000000005', 'Selene — Lunar Ballet',
 'A moonlit masterpiece.', 'THEATER',
 'aaaaaaa1-0000-0000-0000-000000000005', '22222222-2222-2222-2222-222222222222',
 now() + interval '30 days', FALSE),
('eeeeeee1-0000-0000-0000-000000000006', 'Dave Kim Live',
 'No phones. No mercy.', 'COMEDY',
 'aaaaaaa1-0000-0000-0000-000000000006', '22222222-2222-2222-2222-222222222222',
 now() + interval '7 days', FALSE);

-- One ticket per seat in the venue's seat map, priced by tier (guide: "when a new event
-- is created we create a new ticket for each seat").
INSERT INTO tickets (event_id, section, seat_row, seat_num, price_cents)
SELECT e.id,
       sec.code,
       rw.rn::TEXT,
       sn.sn,
       CASE sec.tier WHEN 'PREMIUM' THEN 25000 WHEN 'MID' THEN 15000 ELSE 8000 END
FROM events e
JOIN venues v ON v.id = e.venue_id
CROSS JOIN LATERAL jsonb_to_recordset(v.seat_map -> 'sections')
    AS sec(code TEXT, "rows" INT, seats INT, tier TEXT)
CROSS JOIN LATERAL generate_series(1, sec."rows") AS rw(rn)
CROSS JOIN LATERAL generate_series(1, sec.seats) AS sn(sn);
