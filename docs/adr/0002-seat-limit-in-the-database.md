# ADR 0002: The seat limit is a counter guarded by the database

- Status: accepted
- Date: 2026-10-31
- Deciders: Oluwafisayomi Aiyetogbon (service), Ufoma Onowho (interface)

## Context

FairSeat's one rule is that confirmed attendees never exceed an event's seats, even when many
people sign up at the same moment. A check in Java ("count the going sign-ups, and insert if below
seats") is a read-then-write. Two requests can both read "1 of 2 taken" and both insert, which
overbooks the event.

## Decision

Each event row carries `seats_taken`, which counts GOING plus live OFFERED sign-ups. Taking a seat
is a single conditional statement:

```sql
UPDATE events SET seats_taken = seats_taken + 1
WHERE id = ? AND seats_taken < seats;
```

One row updated means GOING; zero rows means WAITING. PostgreSQL locks the row for the update and
re-checks the `WHERE` clause after any concurrent update commits, so at most `seats` requests can
succeed.

A second line of defence is `CHECK (seats_taken BETWEEN 0 AND seats)`. Even if a future code path
forgets the guard, the database rejects the overbooking write.

Every write that touches seats (sign-up, cancel, accept, expiry, event edit) first locks the event
row with `SELECT … FOR UPDATE`. That keeps one lock order and keeps the counter and the sign-up
rows in step.

`SeatConcurrencyIT` fires ten sign-ups for two seats from a latch and expects 2 GOING and 8 WAITING.
With the guard and the CHECK removed, it reports 10 GOING.

## Alternatives rejected

1. **Count rows at sign-up time inside a SERIALIZABLE transaction.** It is correct, but under
   contention PostgreSQL aborts most of the transactions with serialisation failures. Every caller
   then needs retry logic, and a burst of ten sign-ups turns into a retry storm.
2. **An application lock** (a Java `synchronized` block or a lock per event in memory). It works
   on one instance only, so it is gone on restart and wrong the moment there are two instances.
   It also protects nothing when someone calls the database by another route.
3. **A unique index over seat numbers** (seat 1, seat 2 …). It enforces the limit, but the service
   has to choose a free seat number, which is its own race. It also makes "release a seat and offer
   it" a renumbering problem.
4. **`SELECT … FOR UPDATE` on the event, then count rows.** It is correct, and we use the same lock
   for ordering. But the count is still a query the code could forget. The counter plus the CHECK
   puts the rule in the schema, where it can't be skipped.

## Cost accepted

- `seats_taken` is **derived data stored twice**: once as the counter and once implied by the
  sign-up rows. If a bug changes a sign-up's status without moving the counter, they drift.
  Mitigations:
  - All moves go through `SignupRepository` and `SeatAllocator`.
  - Event reads compute their figures from the rows, not the counter.
  - The CHECK stops the drift from ever exceeding the seats.
- Every seat write on one event queues behind that event's row lock. A very popular event processes
  sign-ups one at a time. For meetups of tens to hundreds of seats this is milliseconds per request.

## What would make us reverse it

If a reconciliation query ever finds `seats_taken` different from the count of GOING plus live
OFFERED rows for any event, the double bookkeeping has failed. That query is:

```sql
SELECT e.id FROM events e
WHERE e.seats_taken <> (SELECT count(*) FROM signups s WHERE s.event_id = e.id
                         AND s.status IN ('GOING','OFFERED'))
```

We would then drop the counter and move to alternative 4: lock the row and count, with the CHECK
replaced by a trigger.

The same applies if lock waits on one event's row show up as the main latency in a load test.
