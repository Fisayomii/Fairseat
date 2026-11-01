package africa.tesa.fairseat.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring, no Docker. Runs with mvn test or mvn verify -DskipITs. */
class ImageSignerTest {

    /** Cloudinary's published example: these parameters and secret "abcd" give this signature. */
    @Test
    void matchesCloudinaryPublishedExample() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timestamp", "1315060510");
        params.put("public_id", "sample_image");
        params.put("eager", "w_400,h_300,c_pad|w_260,h_200,c_crop");

        assertThat(ImageSigner.sign(params, "abcd")).isEqualTo("bfd09f95f331f558cbd1320e67aa8d488770583e");
    }

    @Test
    void sortsByNameAndJoinsWithAmpersands() {
        assertThat(ImageSigner.stringToSign(Map.of("timestamp", "2", "allowed_formats", "jpg,png", "public_id", "a/b")))
                .isEqualTo("allowed_formats=jpg,png&public_id=a/b&timestamp=2");
    }

    @Test
    void leavesOutUnsignedAndEmptyParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("timestamp", "2");
        params.put("api_key", "123");
        params.put("file", "data");
        params.put("cloud_name", "c");
        params.put("resource_type", "image");
        params.put("upload_preset", "");

        assertThat(ImageSigner.stringToSign(params)).isEqualTo("timestamp=2");
    }
}
