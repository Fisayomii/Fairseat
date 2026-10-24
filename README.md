# FairSeat service

Fair seating and waiting lists for free meetups. Side project, TeSA cohort 6 capstone, Team 4. Proposed by Ufoma Onowho.
This repository is the **service tier** (Java). The interface lives in its own repository: _fairseat-web link to add_.
All data is fictional.

| | Address |
| --- | --- |
| Interface | _to add after first deploy_ |
| Service | _to add after first deploy_ |

**The contract lives here and only here** (`contract/openapi.yaml`). The interface copies it with `npm run contract:sync`
and its CI fails if its copy differs from this repository's `main`. Contract changes: PR here, with a line in
`docs/decisions.md` and one review from each side.

## Run locally

Needs Java 25, Maven 3.9 and Docker.

```bash
docker run -d --name fairseat-db -p 5433:5432 \
  -e POSTGRES_DB=fairseat -e POSTGRES_USER=fairseat -e POSTGRES_PASSWORD=fairseat postgres:17-alpine
DATABASE_URL=jdbc:postgresql://localhost:5433/fairseat mvn spring-boot:run
mvn verify
```

Contract tooling: `cd contract && npm ci && npm run lint:contract && npm run mock` (mock on http://localhost:4011).

Host settings and pipeline are the same as BloodLink: `DATABASE_URL` (JDBC form), `DATABASE_USERNAME`, `DATABASE_PASSWORD`,
`ALLOWED_ORIGINS` (the interface's address), `PORT`; secret `API_DEPLOY_HOOK`, variable `API_URL`; host auto-deploy off.

## Demonstration accounts

_To add with the seed data._
