package africa.tesa.fairseat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import africa.tesa.fairseat.auth.Role;
import africa.tesa.fairseat.image.ImageSigner;
import africa.tesa.fairseat.image.ImageStore.StoredImage;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Event images (ADR 0003). Cloudinary is mocked: the tests prove who may upload, that the ticket
 * is signed correctly, and that only an image the host really holds, under this event's own
 * folder, can be attached.
 */
class EventImageIT extends IntegrationTestBase {

    private static final String SECRET = "test-secret-not-real";
    private static final Pattern FIELD = Pattern.compile("\"(%s)\"\\s*:\\s*\"([^\"]*)\"");

    @Test
    void attendeeCannotGetAnUploadTicket() {
        UUID eventId = event(user(Role.ORGANISER), 10);
        TestUser attendee = user(Role.ATTENDEE);

        HttpResponse<String> r = post("/events/" + eventId + "/image-uploads", attendee.token(), null);

        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("\"code\":\"FORBIDDEN_ROLE\"");
    }

    @Test
    void anotherOrganiserCannotGetAnUploadTicketOrSetTheImage() {
        UUID eventId = event(user(Role.ORGANISER), 10);
        TestUser other = user(Role.ORGANISER);

        assertThat(post("/events/" + eventId + "/image-uploads", other.token(), null).body())
                .contains("\"code\":\"NOT_EVENT_OWNER\"");
        HttpResponse<String> r = put("/events/" + eventId + "/image", other.token(),
                "{\"publicId\":\"fairseat/events/" + eventId + "/cover-1\"}");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(hasImage(eventId)).isFalse();
    }

    @Test
    void ownerGetsATicketSignedForThisEventOnly() {
        TestUser owner = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);

        HttpResponse<String> r = post("/events/" + eventId + "/image-uploads", owner.token(), null);

        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.body()).contains("https://api.cloudinary.com/v1_1/test-cloud/image/upload");
        String publicId = field(r, "public_id");
        assertThat(publicId).startsWith("fairseat/events/" + eventId + "/cover-");
        assertThat(r.body()).doesNotContain(SECRET);

        Map<String, String> signed = new LinkedHashMap<>();
        for (String key : new String[] {"timestamp", "public_id", "allowed_formats", "transformation"}) {
            signed.put(key, field(r, key));
        }
        assertThat(field(r, "signature")).isEqualTo(ImageSigner.sign(signed, SECRET));
        assertThat(field(r, "api_key")).isEqualTo("123456789012345");
    }

    @Test
    void imageFromAnotherEventsFolderIsRefused() {
        TestUser owner = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);
        UUID otherEvent = event(owner, 10);

        HttpResponse<String> r = put("/events/" + eventId + "/image", owner.token(),
                "{\"publicId\":\"fairseat/events/" + otherEvent + "/cover-1700000000\"}");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"IMAGE_NOT_ALLOWED\"");
        assertThat(hasImage(eventId)).isFalse();
    }

    @Test
    void imageThatWasNeverUploadedIsRefused() {
        TestUser owner = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);
        String publicId = "fairseat/events/" + eventId + "/cover-1700000000";
        when(imageStore.find(publicId)).thenReturn(Optional.empty());

        HttpResponse<String> r = put("/events/" + eventId + "/image", owner.token(),
                "{\"publicId\":\"" + publicId + "\"}");

        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("\"code\":\"IMAGE_NOT_UPLOADED\"");
    }

    @Test
    void tooLargeOrWrongFormatIsRefused() {
        TestUser owner = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);
        String publicId = "fairseat/events/" + eventId + "/cover-1700000000";
        when(imageStore.find(publicId)).thenReturn(Optional.of(new StoredImage(publicId, 1L, "gif", 1000L)));

        HttpResponse<String> r = put("/events/" + eventId + "/image", owner.token(),
                "{\"publicId\":\"" + publicId + "\"}");

        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("\"code\":\"IMAGE_NOT_ALLOWED\"");
    }

    @Test
    void uploadedImageIsAttachedShownToVisitorsAndCanBeRemoved() {
        TestUser owner = user(Role.ORGANISER);
        UUID eventId = event(owner, 10);
        String publicId = "fairseat/events/" + eventId + "/cover-1700000000";
        when(imageStore.find(publicId)).thenReturn(Optional.of(new StoredImage(publicId, 1712345678L, "jpg", 250_000L)));

        HttpResponse<String> attached = put("/events/" + eventId + "/image", owner.token(),
                "{\"publicId\":\"" + publicId + "\"}");

        assertThat(attached.statusCode()).isEqualTo(200);
        String expected = "https://res.cloudinary.com/test-cloud/image/upload/c_fill,w_1200,h_675,f_auto,q_auto/"
                + "v1712345678/" + publicId;
        assertThat(attached.body()).contains("\"imageUrl\":\"" + expected + "\"");
        assertThat(get("/events/" + eventId, null).body()).contains(expected);

        HttpResponse<String> removed = delete("/events/" + eventId + "/image", owner.token());
        assertThat(removed.statusCode()).isEqualTo(200);
        assertThat(removed.body()).contains("\"imageUrl\":null");
        assertThat(hasImage(eventId)).isFalse();
    }

    @Test
    void eventsWithoutAnImageHaveANullImageUrl() {
        UUID eventId = event(user(Role.ORGANISER), 10);
        assertThat(get("/events/" + eventId, null).body()).contains("\"imageUrl\":null");
    }

    /** Reads the column directly. A boolean, because JdbcClient's single() refuses a null value. */
    private boolean hasImage(UUID eventId) {
        return jdbc.sql("SELECT image_public_id IS NOT NULL FROM events WHERE id = :id").param("id", eventId)
                .query(Boolean.class).single();
    }

    private static String field(HttpResponse<String> r, String name) {
        Matcher m = Pattern.compile(String.format(FIELD.pattern(), Pattern.quote(name))).matcher(r.body());
        if (!m.find()) {
            throw new AssertionError("No " + name + " in: " + r.body());
        }
        return m.group(2);
    }
}
