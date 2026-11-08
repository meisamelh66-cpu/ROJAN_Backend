CREATE TABLE salon_gallery_images
(
    id            UUID PRIMARY KEY,
    salon_id      UUID          NOT NULL REFERENCES salons (id),
    url           VARCHAR(2048) NOT NULL,
    display_order INTEGER       NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_salon_gallery_images_salon_id ON salon_gallery_images (salon_id);
