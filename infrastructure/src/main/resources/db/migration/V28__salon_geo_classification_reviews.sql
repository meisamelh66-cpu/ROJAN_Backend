-- ROJAN Verification: geographic classification is an independent verification
-- item (neighborhood/local vs. city-center), declared by the owner (on salons,
-- V25) and separately verified by a reviewer per verification case - kept in its
-- own history table, not overwritten in place, so a later re-review's decision
-- never destroys what an earlier case verified. One row per verification case
-- that actually touches this classification (not every case is required to);
-- verified_neighborhood/verified_city_center stay NULL until a reviewer records
-- a decision for that case.
CREATE TABLE salon_geo_classification_reviews
(
    id                    UUID        PRIMARY KEY,
    salon_id              UUID        NOT NULL REFERENCES salons (id),
    declared_neighborhood BOOLEAN     NOT NULL,
    declared_city_center  BOOLEAN     NOT NULL,
    verification_id       UUID        NOT NULL REFERENCES salon_verifications (id),
    verified_neighborhood BOOLEAN,
    verified_city_center  BOOLEAN,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_salon_geo_classification_reviews_salon_id ON salon_geo_classification_reviews (salon_id);
CREATE INDEX idx_salon_geo_classification_reviews_verification_id ON salon_geo_classification_reviews (verification_id);
