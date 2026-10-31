# ADR 0001: The offer window shrinks as the event approaches

- Status: accepted
- Date: 2026-10-31
- Deciders: Oluwafisayomi Aiyetogbon (service), Ufoma Onowho (interface)

## Context

When a seat frees up, the next person on the waiting list is offered it. The brief says an offer
lasts up to 24 hours. A flat 24 hours breaks down near the start: an offer made three hours before
the event holds the seat for 24 hours, so nobody can use it, and it lapses after the event is over.

## Decision

An offer ends at the **earliest** of:

- 24 hours after it is made, or
- 1 hour before the event starts.

It always lasts **at least 30 minutes**, but **never past the start**.

| Event starts in | Offer lasts |
|---|---|
| 7 days | 24 hours |
| 18 hours | 17 hours |
| 3 hours | 2 hours |
| 80 minutes | 30 minutes |
| 40 minutes | 30 minutes |
| 10 minutes | 10 minutes (until the start) |

**After the start**, no new offers are made, and lapsed offers are marked expired without passing the
seat on.

The rule is one SQL expression in `SignupRepository.offer`, evaluated with the database clock in
the same transaction as the cancellation that freed the seat. It is configurable through
`fairseat.offers.window-hours` and `fairseat.offers.min-minutes`. `OfferWindowIT` covers each row
of the table.

## Alternatives rejected

1. **Flat 24 hours (the brief's starting point).** It is simple and identical for everyone, but
   near the start it locks the seat until after the event. That is the waste the brief warns about.
2. **Auto-confirm in the last hour.** Move the next person straight to GOING with no acceptance
   step. That fills the seat but books people who may no longer be able to come, and they learn
   about it only when they check.
3. **Open the seat to anyone in the last hour.** First come, first served for walk-ins. This drops
   the waiting list's fairness exactly when the seat is most wanted, and rewards people refreshing
   the page.
4. **Proportional window**, for example half the time remaining. It is fair in principle, but hard
   to explain on screen ("you have 47 minutes") and gives tiny windows to the third or fourth person
   in a chain.

## Cost accepted

- People near the front of the queue get **less time** to respond as the event gets closer. Someone
  offered a seat 40 minutes before the start who does not check FairSeat within 30 minutes loses
  it.
- Notices are in-platform only, so a short window depends on people opening the app. This cost is
  highest for attendees on poor connections.
- A seat freed in the final 30 minutes may go **unused** if the person offered it doesn't respond
  before the start.

## What would make us reverse it

If the organiser figures (accepted versus lapsed offers by hours before start) show that most offers
made in the last 3 hours **lapse unanswered**, the short window isn't working. We would then switch
the last hour to alternative 3 (open seat) and keep the waiting list for earlier offers.
