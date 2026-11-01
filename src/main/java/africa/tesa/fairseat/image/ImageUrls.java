package africa.tesa.fairseat.image;

import org.springframework.stereotype.Component;

/**
 * Builds the delivery URL for an event image. One shape for every screen: 1200 x 675 (16:9),
 * cropped to fill, with format and quality chosen by Cloudinary for each browser. The version in
 * the path means a replaced image is never served from an old cached copy.
 */
@Component
public class ImageUrls {

    static final String TRANSFORMATION = "c_fill,w_1200,h_675,f_auto,q_auto";

    private final CloudinaryConfig config;

    public ImageUrls(CloudinaryConfig config) {
        this.config = config;
    }

    /** Null when the event has no image or images are not configured. */
    public String eventImage(String publicId, Long version) {
        if (publicId == null || version == null || config.cloudName().isEmpty()) {
            return null;
        }
        return "https://res.cloudinary.com/" + config.cloudName() + "/image/upload/" + TRANSFORMATION
                + "/v" + version + "/" + publicId;
    }
}
