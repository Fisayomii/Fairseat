--liquibase formatted sql

--changeset fisayomi:003-event-image
-- The Cloudinary public id and version of an event's cover image. The service builds the
-- delivery URL from these, so size and format can change without touching the data.
ALTER TABLE events ADD COLUMN image_public_id varchar(255);
ALTER TABLE events ADD COLUMN image_version bigint;
ALTER TABLE events ADD CONSTRAINT events_image_complete
    CHECK ((image_public_id IS NULL) = (image_version IS NULL));
--rollback ALTER TABLE events DROP CONSTRAINT events_image_complete;
--rollback ALTER TABLE events DROP COLUMN image_version;
--rollback ALTER TABLE events DROP COLUMN image_public_id;
