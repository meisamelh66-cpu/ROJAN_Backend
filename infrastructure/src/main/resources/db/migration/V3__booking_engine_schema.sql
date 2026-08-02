CREATE TABLE working_hours
(
    id          UUID PRIMARY KEY,
    salon_id    UUID        NOT NULL REFERENCES salons (id),
    day_of_week VARCHAR(16) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (salon_id, day_of_week)
);
CREATE INDEX idx_working_hours_salon_id ON working_hours (salon_id);

CREATE TABLE working_hours_intervals
(
    working_hours_id UUID    NOT NULL REFERENCES working_hours (id) ON DELETE CASCADE,
    interval_order    INTEGER NOT NULL,
    start_time        TIME    NOT NULL,
    end_time          TIME    NOT NULL,
    PRIMARY KEY (working_hours_id, interval_order),
    CHECK (start_time < end_time)
);

CREATE TABLE specialist_weekly_availability
(
    id            UUID PRIMARY KEY,
    specialist_id UUID        NOT NULL REFERENCES specialists (id),
    day_of_week   VARCHAR(16) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (specialist_id, day_of_week)
);
CREATE INDEX idx_specialist_weekly_availability_specialist_id ON specialist_weekly_availability (specialist_id);

CREATE TABLE specialist_weekly_availability_intervals
(
    weekly_availability_id UUID    NOT NULL REFERENCES specialist_weekly_availability (id) ON DELETE CASCADE,
    interval_order          INTEGER NOT NULL,
    start_time              TIME    NOT NULL,
    end_time                TIME    NOT NULL,
    PRIMARY KEY (weekly_availability_id, interval_order),
    CHECK (start_time < end_time)
);

CREATE TABLE specialist_schedule_overrides
(
    id             UUID PRIMARY KEY,
    specialist_id  UUID        NOT NULL REFERENCES specialists (id),
    override_date  DATE        NOT NULL,
    reason         VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (specialist_id, override_date)
);
CREATE INDEX idx_schedule_overrides_specialist_id ON specialist_schedule_overrides (specialist_id);

CREATE TABLE specialist_schedule_override_intervals
(
    override_id    UUID    NOT NULL REFERENCES specialist_schedule_overrides (id) ON DELETE CASCADE,
    interval_order INTEGER NOT NULL,
    start_time     TIME    NOT NULL,
    end_time       TIME    NOT NULL,
    PRIMARY KEY (override_id, interval_order),
    CHECK (start_time < end_time)
);

CREATE TABLE specialist_leaves
(
    id            UUID PRIMARY KEY,
    specialist_id UUID        NOT NULL REFERENCES specialists (id),
    start_date    DATE        NOT NULL,
    end_date      DATE        NOT NULL,
    reason        VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (start_date <= end_date)
);
CREATE INDEX idx_specialist_leaves_specialist_date ON specialist_leaves (specialist_id, start_date, end_date);

CREATE TABLE specialist_blocks
(
    id            UUID PRIMARY KEY,
    specialist_id UUID        NOT NULL REFERENCES specialists (id),
    block_date    DATE        NOT NULL,
    start_time    TIME        NOT NULL,
    end_time      TIME        NOT NULL,
    reason        VARCHAR(500),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (start_time < end_time)
);
CREATE INDEX idx_specialist_blocks_specialist_date ON specialist_blocks (specialist_id, block_date);

CREATE TABLE bookings
(
    id            UUID PRIMARY KEY,
    salon_id      UUID        NOT NULL REFERENCES salons (id),
    service_id    UUID        NOT NULL REFERENCES services (id),
    specialist_id UUID        NOT NULL REFERENCES specialists (id),
    customer_id   UUID        NOT NULL REFERENCES users (id),
    start_time    TIMESTAMP   NOT NULL,
    end_time      TIMESTAMP   NOT NULL,
    status        VARCHAR(16) NOT NULL,
    notes         VARCHAR(1000),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (start_time < end_time)
);
CREATE INDEX idx_bookings_specialist_time ON bookings (specialist_id, start_time, end_time);
CREATE INDEX idx_bookings_salon_id ON bookings (salon_id);
CREATE INDEX idx_bookings_customer_id ON bookings (customer_id);
CREATE INDEX idx_bookings_status ON bookings (status);
