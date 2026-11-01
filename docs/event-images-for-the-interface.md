# Event images: how the interface uses them (contract v0.2.0)

For Ufoma. The service never receives the file: the browser uploads straight to Cloudinary using a
ticket the service signs.

## Showing images

Every event has `imageUrl`. It is a ready-to-use 1200 × 675 (16:9) image, or `null`.

- Use it as is in an `<img>` with `width="1200" height="675"`, or in a 16:9 box, so the layout
  doesn't jump while it loads. Add `loading="lazy"` on lists.
- When it is `null`, or the image fails to load (`onError`), show the default image for the
  event's `category`.
- Alt text: the event title, or empty `alt=""` if the title is already next to it.

## Uploading (create and edit event screens)

Run this only after the event exists: create it first, then upload.

```ts
async function uploadEventImage(eventId: string, file: File, token: string) {
  // 1. Get a signed ticket from our service
  const t = await api.post(`/events/${eventId}/image-uploads`, null, token); // 201
  if (file.size > t.maxBytes) throw new Error("Image must be 5 MB or less");
  const ext = file.name.split(".").pop()?.toLowerCase();
  if (!t.allowedFormats.includes(ext === "jpeg" ? "jpg" : ext)) throw new Error("Use JPG, PNG or WebP");

  // 2. Upload straight to Cloudinary: every field exactly as given, plus the file
  const form = new FormData();
  Object.entries(t.fields).forEach(([k, v]) => form.append(k, v as string));
  form.append("file", file);
  const res = await fetch(t.uploadUrl, { method: "POST", body: form }); // no Authorization header
  if (!res.ok) throw new Error("Upload failed. Try again.");

  // 3. Tell our service which image to use; it returns the updated event with imageUrl
  return api.put(`/events/${eventId}/image`, { publicId: t.publicId }, token);
}
```

Remove the image with `DELETE /events/{eventId}/image`, which returns the event with `imageUrl: null`.

## Rules that matter

- **Send `fields` unchanged.** Don't add, rename or reorder-encode them, and don't add `folder` or
  `public_id` yourself. Cloudinary checks the signature against exactly these values.
- **A ticket lasts an hour.** If the user waits longer, get a new one.
- **No `Authorization` header to Cloudinary.** It goes only to our service.
- **Organisers and admins only.** Hide the picker for attendees; the service refuses them anyway.

## Screen states for the picker

| State | Show |
|---|---|
| Empty | Category default image and a "Add a cover image" button |
| Uploading | Progress or spinner; disable Save |
| Success | The new image from the returned `imageUrl` |
| Error | The message, and keep the previous image |
| Too large or wrong type | Catch it before uploading, with the 5 MB / JPG, PNG, WebP rule |

## Error codes

| Code | Meaning | Interface does |
|---|---|---|
| `IMAGE_NOT_ALLOWED` (400) | Wrong event's upload, wrong type, or over 5 MB | Show the message; let them pick another file |
| `IMAGE_NOT_UPLOADED` (409) | Cloudinary doesn't have the file yet | Retry the PUT once after a second, then show the message |
| `IMAGES_NOT_CONFIGURED` (503) | Images are switched off on this deployment | Hide the picker; keep default images |
| `NOT_EVENT_OWNER` / `FORBIDDEN_ROLE` (403) | Not this event's organiser | Shouldn't happen if the picker is hidden |
