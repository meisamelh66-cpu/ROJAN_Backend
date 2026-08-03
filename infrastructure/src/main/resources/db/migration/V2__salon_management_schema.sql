CREATE TABLE salons
(
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL REFERENCES users (id),
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    phone       VARCHAR(32)  NOT NULL,
    email       VARCHAR(255),
    address     VARCHAR(500) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_salons_owner_id ON salons (owner_id);
CREATE INDEX idx_salons_active ON salons (active);

CREATE TABLE branches
(
    id         UUID PRIMARY KEY,
    salon_id   UUID         NOT NULL REFERENCES salons (id),
    name       VARCHAR(255) NOT NULL,
    address    VARCHAR(500) NOT NULL,
    phone      VARCHAR(32)  NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_branches_salon_id ON branches (salon_id);

CREATE TABLE service_categories
(
    id          UUID PRIMARY KEY,
    salon_id    UUID         NOT NULL REFERENCES salons (id),
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_service_categories_salon_id ON service_categories (salon_id);

CREATE TABLE services
(
    id                UUID PRIMARY KEY,
    salon_id          UUID           NOT NULL REFERENCES salons (id),
    category_id       UUID           NOT NULL REFERENCES service_categories (id),
    name              VARCHAR(255)   NOT NULL,
    description       VARCHAR(2000),
    duration_minutes  INTEGER        NOT NULL CHECK (duration_minutes > 0),
    price             NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    active            BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_services_salon_id ON services (salon_id);
CREATE INDEX idx_services_category_id ON services (category_id);

CREATE TABLE specialists
(
    id           UUID PRIMARY KEY,
    salon_id     UUID         NOT NULL REFERENCES salons (id),
    user_id      UUID REFERENCES users (id),
    display_name VARCHAR(255) NOT NULL,
    bio          VARCHAR(2000),
    photo_url    VARCHAR(1000),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_specialists_salon_id ON specialists (salon_id);
CREATE INDEX idx_specialists_user_id ON specialists (user_id);
