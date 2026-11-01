package africa.tesa.fairseat.image;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Looks an image up with Cloudinary's Admin API (GET resources/image/upload/{public_id}, HTTP
 * basic auth with the API key and secret). Only three plain fields are read from the reply, so a
 * small pattern match is used rather than a JSON library.
 */
@Component
public class CloudinaryImageStore implements ImageStore {

    private static final Logger LOG = LoggerFactory.getLogger(CloudinaryImageStore.class);
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");
    private static final Pattern FORMAT = Pattern.compile("\"format\"\\s*:\\s*\"([a-z0-9]+)\"");
    private static final Pattern BYTES = Pattern.compile("\"bytes\"\\s*:\\s*(\\d+)");

    private final CloudinaryConfig config;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public CloudinaryImageStore(CloudinaryConfig config) {
        this.config = config;
    }

    @Override
    public Optional<StoredImage> find(String publicId) {
        String path = URLEncoder.encode(publicId, StandardCharsets.UTF_8).replace("%2F", "/");
        URI uri = URI.create("https://api.cloudinary.com/v1_1/" + config.cloudName()
                + "/resources/image/upload/" + path);
        String credentials = Base64.getEncoder().encodeToString(
                (config.apiKey() + ":" + config.apiSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach Cloudinary", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking Cloudinary", e);
        }
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            LOG.warn("Cloudinary lookup failed status={} publicId={}", response.statusCode(), publicId);
            throw new IllegalStateException("Cloudinary answered " + response.statusCode());
        }
        String body = response.body();
        Matcher version = VERSION.matcher(body);
        if (!version.find()) {
            throw new IllegalStateException("Cloudinary reply had no version");
        }
        Matcher format = FORMAT.matcher(body);
        Matcher bytes = BYTES.matcher(body);
        return Optional.of(new StoredImage(publicId, Long.parseLong(version.group(1)),
                format.find() ? format.group(1) : "", bytes.find() ? Long.parseLong(bytes.group(1)) : 0L));
    }
}
