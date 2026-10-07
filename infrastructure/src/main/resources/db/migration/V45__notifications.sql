CREATE TABLE notifications
(
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    salon_id       UUID         NOT NULL REFERENCES salons (id) ON DELETE CASCADE,
    user_id        UUID         REFERENCES users (id) ON DELETE SET NULL,
    type           VARCHAR(50)  NOT NULL,
    title          VARCHAR(200) NOT NULL,
    message        TEXT         NOT NULL,
    severity       VARCHAR(20)  NOT NULL DEFAULT 'INFO',
    category       VARCHAR(50)  NOT NULL DEFAULT 'bookings',
    reference_id   VARCHAR(100),
    reference_type VARCHAR(50),
    is_read        BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at        TIMESTAMPTZ,
    read_by        UUID         REFERENCES users (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_salon_created
    ON notifications (salon_id, created_at DESC);

CREATE INDEX idx_notifications_salon_unread
    ON notifications (salon_id, created_at DESC)
    WHERE is_read = FALSE;

CREATE INDEX idx_notifications_reference
    ON notifications (salon_id, reference_type, reference_id);
