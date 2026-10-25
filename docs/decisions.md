# FairSeat decisions

One dated line per decision or contract change: what changed, why, and the two engineers who agreed.
Stack, areas and hosting rules are shared with BloodLink (see the BloodLink repository's `docs/decisions.md`);
only differences are recorded here.

> Fill in "Agreed by" before merging.

## Stack and areas

| Date | Decision | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-23 | Same stack, areas and pipeline as BloodLink: Java 25, Spring Boot 4.1.1, Postgres 17, Liquibase, Testcontainers | One set of conventions across both products, as the brief asks | |
| 2026-10-24 | Separate repositories per tier: `fairseat` (service, owns the contract) and `fairseat-web` (interface). Same reasoning, cost and drift check as BloodLink. | | |
| 2026-10-23 | Sign-up rule protected in the database: seat counter with `CHECK (seats_taken <= seats)` and a conditional update (detailed design Sat 24 Oct) | Brief: "Protect it in the database" | |

## Hosting

| Date checked | Tier | Provider and plan | Sleeps when idle? | Hours or credit limit | Database expiry or pause | Checked by |
| --- | --- | --- | --- | --- | --- | --- |
| | Service (Docker) | | | | n/a | |
| | Database | | | | | |
| | Interface (static) | | | | n/a | |

## Contract changes

| Date | Change | Why | Agreed by |
| --- | --- | --- | --- |
| 2026-10-23 | v0.0.1: `POST /sessions`, `GET /actuator/health`, shared `Error` shape | Prove the pipeline and deployment before the MVP paths | |
| | v0.1.0 (due Fri 23 Oct): events, sign-ups, waiting list, offers, My Events | MVP scope agreed with Ufoma | |
- 2026-10-25 — Contract v0.0.1 → v0.1.0. Added the MVP journey: GET/POST /events (search by q, category, location, from/to, organiserId), GET/PATCH /events/{eventId}, POST /events/{eventId}/cancellation, POST /events/{eventId}/signups, POST /signups/{signupId}/cancellation, POST /signups/{signupId}/acceptance, GET /me/signups, GET /me/notices. Error.code is now an enum the interface can switch on; Event and Signup carry `asOf` so screens can say when figures were computed; an expired offer is reported as OFFER_EXPIRED on read before the seat is moved on. Why: the Monday definition of done (create, sign up, wait, cancel, offer, accept, My Events). Agreed: Oluwafisayomi Aiyetogbon, Ufoma Onowho.
