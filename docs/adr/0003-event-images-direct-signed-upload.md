# ADR 0003: Event images are uploaded straight to Cloudinary with a signed ticket

- Status: accepted
- Date: 2026-11-01
- Deciders: Oluwafisayomi Aiyetogbon (service), Ufoma Onowho (interface)

## Context

The interface needs a cover image per event, chosen by the organiser. The service runs on a free
Render instance with little memory, and Render's free disk is wiped on every deploy, so the
service can't store files itself. Images also have to load quickly on phones on slow connections.

## Decision

Images live in Cloudinary (free plan). The service never handles the file:

1. `POST /events/{id}/image-uploads`: after the organiser-only check, the service signs one upload
   ticket. The ticket fixes the public id under `fairseat/events/{id}/`, allows only JPG, PNG and
   WebP, scales stored images down to fit 1600 × 1600, and expires after an hour.
2. The browser posts the file straight to Cloudinary with that ticket.
3. `PUT /events/{id}/image {publicId}`: the service checks that the id belongs to this event's
   folder. It then asks Cloudinary's Admin API whether the image really exists and reads its
   version, format and size itself, and stores only the public id and version (changeset 003).
4. Every event response carries `imageUrl`, built by the service as one 1200 × 675 size with
   `f_auto,q_auto`. Cloudinary then sends WebP or AVIF to browsers that support it, and the version
   in the URL stops old copies being served from a cache.

The API secret stays in Render's environment. The interface only ever sees the signed fields.

## Alternatives rejected

1. **Upload through the service** (multipart to Spring, then on to Cloudinary). Every image would
   pass through a 512 MB instance with a 350 MB heap. A few large uploads at once could exhaust
   memory, and uploads would fail whenever Render is waking up.
2. **Organiser pastes an image URL.** It needs no storage, but leads to broken links, slow or huge
   images from other sites, mixed content, and no control over size or format.
3. **Unsigned upload preset.** It needs no service endpoint, but anyone who reads the interface
   code can upload anything to our account and use up the free credits.
4. **Store files in Postgres (bytea).** The database is on Neon's free plan with 1 GB per project;
   images would fill it and slow every backup.

## Cost accepted

- **A third free tier to keep alive**, with its own credit limit: 25 monthly credits, where one
  credit is 1,000 transformations, 1 GB of storage or 1 GB of bandwidth. If credits run out, images
  stop loading, so the interface always falls back to a default image per category.
- **Replaced and removed images stay in Cloudinary.** The service does not delete them, so storage
  grows slowly.
- **Attaching needs an outbound call** to Cloudinary's Admin API, which is rate-limited. If
  Cloudinary is unreachable, setting an image fails with a 500, but every other feature keeps
  working.
- **No moderation.** Organisers can upload any picture.

## What would make us reverse it

If Cloudinary's usage page shows the free credits more than about 70% used in a normal month, we
would move images to a cheaper object store behind a CDN and serve pre-sized copies.

The same applies if attach failures caused by Admin API limits appear in the logs (`Cloudinary
lookup failed`). In that case we would verify Cloudinary's upload-response signature instead of
calling the Admin API.
