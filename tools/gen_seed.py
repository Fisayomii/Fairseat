"""Generates src/main/resources/db/changelog/changes/002-demo-seed.sql. Run: python tools/gen_seed.py
All people and events are fictional. Times are relative to when the migration runs."""
import pathlib

HASH = "$2a$10$esuCiGoleJo3lIkkMV6VL.tQrz6uj2TAhOpXA/41VzYaXTM3W7a/q"  # FairSeat-demo-2026
def uid(n): return f"00000000-0000-4000-8000-{n:012d}"
def eid(n): return f"00000000-0000-4000-9000-{n:012d}"

users = [(uid(1), "admin@fairseat.demo", "Demo Admin", "ADMIN"),
         (uid(101), "tolu.organiser@fairseat.demo", "Tolu Adebayo", "ORGANISER"),
         (uid(102), "chidi.organiser@fairseat.demo", "Chidi Okafor", "ORGANISER")]
people = ["Amaka Obi", "Bayo Akande", "Chioma Eze", "Dayo Ogunleye", "Emeka Nwosu", "Funke Bello",
          "Gbenga Afolabi", "Halima Musa", "Ifeanyi Okonkwo", "Jumoke Adeyemi", "Kunle Balogun", "Ngozi Umeh"]
A = {}
for i, name in enumerate(people):
    first = name.split()[0].lower()
    A[first] = uid(201 + i)
    users.append((uid(201 + i), f"{first}@fairseat.demo", name, "ATTENDEE"))

TOLU, CHIDI = uid(101), uid(102)
LONG = ("Building Resilient Payment-Adjacent Services for Nigerian Fintechs Without Losing Sleep: "
        "Idempotency, Retries, Reconciliation and the Long Tail of Edge Cases Nobody Warns You About")
assert len(LONG) <= 200, len(LONG)

# (n, title, category, location, starts_in_hours, seats, status, organiser, description,
#  signups: (attendee, status, created_hours_ago, offer_expires_in_hours or None))
G, W, O, X, C = "GOING", "WAITING", "OFFERED", "OFFER_EXPIRED", "CANCELLED"
events = [
 (1, "Lagos React Meetup", "TECH", "Yaba, Lagos", 72, 3, "SCHEDULED", TOLU,
  "Full, with a waiting list. Talks on React Server Components and testing.",
  [("amaka", G, 50, None), ("bayo", G, 49, None), ("chioma", G, 48, None), ("dayo", W, 30, None), ("emeka", W, 20, None)]),
 (2, "Product Managers' Breakfast", "PRODUCT", "Victoria Island, Lagos", 48, 2, "SCHEDULED", CHIDI,
  "An offer that expired and moved on: Gbenga's offer lapsed, Halima now holds a live offer, Ifeanyi waits.",
  [("funke", G, 70, None), ("gbenga", X, 60, -5), ("halima", O, 55, 19), ("ifeanyi", W, 40, None)]),
 (3, "Figma to Code Workshop", "DESIGN", "Ikeja, Lagos", 96, 2, "SCHEDULED", TOLU,
  "Kunle's offer lapsed 3 hours ago and nobody has touched the event. Reads show it expired; the sweeper passes the seat to Ngozi.",
  [("jumoke", G, 80, None), ("kunle", O, 75, -3), ("ngozi", W, 70, None)]),
 (4, "Women in Tech Lagos Mixer", "COMMUNITY", "Lekki, Lagos", 120, 30, "CANCELLED", CHIDI,
  "Cancelled by the organiser; everyone signed up was notified.",
  [("amaka", C, 90, None), ("bayo", C, 88, None)]),
 (5, "DevFest Recap Evening", "TECH", "Yaba, Lagos", -240, 40, "SCHEDULED", TOLU, "A past event.",
  [("amaka", G, 400, None), ("chioma", G, 390, None), ("emeka", G, 380, None)]),
 (6, "Career Clinic: First Tech Job", "CAREER", "Online", -72, 20, "SCHEDULED", CHIDI, "A past event.",
  [("bayo", G, 200, None), ("dayo", G, 190, None)]),
 (7, LONG, "TECH", "Wuse II, Abuja", 168, 50, "SCHEDULED", TOLU, "A title long enough to test the layout.",
  [("funke", G, 10, None)]),
 (8, "Design Critique Circle", "DESIGN", "Surulere, Lagos", 200, 12, "SCHEDULED", CHIDI,
  "Nobody has signed up yet.", []),
 (9, "Startup Legal Basics", "BUSINESS", "Ikoyi, Lagos", 18, 1, "SCHEDULED", TOLU,
  "Starts within a day, but Halima's 24-hour offer runs past the start: the open design problem.",
  [("gbenga", C, 30, None), ("halima", O, 25, 23)]),
 (10, "Data Science Study Group", "TECH", "Bodija, Ibadan", 60, 5, "SCHEDULED", CHIDI, "One seat left.",
  [("ifeanyi", G, 30, None), ("jumoke", G, 29, None), ("kunle", G, 28, None), ("ngozi", G, 27, None)]),
 (11, "Backend Engineers Lagos", "TECH", "Yaba, Lagos", 80, 4, "SCHEDULED", TOLU,
  "Amaka cancelled and then signed up again: My Events shows her once, as going.",
  [("amaka", C, 48, None), ("bayo", G, 40, None), ("amaka", G, 24, None)]),
 (12, "Community Cleanup Planning", "COMMUNITY", "Ajah, Lagos", 150, 15, "SCHEDULED", CHIDI, "",
  [("chioma", G, 12, None)]),
 (13, "UX Research Roundtable", "DESIGN", "Online", 30, 25, "SCHEDULED", TOLU, "Methods, recruiting and synthesis.",
  [("dayo", G, 20, None), ("emeka", G, 19, None)]),
 (14, "Founders' Fireside", "BUSINESS", "Victoria Island, Lagos", 250, 60, "SCHEDULED", CHIDI, "Early-stage founders in conversation.",
  [("gbenga", G, 15, None)]),
 (15, "Kotlin & Java User Group", "TECH", "Yaba, Lagos", 100, 35, "SCHEDULED", TOLU, "JVM talks and lightning demos.",
  [("halima", G, 14, None), ("ifeanyi", G, 13, None)]),
 (16, "Product Analytics 101", "PRODUCT", "Online", 130, 100, "SCHEDULED", CHIDI, "Events, funnels and retention.", []),
 (17, "Interview Prep Night", "CAREER", "Ikeja, Lagos", 180, 10, "SCHEDULED", TOLU, "Mock interviews in pairs.",
  [("jumoke", G, 9, None), ("kunle", G, 8, None), ("ngozi", G, 7, None)]),
 (18, "Cloud Native Abuja", "TECH", "Garki, Abuja", 220, 40, "SCHEDULED", CHIDI, "Containers, observability and cost.", []),
 (19, "Portfolio Review Session", "DESIGN", "Yaba, Lagos", 270, 8, "SCHEDULED", TOLU, "Bring two case studies.",
  [("amaka", G, 6, None)]),
 (20, "Small Business Bookkeeping Basics", "BUSINESS", "Surulere, Lagos", 300, 20, "SCHEDULED", CHIDI, "Records, receipts and cash flow.", []),
 (21, "Open Source Saturday", "COMMUNITY", "Yaba, Lagos", -480, 25, "SCHEDULED", TOLU, "A past event.",
  [("bayo", G, 600, None), ("chioma", G, 590, None)]),
 (22, "Mobile Dev Lagos", "TECH", "Lekki, Lagos", 320, 30, "SCHEDULED", CHIDI, "Flutter, React Native and native.",
  [("dayo", G, 5, None)]),
 (23, "Hackathon Kickoff", "TECH", "Yaba, Lagos", -120, 50, "CANCELLED", TOLU, "A past event that was cancelled.",
  [("emeka", C, 300, None)]),
]

def q(s): return "'" + s.replace("'", "''") + "'"
def at(h): return f"now() + interval '{h} hours'"
out = ["--liquibase formatted sql", "",
       "-- Demonstration data. All people and events are fictional. Generated by tools/gen_seed.py; edit that, not this.",
       "-- Every demo account's password is FairSeat-demo-2026.", "",
       "--changeset fisayomi:002-demo-seed"]
for u in users:
    out.append(f"INSERT INTO users (id, email, display_name, password_hash, role) VALUES ({q(u[0])}, {q(u[1])}, {q(u[2])}, {q(HASH)}, {q(u[3])});")
notices = []
for (n, title, cat, loc, start, seats, status, org, desc, sus) in events:
    taken = sum(1 for s in sus if s[1] in (G, O)) if status == "SCHEDULED" else 0
    assert taken <= seats, title
    out.append(f"INSERT INTO events (id, organiser_id, title, description, category, location, starts_at, ends_at, seats, seats_taken, status, created_at) "
               f"VALUES ({q(eid(n))}, {q(org)}, {q(title)}, {q(desc)}, {q(cat)}, {q(loc)}, {at(start)}, {at(start + 3)}, {seats}, {taken}, {q(status)}, now() - interval '30 days');")
    for (who, st, ago, exp) in sus:
        e = at(exp) if exp is not None else "NULL"
        out.append(f"INSERT INTO signups (event_id, user_id, status, offer_expires_at, created_at, updated_at) "
                   f"VALUES ({q(eid(n))}, {q(A[who])}, {q(st)}, {e}, now() - interval '{ago} hours', now() - interval '{max(ago - 1, 0)} hours');")
        if st == O:
            notices.append((A[who], eid(n), "SEAT_OFFERED", f"A seat opened at {title}. Accept it within 24 hours."))
        if st == X:
            notices.append((A[who], eid(n), "OFFER_EXPIRED", f"Your seat offer for {title} expired, so it went to the next person waiting."))
        if st == C and status == "CANCELLED":
            notices.append((A[who], eid(n), "EVENT_CANCELLED", f"{title} has been cancelled by the organiser."))
for (u, e, k, m) in notices:
    out.append(f"INSERT INTO notices (user_id, event_id, kind, message) VALUES ({q(u)}, {q(e)}, {q(k)}, {q(m)});")
ev_ids = ", ".join(q(eid(e[0])) for e in events)
us_ids = ", ".join(q(u[0]) for u in users)
out.append(f"--rollback DELETE FROM notices WHERE event_id IN ({ev_ids}) OR user_id IN ({us_ids});")
out.append(f"--rollback DELETE FROM signups WHERE event_id IN ({ev_ids}) OR user_id IN ({us_ids});")
out.append(f"--rollback DELETE FROM events WHERE id IN ({ev_ids});")
out.append(f"--rollback DELETE FROM users WHERE id IN ({us_ids});")
root = pathlib.Path(__file__).resolve().parent.parent
(root / "src/main/resources/db/changelog/changes/002-demo-seed.sql").write_text("\n".join(out) + "\n", encoding="utf-8")
print(f"{len(users)} users, {len(events)} events, {sum(len(e[9]) for e in events)} sign-ups, {len(notices)} notices")
