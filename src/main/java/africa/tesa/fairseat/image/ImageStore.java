package africa.tesa.fairseat.image;

import java.util.Optional;

/** Asks the image host what it actually holds, so the service never trusts the browser's word. */
public interface ImageStore {

    /** What the host holds under a public id. */
    record StoredImage(String publicId, long version, String format, long bytes) {
    }

    Optional<StoredImage> find(String publicId);
}
