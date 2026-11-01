package africa.tesa.fairseat.image;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Signs Cloudinary upload parameters the way Cloudinary checks them: every parameter except
 * file, cloud_name, resource_type and api_key, with empty values left out, sorted by name, joined
 * as key=value with &amp;, the API secret appended, then SHA-1 as lowercase hex.
 * ImageSignerTest pins this against Cloudinary's published example.
 */
public final class ImageSigner {

    private static final Set<String> NOT_SIGNED = Set.of("file", "cloud_name", "resource_type", "api_key");

    private ImageSigner() {
    }

    public static String sign(Map<String, String> params, String apiSecret) {
        return sha1Hex(stringToSign(params) + apiSecret);
    }

    static String stringToSign(Map<String, String> params) {
        StringJoiner joined = new StringJoiner("&");
        new TreeMap<>(params).forEach((key, value) -> {
            if (!NOT_SIGNED.contains(key) && value != null && !value.isEmpty()) {
                joined.add(key + "=" + value);
            }
        });
        return joined.toString();
    }

    private static String sha1Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every Java runtime", e);
        }
    }
}
