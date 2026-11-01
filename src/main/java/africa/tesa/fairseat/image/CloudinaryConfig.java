package africa.tesa.fairseat.image;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cloudinary settings from the environment. When the cloud name or keys are missing the service
 * still starts; image endpoints answer 503 IMAGES_NOT_CONFIGURED and events have no imageUrl.
 */
@Component
public class CloudinaryConfig {

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String uploadPreset;

    public CloudinaryConfig(@Value("${CLOUDINARY_CLOUD_NAME:}") String cloudName,
                            @Value("${CLOUDINARY_API_KEY:}") String apiKey,
                            @Value("${CLOUDINARY_API_SECRET:}") String apiSecret,
                            @Value("${CLOUDINARY_UPLOAD_PRESET:}") String uploadPreset) {
        this.cloudName = cloudName.trim();
        this.apiKey = apiKey.trim();
        this.apiSecret = apiSecret.trim();
        this.uploadPreset = uploadPreset.trim();
    }

    public boolean configured() {
        return !cloudName.isEmpty() && !apiKey.isEmpty() && !apiSecret.isEmpty();
    }

    public String cloudName() {
        return cloudName;
    }

    public String apiKey() {
        return apiKey;
    }

    public String apiSecret() {
        return apiSecret;
    }

    /** Optional signed upload preset (for example one that caps file size). Empty when unset. */
    public String uploadPreset() {
        return uploadPreset;
    }
}
