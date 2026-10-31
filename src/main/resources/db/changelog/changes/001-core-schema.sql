--liquibase formatted sql

--changeset fisayomi:001-users
CREATE TABLE users (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    display_name  text        NOT NULL CHECK (length(btrim(display_name)) > 0),
    password_hash text        NOT NULL,
    role          text        NOT NULL CHECK (role IN ('ATTENDEE', 'ORGANISER', 'ADMIN')),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));
--rollback DROP TABLE users;

--changeset fisayomi:001-events
CREATE TABLE events (
    id           uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    organiser_id uuid         NOT NULL REFERENCES users (id),
    title        varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description  text         NOT NULL DEFAULT '',
    category     text         NOT NULL CHECK (category IN ('TECH', 'DESIGN', 'PRODUCT', 'CAREER', 'BUSINESS', 'COMMUNITY')),
    location     varchar(200) NOT NULL CHECK (length(btrim(location)) > 0),
    starts_at    timestamptz  NOT NULL,
    ends_at      timestamptz  NOT NULL,
    seats        integer      NOT NULL CHECK (seats BETWEEN 1 AND 1000),
    -- GOING plus live OFFERED. Moved only by guarded UPDATEs in SignupRepository.
    seats_taken  integer      NOT NULL DEFAULT 0,
    status       text         NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CANCELLED')),
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT events_ends_after_start  CHECK (ends_at > starts_at)
);
CREATE INDEX idx_events_starts_at ON events (starts_at);
CREATE INDEX idx_events_organiser ON events (organiser_id, starts_at);
--rollback DROP TABLE events;

--changeset fisayomi:001-signups
CREATE TABLE signups (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Tie-break for the waiting list when created_at is equal.
    seq              bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_id         uuid        NOT NULL REFERENCES events (id),
    user_id          uuid        NOT NULL REFERENCES users (id),
    status           text        NOT NULL CHECK (status IN ('GOING', 'WAITING', 'OFFERED', 'OFFER_EXPIRED', 'CANCELLED')),
    offer_expires_at timestamptz,
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT signups_offer_has_expiry CHECK (status <> 'OFFERED' OR offer_expires_at IS NOT NULL)
);
-- One active sign-up per person per event.
CREATE UNIQUE INDEX uq_signups_active_per_user
    ON signups (event_id, user_id)
    WHERE status IN ('GOING', 'WAITING', 'OFFERED');
-- Next person in line.
CREATE INDEX idx_signups_waiting_queue
    ON signups (event_id, created_at, seq)
    WHERE status = 'WAITING';
-- Sweeper: offers whose window has passed.
CREATE INDEX idx_signups_offer_expiry
    ON signups (offer_expires_at)
    WHERE status = 'OFFERED';
-- My Events.
CREATE INDEX idx_signups_user ON signups (user_id);
--rollback DROP TABLE signups;

--changeset fisayomi:001-notices
CREATE TABLE notices (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    event_id   uuid        NOT NULL REFERENCES events (id),
    kind       text        NOT NULL CHECK (kind IN ('SEAT_OFFERED', 'OFFER_EXPIRED', 'EVENT_CHANGED', 'EVENT_CANCELLED')),
    message    text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    read_at    timestamptz
);
CREATE INDEX idx_notices_user_created ON notices (user_id, created_at DESC);
--rollback DROP TABLE notices;
