# secure-ticketing-api

Secure event ticketing and seat reservation API — Spring Boot 4, Java 25, PostgreSQL,
JWT authentication with role-based authorization.

## Setup

**Requirements:** JDK 25 and Docker. No local Maven — the wrapper is included.

```bash
git clone <repository-url>
cd secure-ticketing-api

./mvnw clean verify        # tests: in-memory H2, no Docker needed
./mvnw spring-boot:run     # http://localhost:8080
```

`spring-boot:run` starts the PostgreSQL and Redis containers in `compose.yaml` and wires
the application to them; Flyway then creates the schema. Nothing else to configure and
nothing to create by hand.

The containers use ports **5433** and **6380**, so an existing PostgreSQL or Redis on the
usual ports is untouched.

**Already have a database you would rather use?**

```bash
SPRING_DOCKER_COMPOSE_ENABLED=false DB_PORT=5432 REDIS_PORT=6379 ./mvnw spring-boot:run
```

Redis is optional in either case: the application runs without it, and
`SOLDOUT_CACHE_ENABLED=false` turns that dependency off entirely — see *Oversell
protection*.

### Something to try it with

```bash
SEED_DEMO=true ./mvnw spring-boot:run
```

Creates three accounts and a published event, so the API can be exercised immediately:

| Email | Password | Role |
|---|---|---|
| `admin@demo.local` | `demo-admin-pw` | ADMIN |
| `organizer@demo.local` | `demo-organizer-pw` | ORGANIZER |
| `customer@demo.local` | `demo-customer-pw` | CUSTOMER |

Plus **“Demo Concert”**, published with 100 seats — sign in as the customer and reserve
without setting anything up first.

Off by default, and running it twice changes nothing that already exists. These
passwords are in a public file: the flag belongs in development only. For a real
deployment see *Creating accounts*.

## Configuration

Every value has a development default, so the app starts with no configuration.
Override via environment variables or a `.env` file (git-ignored).

The database and Redis rows only apply when Compose is switched off: with it on, Boot
takes those settings from the running containers and the defaults below are ignored.

| Variable | Default | Notes |
|---|---|---|
| `JWT_SECRET` | dev-only placeholder | **Set this in production.** HS256 needs ≥ 32 bytes |
| `SEED_DEMO` | `false` | `true` creates the demo accounts above. Development only |
| `SPRING_DOCKER_COMPOSE_ENABLED` | `true` | `false` to point at your own database instead |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `ticketing` | Used when Compose is off |
| `DB_USER` / `DB_PASSWORD` | `ticketing` / `ticketing` | |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Used when Compose is off |
| `SOLDOUT_CACHE_ENABLED` | `true` | `false` disables Redis completely |
| `RESERVATION_HOLD_TTL` | `15m` | How long an unconfirmed reservation keeps its seats |
| `RESERVATION_SWEEP_INTERVAL` | `60s` | How often expired holds are reclaimed |
| `RESERVATION_SWEEP_BATCH_SIZE` | `200` | Max holds reclaimed per run |
| `RESERVATION_MAX_SEATS` | `50` | Most seats one request may ask for |
| `IDEMPOTENCY_RETENTION` | `24h` | How long a used `Idempotency-Key` is remembered |
| `IDEMPOTENCY_LEASE` | `30s` | How long one request may hold a key before it is presumed dead |
| `IDEMPOTENCY_SWEEP_INTERVAL` | `5m` | How often expired keys are deleted |
| `IDEMPOTENCY_SWEEP_BATCH_SIZE` | `500` | Max keys deleted per run |
| `RATELIMIT_ENABLED` | `true` | `false` turns throttling off, for edge-limited deployments |
| `RATELIMIT_SHARED` | `true` | `false` counts per instance only and never contacts Redis |
| `RATELIMIT_INSTANCES` | `1` | How many instances share the budget, used to size the fallback |
| `RATELIMIT_AUTH_CAPACITY` / `_WINDOW` | `10` / `1m` | Sign-in attempts per client address |
| `RATELIMIT_RESERVATION_CAPACITY` / `_WINDOW` | `30` / `1m` | Reservations per user |
| `RATELIMIT_MAX_TRACKED_KEYS` | `50000` | Ceiling on locally remembered callers |
| `CIRCUIT_BREAKER_FAILURE_THRESHOLD` | `3` | Consecutive Redis failures before calls are skipped |
| `CIRCUIT_BREAKER_OPEN_DURATION` | `30s` | How long they are skipped for |

Token lifetimes: access 15 min, refresh 7 days.

## Creating accounts

Registration always grants `CUSTOMER` — deliberately, so nobody can hand themselves
elevated roles. Nothing privileged is created automatically either: a service that ships
with a known ADMIN password installs that password everywhere it is deployed. The demo
accounts above are a development fixture behind `SEED_DEMO`, off unless asked for.

So the first admin is created on purpose, from a shell, and every account after that is
created by an admin through the API.

### 1. The first admin

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
  --create-admin --email=admin@example.com --spring.main.web-application-type=none"

# Password:
# Repeat:
# Created ADMIN account 1 <admin@example.com>
```

The password is prompted for, not passed as an argument, so it does not end up in your
shell history. Without a terminal — a pipeline, some IDEs — `--password=…` is accepted
instead, with a warning saying exactly that.

Running it twice changes nothing: an existing address is reported and left alone. The
command exits when it is done; no server is left running.

### 2. Everyone else

```bash
curl -X POST localhost:8080/api/admin/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"email":"organizer@example.com","password":"…","roles":["ORGANIZER"]}'
# 201 {"id":2,"email":"organizer@example.com","roles":["ORGANIZER"],…}
```

Both the bootstrap and every admin-created account appear in `audit_logs`, the latter
recorded against the admin who made the call.

**Known gap:** changing the roles of an account that already exists still needs SQL —
the endpoint creates, it does not modify. Nothing else in the system requires touching
the database by hand.

## Auth flow

```bash
# 1. register (always CUSTOMER)
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"secret123"}'

# 2. login -> access + refresh token
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"secret123"}'

# 3. call a protected endpoint
curl localhost:8080/api/auth/me -H "Authorization: Bearer $ACCESS_TOKEN"

# 4. refresh when the access token expires
curl -X POST localhost:8080/api/auth/refresh -H 'Content-Type: application/json' \
  -d '{"refreshToken":"..."}'
```

A token is signed, not encrypted — its payload is readable by anyone holding it:

```bash
echo "$ACCESS_TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d
# {"sub":"3","exp":...,"type":"access","email":"...","roles":["ORGANIZER"]}
```

The `type` claim separates access from refresh tokens, so a long-lived refresh token
cannot be replayed as an access token.

## Endpoints

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/auth/register` `/login` `/refresh` | public — rate limited per client |
| `GET` | `/api/auth/me` | authenticated |
| `POST` | `/api/events` | ORGANIZER, ADMIN |
| `PUT` | `/api/events/{id}` | owner or ADMIN |
| `POST` | `/api/events/{id}/publish` | owner or ADMIN |
| `GET` | `/api/events?ownerId=&page=&size=` | authenticated |
| `GET` | `/api/events/public?from=&to=&q=` | **public** |
| `POST` | `/api/events/{id}/reservations` | authenticated † — requires `Idempotency-Key`, rate limited per user |
| `POST` | `/api/reservations/{id}/confirm` `/cancel` | owner or ADMIN |
| `POST` | `/api/admin/users` | ADMIN |
| `GET` | `/actuator/health` `/info` | **public** (health details need ADMIN) |
| `GET` | `/actuator/metrics` | ADMIN |
| `GET` | `/v3/api-docs`, `/swagger-ui/**` | authenticated |

† Reservation endpoints carry no *role* restriction, unlike event management. This is
intentional: there is no reason an organizer should be barred from buying a ticket to
someone else's event, and restricting the action to `CUSTOMER` would make the role a
category rather than a permission. Authorization here is by **ownership** —
`confirm`/`cancel` reject anyone who is not the reservation's owner (or an ADMIN) with
a 403. See ADR-3.

Every failure returns the same body:

```json
{
  "timestamp": "2026-07-25T11:35:23Z",
  "status": 409,
  "code": "INSUFFICIENT_CAPACITY",
  "message": "Only 2 seat(s) remain for this event.",
  "path": "/api/events/4/reservations"
}
```

Validation failures add an `errors` array naming each rejected field. `code` is
stable and meant to be branched on; `message` is for humans.

## Reserving safely

Creating a reservation requires an `Idempotency-Key`. Generate a UUID per intent — not
per attempt — and send the same one on every retry:

```bash
KEY=$(uuidgen)

curl -X POST localhost:8080/api/events/4/reservations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"seats":2}'
# 201 {"id":42,"status":"PENDING","seats":2,...}

# the same call again — no second reservation, no second set of seats
curl -i -X POST localhost:8080/api/events/4/reservations \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"seats":2}'
# 201, X-Idempotent-Replay: true, same id 42
```

| Situation | Response |
|---|---|
| Header missing | `400 IDEMPOTENCY_KEY_REQUIRED` |
| Header blank or over 100 characters | `400 IDEMPOTENCY_KEY_INVALID` |
| Same key, different body or different event | `422 IDEMPOTENCY_KEY_REUSED` |
| Same key while the first call is still running | `409 IDEMPOTENCY_REQUEST_IN_PROGRESS` |
| Same key, same request, already finished | the reservation, plus `X-Idempotent-Replay: true` |

Keys are scoped per user, so two customers may pick the same value without
interfering. A key is remembered for `IDEMPOTENCY_RETENTION`; after that it is free
again. See ADR-4 for why a replay is not a byte copy of the first response.

## Rate limits

Sign-in is limited per client address, reserving per user. Over the limit:

```bash
curl -i -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"user@example.com","password":"wrong"}'

# HTTP/1.1 429
# Retry-After: 20
# X-RateLimit-Limit: 10
# X-RateLimit-Remaining: 0
# X-RateLimit-Reset: 20
# RateLimit: "auth";r=0;t=20
#
# {"status":429,"code":"RATE_LIMIT_EXCEEDED","message":"Too many requests. ..."}
```

`register`, `login` and `refresh` share one budget, so rotating between them gains
nothing. Every answer carries the headers, not just refusals, so a client can slow down
before it is told to. See ADR-5.

## Who did what

Sign-ins, publishes, reservations and cancellations are recorded in `audit_logs` with
the actor, their address and their client:

```sql
SELECT action, actor_id, resource_id, ip, created_at
FROM audit_logs ORDER BY created_at DESC LIMIT 5;

--        action         | actor_id | resource_id |      ip      |        created_at
-- RESERVATION_CANCELLED |       22 |          48 | 203.0.113.7  | 2026-07-26 09:14:02+00
-- RESERVATION_CREATED   |       22 |          48 | 203.0.113.7  | 2026-07-26 09:13:58+00
-- LOGIN_FAILED          |     NULL |        NULL | 198.51.100.4 | 2026-07-26 09:12:31+00
```

A `LOGIN_FAILED` with no actor is an attempt against an address that was never
registered; one with an actor is a wrong password on a real account.

| Action | Resource | Recorded by |
|---|---|---|
| `REGISTERED` | the new account | explicit call — the actor is the account being created |
| `LOGIN_SUCCEEDED` | the account | explicit call — nobody is authenticated yet |
| `LOGIN_FAILED` | the targeted account, or nothing | explicit call, in its own transaction |
| `EVENT_PUBLISHED` | the event | `@Audited` |
| `RESERVATION_CREATED` | the reservation | `@Audited` |
| `RESERVATION_CONFIRMED` | the reservation | `@Audited` |
| `RESERVATION_CANCELLED` | the reservation | `@Audited` |

The three sign-in actions cannot use the annotation: the aspect reads the actor from
the security context, and on those paths there is nobody in it yet. See ADR-6.

## Looking at a running instance

```bash
curl localhost:8080/actuator/health
# {"status":"UP","groups":["liveness","readiness"]}   <- anyone

curl localhost:8080/actuator/health -H "Authorization: Bearer $ADMIN_TOKEN"
# … "components":{"db":{"status":"UP"},"redis":{"status":"UP"},…}   <- ADMIN only
```

`health` and `info` answer anyone, because a load balancer cannot present a token.
*Which* dependency is down is another matter, and needs ADMIN. `metrics` needs ADMIN
outright. `env`, `heapdump`, `threaddump`, `beans` and `configprops` are **404** —
not published at all rather than merely guarded. See ADR-7.

**API description:** `http://localhost:8080/swagger-ui/index.html`. It needs a token
like everything else — press *Authorize* and paste one from `/api/auth/login`. The raw
schema is at `/v3/api-docs`.

**Coverage:** `./mvnw verify` writes `target/site/jacoco/index.html`. Currently
**93.1% of lines** and **78.0% of branches**. Two
deliberate holes remain: the interactive password prompt has no terminal to read from
under Maven, and the Redis-backed rate limit counter is built in the suite but never
reaches a live Redis, so only its failure path is exercised. Both are covered by the
manual verification described in ADR-5 and ADR-7 rather than excluded from the
measurement to flatter it.

## Filter chain

Three slices have added a filter, and the order between them is load-bearing:

```
request
  │
  ├─ 1. JwtAuthenticationFilter   resolves the caller (HMAC, cheap)
  ├─ 2. RateLimitFilter           counts; a 429 returns from here
  ├─ 3. AuthorizationFilter       401 / 403
  ├─ 4. IdempotencyFilter         catches repeats; a replay returns from here
  └─ 5. Controller -> Service     BCrypt and business logic run for the first time
```

- **Rate limit after authentication** so a per-user limit can find its user, **but
  before authorization** so requests destined for a 401 are counted too — otherwise an
  attacker gets unlimited attempts as long as the token is invalid.
- **Idempotency after authorization** so a request that is about to be refused does not
  consume the caller's key.
- **Both before the controller**, which is what keeps BCrypt and the seat counter behind
  them. `RateLimitOrderingTest` proves a throttled request leaves no reservation, no
  idempotency record and no audit entry.

---

# Architecture decisions

## ADR-1: Oversell protection

**Problem.** Two requests must never sell the same last seat, and the protection has
to hold under heavy traffic without exhausting the database.

### Options considered

Assume capacity 100, the last 10 seats, and 5.000 concurrent requests.

**Optimistic locking with retry** — rejected. Contention is at its maximum exactly
when it matters. One request of 5.000 wins; the rest hit a version conflict and
retry, and the retries collide again. Every failed attempt has already paid for a
capacity read, an insert and a rollback, so this puts *more* load on the database
than locking does. Some requests exhaust their retry budget and fail, so the outcome
is not even fair.

**Pessimistic locking (`SELECT ... FOR UPDATE`)** — rejected. Correct, but it
serialises every request for the same event. Lock held ≈ read + insert + commit
≈ 1-3 ms, giving roughly 300-1.000 reservations/second per event. The real hazard is
not the lock but the connection pool: thousands of requests holding connections while
they wait will drain it, and unrelated endpoints start timing out too.

**Redis as the source of truth** — rejected. Fast (100k+/s) but if the Redis counter
increments and the database insert then fails, those seats are simply lost. Closing
that gap needs an outbox or compensation mechanism. Postgres already does ~10k
single-row updates per second, so the bottleneck Redis solves does not exist yet.

### Decision

**An atomic conditional UPDATE in the database, with Redis as a fast-reject cache in
front of it.**

```sql
UPDATE events SET reserved_seats = reserved_seats + :seats
WHERE id = :id
  AND published = true
  AND reserved_seats + :seats <= capacity
```

One row affected means the seats are claimed; zero means they are not. The entire
decision lives inside a single statement, so there is no read-then-write window and
therefore nothing to lock: roughly 2.000-10.000 reservations/second per event, about
ten times the pessimistic figure. Two callers cannot both take the last seat because
the database evaluates the condition while holding the row.

`published = true` sits in the same condition on purpose — checking it separately
would leave a window where an event is unpublished between check and claim.

**Defence in depth.** A CHECK constraint enforces
`reserved_seats BETWEEN 0 AND capacity`, so even a bypassed application cannot leave
an oversold row. Verified by hand: a direct `UPDATE` past capacity is rejected by
PostgreSQL.

**Trade-off accepted.** `reserved_seats` is a denormalised counter, so it could in
principle drift from the reservations it summarises. That risk is bound to a test
rather than to discipline: the concurrency suite asserts
`reserved_seats == SUM(seats WHERE status <> 'CANCELLED')` after every scenario,
including mixed reserve/cancel traffic.

### `@Version` is present, but oversell protection does not rely on it

`Event` carries a `@Version` field and `EventOptimisticLockingTest` proves it fires a
real `ObjectOptimisticLockingFailureException`. It would be easy to read that as the
oversell mechanism. It is not:

| Concern | Protected by |
|---|---|
| Two organizers editing the same event | `@Version` (optimistic locking) |
| Two customers claiming the same last seat | the atomic conditional `UPDATE` |

The two are independent by construction. A `@Modifying` bulk update does not load the
entity and therefore **does not bump the version column** — so the seat claim neither
benefits from optimistic locking nor can be defeated by a version conflict. Its
correctness comes entirely from the `WHERE` clause being evaluated by the database
while it holds the row.

This is deliberate. Routing seat claims through the versioned entity would reintroduce
exactly the read-modify-write contention rejected above.

### Redis's role, and what happens when it fails

Redis caches one thing: *this event is full*. A request for a known-full event is
rejected without touching the database. It is a **negative cache only** — "there is
room" is never cached, because that answer belongs to the database. A stale marker
can therefore only cause an unnecessary rejection, never an oversell, and
cancellation clears it.

**Invalidation is the hard part.** The marker has to disappear whenever it stops
being true, and there are more such moments than the obvious one:

| Event | Marker |
|---|---|
| Event fills up | written |
| A reservation is cancelled | cleared |
| A request asks for more seats than remain (but seats remain) | **not written** |
| Capacity is raised | **cleared** |

The last two were missed initially. Writing the marker for an oversized request was
also a denial-of-service: a single "1000 seats" request locked everyone out for the
whole TTL. A raised capacity left a full event unbookable while seats sat free. The
capacity case is handled with a domain event, so the event slice stays unaware of
caches, and it fires `AFTER_COMMIT` so a rolled-back update cannot clear the marker.

Correctness never depends on Redis. Availability must not either, which takes three
layers:

1. **Short command timeout (100 ms).** Lettuce defaults to 60 seconds — leaving that
   in place would turn a Redis outage into an application outage. The *connect*
   timeout is deliberately larger (500 ms): establishing the first connection does
   not fit in 100 ms, and sharing the budget silently lost the first cache write
   after startup.
2. **Circuit breaker.** After a few consecutive failures (`CIRCUIT_BREAKER_FAILURE_THRESHOLD`,
   default 3) the cache is skipped entirely
   for 30 seconds, so a sustained outage costs nothing per request instead of a
   timeout every time. Hand-written rather than pulled from Resilience4j: that
   library is not managed by the Spring Boot BOM, its current starters target Boot 3,
   and there is exactly one call site to protect.
3. **Swallowed failures.** Any cache error is logged and treated as "unknown", so the
   request continues against the database.

Verified by stopping Redis mid-traffic: reservations kept succeeding, and the log
recorded exactly three warnings before going quiet — the breaker opening.

## ADR-2: Reservation state machine

Valid transitions:

```
PENDING ──confirm──> CONFIRMED
   │                      │
 cancel                cancel
   │                      │
   v                      v
CANCELLED <───────────────┘        (terminal)
```

**Where the rules live.** The whole table is declared once, on the
`ReservationStatus` enum itself:

```java
PENDING,   EnumSet.of(CONFIRMED, CANCELLED)
CONFIRMED, EnumSet.of(CANCELLED)
CANCELLED, EnumSet.noneOf(ReservationStatus.class)   // terminal
```

`Reservation` then has a single gate — `transitionTo(target)` — that asks the enum
whether the move is legal and throws `INVALID_STATE_TRANSITION` (409) if not. Both
`confirm()` and `cancel()` go through it.

The alternative was an `if` inside each entity method, which is where this started. The
same information ended up split across two places ("must be PENDING" in `confirm()`,
"must not be CANCELLED" in `cancel()`), and adding a fourth state would have meant
auditing every method to find the rules. One table also means the rules are testable
without an entity or a database.

`CANCELLED → CONFIRMED` is therefore not merely forbidden, it is **unreachable**:
`CANCELLED` maps to an empty set, so no code path can resurrect a cancelled
reservation. Repeating a transition returns 409 too — a state is never a legal target
of itself.

A confirmed reservation can still be cancelled — a ticket stays refundable — and the
seats genuinely return to the pool.

**Capacity follows from one predicate.** `holdsSeats()` is also on the enum: `PENDING`
and `CONFIRMED` occupy capacity, `CANCELLED` releases it. This is the detail that is
easy to get wrong — counting only `CONFIRMED` reservations would let pending holds
oversell the event, because their seats are already claimed in `reserved_seats`.
Confirming does not touch capacity; only cancelling releases it.

### Holds expire

> **Beyond the core flow.** Added because a reservation holds seats from the moment it
> is created, so an abandoned checkout would otherwise remove capacity permanently.

Every serious inventory system bounds the hold, and so does this one: a `PENDING`
reservation carries an `expires_at`, and a scheduled sweeper cancels lapsed holds and
returns their seats.

The duration is **configurable** (`RESERVATION_HOLD_TTL`, default 15 minutes) because
it is a business decision rather than a constant — card checkout needs longer than a
free registration, and a high-demand event may want it shorter to keep seats moving.

Two details worth noting:

- The sweeper flips the row with a conditional update (`... WHERE id = ? AND status =
  'PENDING'`) **before** returning seats. Only the caller that matches the row
  releases them, so running the sweeper on several instances cannot release the same
  hold twice and push the counter below the truth.
- Confirming a reservation clears `expires_at`. A confirmed booking is no longer a
  hold and must never be swept.

## ADR-3: Where authorization lives

Role checks are declarative on the controller (`@PreAuthorize`), because they are a
property of the endpoint. Ownership checks live in the service, because they are a
business rule: they need a specific error message and are directly unit-testable.

A SpEL expression like `@eventService.isOwner(#id, principal)` was rejected — it has
no compile-time checking, no IDE support, and can only be tested indirectly.

Note that a 403 has two distinct sources: the filter chain's authorization rules, and
method security firing inside the controller invocation. The first is handled by an
`AccessDeniedHandler`, the second by the `@RestControllerAdvice` — both render the
same `ApiError` body.

## ADR-4: Idempotency

**Problem.** Oversell protection stops two people taking the same seat. It does nothing
about *one* person's request arriving twice — a network timeout, a double-click, a client
retry. Both copies are legitimate on their own, so both get seats.

### Why the database, not Redis

The decision is the same as in ADR-1 and for the same reason. A duplicate has to be
detected exactly once under concurrency, and PostgreSQL already has the primitive for
that: a unique constraint. Redis could answer faster, but if it says "new" when the
database already recorded the request, a second reservation is created — the failure mode
is precisely the one being prevented.

### What is stored: no response body

The record is `key, endpoint, requestHash, responseHash, status, createdAt, ttl`. There
is no field for a response body, and that shape is kept deliberately:

```
idempotency_keys
  idempotency_key  user_id  endpoint       -> who asked for what
  request_hash                             -> which request this key was used with
  status                                   -> IN_PROGRESS | COMPLETED
  resource_id                              -> what the request produced
  response_hash                            -> digest of the original reply
  created_at  locked_until  expires_at     -> claim lease and retention
```

A hash cannot be turned back into a response, so replaying from `response_hash` alone is
impossible. Instead the record keeps the **id of the resource**, and a replay re-renders
it. `response_hash` still earns its place: it is compared against the freshly rendered
body to tell whether the resource has moved on since.

**Consequence, deliberately accepted: a replay returns the resource as it stands now, not
a copy of the first reply.** Confirm a reservation and replay its key, and the reply says
`CONFIRMED` — the first one said `PENDING`. This is the honest answer for a resource that
can change, and it cannot serve stale data, but it does mean a replay is not a byte copy
of the first reply. A hash mismatch at replay time is
therefore **logged, not treated as an error** — turning it into a failure would break
every replay that follows a state change. `IdempotencyReplaySemanticsTest` pins the
behaviour so it cannot drift.

### Keys are scoped per user

The obvious constraint is `UNIQUE (key, endpoint)`. That is a cross-tenant leak: if two
customers pick the same key on the same endpoint, the second one is handed the first
one's reservation. The constraint is therefore `UNIQUE (user_id, idempotency_key,
endpoint)` — the scope Stripe and brandur.org both use. Each user gets an independent
key namespace, and no key can expose another user's resource.

`endpoint` holds the route pattern (`POST /api/events/{eventId}/reservations`), not the
concrete URI, and the path variables go into `request_hash` instead. So reusing a key
against a *different* event is a mismatch rather than a fresh operation.

**The hash is computed by the server**, over a canonicalised form of the body. A
client-supplied digest would be worthless — a client that changed the payload could just
send the old one. Canonicalisation matters too: raw byte equality would reject
`{"seats": 2}` as a mismatch for `{"seats":2}`, failing an honest retry.

### Two phases, and why they commit separately

```
1. INSERT the key as IN_PROGRESS   -> own transaction, commits immediately
2. run the request                 -> own transaction
3. UPDATE the key to COMPLETED     -> own transaction
```

Step 1 has to be visible to other requests *before* step 2 finishes, otherwise a
concurrent duplicate would not see it. That is what turns the unique constraint into a
race exactly one caller wins; the losers get `409` **immediately**, without waiting.

Doing all three in one transaction would remove the crash window below, but a duplicate
would then block on the unique index while holding a connection — the pessimistic-locking
cost ADR-1 rejected. Measured: 30 simultaneous requests on one key completed in 201 ms
total, 26 of them rejected without queuing behind anything.

A losing caller finding out through `DataIntegrityViolationException` also forces the
split: that exception marks its transaction rollback-only, so the winning row has to be
read in a fresh one.

**Residual risk, stated plainly.** If the process dies after step 2 commits but before
step 3, the row is stuck `IN_PROGRESS`: retries get `409` until `locked_until` lapses,
after which the next caller takes the key over and does the work again. The reservation
from the first attempt is then duplicated. The lease bounds how long this can go
unnoticed; closing it entirely would mean reconciling against the resource on takeover,
which is not implemented.

### Only success is remembered

| Outcome | Record |
|---|---|
| `2xx` | kept as `COMPLETED` with the resource id |
| `4xx` | **deleted** |
| `5xx` | **deleted** |

A rejected request left no side effect behind, and the condition that caused it may well
have passed — seats free up. Keeping the failure would lock the client out of a key that
never accomplished anything. Some implementations (Stripe among them) replay stored
errors; here the record's absence is what makes the retry possible, and it is what makes
the key immediately reusable.

### No native upsert

`INSERT ... ON CONFLICT DO UPDATE` would express the claim in one statement, but H2 does
not support it and the suite runs on H2. A PostgreSQL-only query has slipped past these
tests before (see *Known gap* below), so the claim uses a plain `save()` and catches the
constraint violation. Portable, and the atomicity still comes from the constraint itself.

The unique constraint is declared **twice** — in the migration and on the entity — for
the same reason: tests build their schema from the mapping, and a constraint that exists
only in Flyway would be absent exactly where the race is tested. That divergence was real
and the tests caught it.

### Bound to the endpoint, not to reservations

The filter guards whatever endpoints have a `ReplayRenderer`, and that interface is what
the reservation slice implements. Reservations depend on idempotency; idempotency knows
nothing about reservations. Adding a second idempotent endpoint means adding a renderer,
not touching the filter.

Confirm and cancel deliberately have no idempotency: the state machine already answers a
repeated call with `409`, so a retry cannot double anything. (They are audited, though —
that is a separate concern; see ADR-6.)

## ADR-5: Rate limiting

**Problem.** `POST /api/auth/login` runs BCrypt, which is slow by design. An attacker
does not have to guess a single password to hurt the service — they only have to keep
asking, and the CPU bill is ours. Unlimited attempts are also unlimited guesses.

### The library, but not its starter

`bucket4j-spring-boot-starter` 0.12.10 has `spring-boot-starter-parent:3.4.5` as its
parent, so adopting it would drop Boot 3 auto-configuration into a Boot 4 application —
the reason Resilience4j was left out in ADR-1. `bucket4j_jdk17-core` 8.14.0 has no
dependencies at all (its POM declares none), so the library is used directly and wired
by hand.

Unlike the circuit breaker, this was not written from scratch. A token bucket's refill
arithmetic is real work with real edge cases, and there is a maintained,
framework-agnostic implementation of it.

### What happens when Redis is unreachable

This is the decision that matters, and the two obvious answers are both wrong:

| Choice | Why not |
|---|---|
| **Fail open** — allow everything | Removes the protection exactly when load is highest. If Redis fell over *because* of that load, the limiter has made things worse. |
| **Fail closed** — refuse everything | The limiter becomes a denial of service against our own users. |

So neither. The shared counter is primary; when it is unreachable each instance falls
back to its own counter, sized at `capacity / RATELIMIT_INSTANCES`. The total stops
being exact but stays bounded, and nothing is switched off. The existing
`RedisCircuitBreaker` does the detection, so after `CIRCUIT_BREAKER_FAILURE_THRESHOLD`
failures (default 3) Redis is not called at all for `CIRCUIT_BREAKER_OPEN_DURATION`
(default 30s) rather than timing out on every request. Both are settings rather than
constants: how many failures mean "really down" and how long to stay away are
operational judgements, and a noisy network makes three too twitchy.

Verified by stopping Redis mid-traffic: requests kept being served **and kept being
limited** — the third one still got a 429 — and the log recorded exactly as many warnings
as the configured threshold
before going quiet.

**The one case the fallback could not reach.** Bucket4j's Lettuce builder opens its
connection while it builds, so constructing the shared counter at startup made an
unreachable Redis a reason for the application not to start at all — the fallback above
never got the chance to run, and a Redis blip during a rolling restart would have taken
the service down instead of degrading it. Found by starting the CLI on a busy machine,
where the first handshake exceeded the 100 ms command timeout. The connection is now
opened on first use, which turns that failure back into a failed *call* — the case that
already had an answer. `RateLimitStartsWithoutRedisTest` pins it: shared counting on,
Redis pointed at a dead port, application starts, limit still enforced.

### Where the filter sits

```
1. JwtAuthenticationFilter   authenticate
2. RateLimitFilter           <- here
3. AuthorizationFilter       authorize
4. IdempotencyFilter         de-duplicate
```

After authentication, because a per-user limit needs to know the user. Before
authorization, because requests that are about to be refused have to count too —
otherwise an attacker gets unlimited attempts as long as the token is invalid. Both are
before the controller, which is what keeps BCrypt behind the limit.

### Keys, and not trusting the client about them

Sign-in is keyed on the client address, because nobody is authenticated yet. Reserving
is keyed on the user, because keying it on the address would punish everyone in an
office for one colleague's enthusiasm.

**`X-Forwarded-For` is not read.** It is a header the caller controls: an attacker could
present a new address on every request and walk straight past the limit. Deployments
behind a proxy set `server.forward-headers-strategy`, which lets Spring populate the
remote address from those headers where it is actually safe. Secure by default,
loosened deliberately.

The local counter map is bounded (`RATELIMIT_MAX_TRACKED_KEYS`) for the same class of
reason: keyed by caller, it would otherwise be an attacker-controlled allocation.
Entries back at full budget are dropped — a caller with a full bucket is
indistinguishable from one never seen, so forgetting them costs nothing.

### Response contract

`429` with the usual `ApiError` body, plus `Retry-After` (RFC 6585), the widely
implemented `X-RateLimit-*` headers, and the `RateLimit` field from
draft-ietf-httpapi-ratelimit-headers. The draft is not final, so both spellings are
sent — the same approach taken with the idempotency draft in ADR-4. Headers go on every
answer, not only refusals, so a client can slow down before it is stopped.

## ADR-6: Audit log

**Problem.** Nothing recorded who cancelled a reservation, who published an event, or
who has been failing to sign in.

### Written inside the business transaction

The three options differ in what they guarantee:

| Approach | Guarantee |
|---|---|
| Same transaction | Record and action commit together. **A gap is impossible.** Costs one insert per action. |
| After commit | Cannot break the action — but "all transactional guarantees are gone once `afterCommit()` runs", so a crash there loses the record silently. |
| Asynchronous | No latency cost; the queue is lost on a crash and ordering is not guaranteed. |

For a security record the gap is the thing that must not happen, so `AuditRecorder`
joins the caller's transaction. Rolled-back work leaves no trace and every trace
corresponds to work that happened. The cost — an extra insert on the reservation path,
which ADR-1 went to some trouble to keep fast — is accepted deliberately.

Verified: a reservation refused for lack of capacity returns 409 and writes **no** audit
row, while the successful ones do.

**This means an audit failure fails the business action**, and that direction was chosen
rather than fallen into. The alternative — catch, log, carry on — trades a visible
failure for an invisible one: the reservation succeeds and nobody ever learns it went
unrecorded. Since the trail lives in the same database as the reservation, an insert
that cannot be written is not a flaky audit subsystem, it is a database that cannot take
writes, and the reservation was not going to commit either. The choice costs nothing in
the realistic case and buys the guarantee that the table is complete.

### Failed sign-in is the one exception

A rejected sign-in throws, so its transaction rolls back — and it is the single most
useful row in the table. That one write commits on its own (`REQUIRES_NEW`), which is
the only place in the system where a record is meant to outlive the work it describes.

Making it useful needed a small change to `AuthService.login`: "no such account" and
"wrong password" used to be one branch, and are now two, so the trail can say which
account was targeted. **The response is byte-identical either way** — same code, same
message — because an endpoint that distinguishes them is an oracle for which addresses
are registered. A test pins that.

So `LOGIN_FAILED` with an actor means a wrong password on a real account; without one,
an attempt against an address that was never registered. Both are worth seeing.

**A `detail` column for the attempted address was considered and left out.** When the
account exists, `actor_id` already says which one was targeted, so the column would be
redundant. When it does not, the only thing to store is whatever string the caller
typed — unvalidated input, in a column of its own, in a table meant to be evidence.
For spotting brute force, `actor_id` plus `ip` plus time is enough:
repeated failures from one address are visible whether or not the addresses tried were
real.

### `@Audited`, but without SpEL

```java
@Audited(action = AuditAction.RESERVATION_CREATED, resource = AuditResource.RESERVATION)
public Reservation create(Long eventId, Long userId, int seats) { ... }
```

The obvious way to get the resource id is a SpEL expression — `resourceId = "#result.id"`.
ADR-3 rejected SpEL for authorization because it has no compile-time checking and fails
quietly when wrong, and recording the wrong resource is no better than authorizing the
wrong caller. Instead the return value says what it is:

```java
public interface AuditableResource {
    Long auditId();
}
```

Both attributes are enums for the same reason. A typo is a compile error, not a row
nobody notices.

The aspect calls `proceed()` **first**: if the action throws, the exception propagates
and nothing is recorded. Actor, address and user agent come from the security context
and the thread-bound request, so no plumbing is threaded through the service signatures.

### Schema decisions

- **No foreign key on `actor_id`**, though every other table has one. The trail has to
  outlive the account it describes; a foreign key would make deleting a user either
  impossible or destructive of the evidence.
- **`actor_id` is nullable** — an attempt against an unregistered address has no actor.
- **No CHECK constraint.** The ones in ADR-1 and ADR-4 protect real invariants; there is
  no invariant here to protect, and a decorative constraint is noise.
- **Append-only entity**: constructor and getters, nothing else. A record that can be
  edited is not evidence. Enforced by a reflection test, because it is exactly the rule
  a later innocent-looking setter would break.
- **No sweeper.** Holds and idempotency keys expire because keeping them is pointless;
  deleting an audit trail is a policy decision, not routine maintenance. Retention is
  out of scope here.

## ADR-7: Observability, and how accounts come into existence

### Nothing is seeded unless it is asked for

The obvious way to make a fresh install usable is a migration that inserts an admin with
a known password. It is also how services end up deployed to production
with an admin account whose password is in a public repository — the migration does not
know which environment it is running in, and by the time anyone notices, the account has
been there since the first deploy.

So the fixture is opt-in rather than automatic, and the real paths do not depend on it:

| Account | How |
|---|---|
| The first admin | `--create-admin` from a shell, password prompted |
| Everyone else | `POST /api/admin/users`, by that admin |
| Customers | Ordinary registration, always `CUSTOMER` |
| Demo accounts | `SEED_DEMO=true`, development only — off by default |

`SEED_DEMO` is a property, not a migration, so it cannot travel with the schema into an
environment nobody meant to seed. `DemoSeedIsOffByDefaultTest` pins that default: with
the flag unset the seeder is not even a bean, and a fresh application ends with no
accounts at all. It exists so a seat can be reserved within a minute of cloning;
`--create-admin` remains the only way an account appears in a real deployment.

The command is triggered by an **argument, not a profile**. A profile can be active
during an ordinary start — and then the command runs when nobody meant it to. Passing
`--create-admin` cannot happen by accident.

The password is prompted for rather than taken as an argument, which is the same reason
`createsuperuser` and `passwd` prompt: an argument is visible in the shell history and
in the process list. Where there is no terminal the argument is accepted, but the
warning says plainly what it costs.

An address that already exists is reported and **left alone**. Quietly promoting whoever
holds an address to ADMIN is not a decision a bootstrap command should make.

For the same reason there is **no endpoint that changes the roles of an existing
account**. Creating an account with a role is one decision by one admin; changing one is
privilege escalation applied to somebody else, and it needs a shape this slice does not
have — who may promote whom, whether it takes a second approval, what happens to the
tokens already issued under the old roles. Getting that wrong is worse than requiring
SQL for it, so it is listed under *Known limitations* rather than half-built.

This also removed the last instruction in this README that told the reader to open a SQL
client. The whole loop — first admin, then organizer, then an event — now runs over the
API.

### Actuator: two gates, not one

Exposure and authorization are separate, and the dangerous endpoints are stopped by the
first one:

```
management.endpoints.web.exposure.include=health,info,metrics
management.endpoints.web.exposure.exclude=env,beans,heapdump,threaddump,configprops
```

`env` returns every configuration value, including `JWT_SECRET`. `heapdump` returns the
contents of memory, where credentials live in plaintext. Leaving them published and
guarded would mean one misconfigured matcher stands between an attacker and the signing
key. Left out of the exposure list they answer **404** — there is nothing there to
misconfigure. `ActuatorSecurityTest` asserts 404 rather than 403 for exactly this reason.

What is published is then authorized:

```java
.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
.requestMatchers("/actuator/**").hasRole("ADMIN")
```

`health` has to answer anonymously — a liveness probe cannot present a token. But
`show-details=when-authorized` with `roles=ADMIN` means an anonymous caller learns only
`UP`, while the breakdown of which dependency is failing goes to admins. The catch-all
below it means an endpoint published later is closed by default.

**Same port, deliberately.** The stronger production posture is
`management.server.port=8081` with `address=127.0.0.1`, so the actuator is not reachable
from outside the host at all. That was not done here because Boot builds a separate
context for a management port, and this project's `SecurityFilterChain` — the JWT, the
roles — would not apply to it. What is wanted here is an actuator guarded by *this
application's* security configuration; on a separate port the guard would be the
network's, with no authorization decision left to test. Anyone deploying this should set
the two properties and keep the rules as a second layer.

### The API description is not public

`/v3/api-docs` and Swagger UI need a token, which they get from the default
`anyRequest().authenticated()` — the deliberate act was **not** adding an exception for
them. A schema lists every endpoint, parameter and field name; published anonymously it
is a free map for anyone looking for a way in. Making it public is a one-line change for
anyone who wants it.

springdoc 3.x is the line built for Boot 4 (2.x targets Boot 3). Its parent is Boot
4.0.5 while this project is on 4.1.0, so `RoleAccessMatrixTest` fetches `/v3/api-docs`
for real — anonymously and with a token — rather than assuming the pairing works.

### Coverage is measured, not enforced

JaCoCo produces a report; there is no `check` goal and the build never fails over a
percentage. A threshold mostly teaches people to write tests that raise the number, and
the interesting question — *is the risky path covered* — is not one a percentage
answers. The figure is in the README and the two known holes are named there rather than
excluded to flatter it.

JaCoCo 0.8.15 is required, not incidental: it carries ASM 9.10.1, which reads Java 25
class files. Older versions fail on this project with an unsupported class file version.

## Known limitations

Everything here is a decision that was made and its cost accepted, not something
overlooked. Each links to where the reasoning is.

| Limitation | What it means in practice | Where |
|---|---|---|
| **Idempotency crash window** | If the process dies between the reservation committing and the key being marked complete, the key stays `IN_PROGRESS`. Retries get 409 until the lease lapses, then the work is redone — the reservation would be duplicated. Bounded by `IDEMPOTENCY_LEASE`. | ADR-4 |
| **Rate limit degrades per instance** | While Redis is unreachable each instance counts on its own, at `capacity / RATELIMIT_INSTANCES`. The total stops being exact. Chosen over failing open (no protection) or closed (self-inflicted outage). | ADR-5 |
| **Tests run on H2, not PostgreSQL** | Flyway is disabled in tests and Hibernate builds the schema, so migrations are not exercised by the suite. This has bitten twice: a PostgreSQL-only query, and a unique constraint that existed only in the migration. Testcontainers would close it. | *Testing* |
| **An audit failure fails the action** | The record is written in the same transaction as the thing it describes, so a database that cannot accept the insert also rejects the reservation. Deliberate: the alternative is a silent gap in the trail. | ADR-6 |
| **Roles cannot be changed after creation** | `POST /api/admin/users` creates an account with roles; there is no endpoint to change them afterwards. That still needs SQL. | *Creating accounts* |
| **Actuator shares the API's port** | Production would put it on `management.server.port` bound to localhost. Kept here so the endpoints are guarded by this project's own roles rather than by the network — which is what made the authorization testable. | ADR-7 |
| **Coverage has two holes** | The interactive password prompt (no terminal under Maven) and the Redis-backed rate limit counter, which the suite builds but never lets reach a live Redis — its failure path is tested, its success path only by hand. Both are covered by manual verification instead of being excluded from the measurement. | *Testing* |

## Testing

```bash
./mvnw clean verify
```

404 tests against in-memory H2; **no Docker required** — the suite is green on a machine
whose Docker daemon cannot be reached at all. The Redis cache is disabled throughout,
which means every test continuously exercises the same fallback path a Redis outage
produces in production, and `RateLimitStartsWithoutRedisTest` goes one step further:
shared counting switched on, pointed at a port nothing is listening on, and the
application still starts and still enforces the limit.

Most of them are readable from the name: unit (`*ServiceTest`, `*StatusTest`),
integration (`*ApiTest`), concurrency (`*ConcurrencyTest`), security
(`RoleAccessMatrixTest`, `*SecurityTest`). The rest are named after the guarantee they
pin, such as `DemoSeedIsOffByDefaultTest`.

The state machine is tested twice over: once as a pure transition table
(`ReservationStatusTransitionTest` pins all nine source/target combinations, so a rule
cannot be loosened without a test changing), and once end-to-end through the API.

There are three concurrency tests, one per hazard, and none carries `@Transactional` —
under one shared transaction the threads would never actually compete.

- **Oversell:** 20 threads against a 10-seat event; exactly 10 succeed, the counter
  reads 10, and it agrees with the sum over reservations.
- **Duplicate submission:** 20 requests carrying *one* idempotency key, through a real
  servlet container on a random port. Exactly one is allowed to do the work; the rest
  are told to retry or handed the finished result. One reservation, one key record.
  MockMvc cannot show this — it never leaves the calling thread.
- **Rate limit burst:** 40 threads on one key against a budget of 10. Exactly 10 get
  through — a limit that only holds when requests arrive one at a time is not a limit,
  and a burst is the shape of the attack it exists to stop.

Authorization is pinned as a table: `RoleAccessMatrixTest` walks every protected
endpoint against ADMIN, ORGANIZER, CUSTOMER, anonymous, and the resource's owner — 51
combinations. Ownership is a column of its own because it is where most of these rules
actually live: the same ORGANIZER is refused on someone else's event and allowed on
their own, and only testing one of those proves half a rule. The
behaviour tests around it stay; what the matrix adds is that a new endpoint forces a
decision about each role instead of leaving one unexamined.

Rate limits are deliberately left **on** for the whole suite with the ceiling out of
reach, so the filter runs on every request; only the tests that are about limits bring
the ceiling down. Disabling a security control in tests is how the sold-out cache once
hid a bug.

**Known gap:** migrations do not run in tests (Flyway is disabled there, H2 builds
the schema). A PostgreSQL-specific query bug once slipped past the suite because of
this and was only caught by manual verification, and a unique constraint that existed
only in the migration was invisible to the tests until it was declared on the entity
too. A Testcontainers-based migration test would close both.
