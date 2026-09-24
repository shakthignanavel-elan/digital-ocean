# Rate Limiter Microservices

Distributed rate limiting with two Spring Boot services sharing Redis for hot-path state.

## Architecture

| Service | Port | Responsibility |
|---------|------|----------------|
| `rate-limiter-dataplane` | 8080 | `POST /evaluate` — token check/consume via Redis Lua scripts |
| `rate-limiter-controlplane` | 8081 | Configuration CRUD + `GET /quota`; persists to Postgres, syncs Redis |

```
LB ──► dataplane  ──► Redis Cluster (token buckets / fixed windows)
LB ──► controlplane ──► Postgres (config) + Redis (quota state sync)
```

Rule updates go through the controlplane and are written to Redis immediately — **no service restart required**. Horizontal scale is supported because all instance state lives in Redis.

## Modules

- `rate-limiter-common` — shared DTOs/models/enums
- `rate-limiter-dataplane` — evaluate hot path
- `rate-limiter-controlplane` — management plane

## Prerequisites

- Java 21+
- Maven 3.9+ (or use the Maven wrapper after bootstrap)
- Docker (for Redis + Postgres)

## Quick start (Docker — recommended)

Builds and runs Postgres, Redis, controlplane, and dataplane:

```bash
docker compose up -d --build
```

| Service | URL |
|---------|-----|
| Dataplane | http://localhost:8080 (`POST /evaluate`, `/actuator/health`) |
| Controlplane | http://localhost:8081 (`/configuration/...`, `/quota/...`) |
| Redis | `localhost:6379` |
| Postgres | `localhost:5432` (`rate_limiter` / `rate_limiter` / `rate_limiter`) |

Smoke check after healthy:

```bash
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8080/actuator/health

# Publish config, then evaluate
curl -s -X POST "http://localhost:8081/configuration/payments/11111111-1111-1111-1111-111111111111" \
  -H 'Content-Type: application/json' \
  -d '{
    "burstableRateLimitConfig": {"rateLimitAlgorithm":"TOKEN_BUCKET","availableToken":100,"timeWindowInSeconds":60},
    "fixedRateLimitConfig": {"rateLimitAlgorithm":"SLIDING_WINDOW","availableToken":1000,"timeWindowInSeconds":3600},
    "tokenConfiguration": {"tokenCount":100},
    "tenantId":"11111111-1111-1111-1111-111111111111",
    "namespace":"payments"
  }'

curl -s -X POST http://localhost:8080/evaluate \
  -H 'Content-Type: application/json' \
  -d '{"namespace":"payments","tenantId":"11111111-1111-1111-1111-111111111111"}'
```

Second dataplane on port **8082** (same Redis, for multi-instance demos):

```bash
docker compose --profile multi up -d --build
```

Build images alone:

```bash
docker build --build-arg MODULE=rate-limiter-dataplane -t rate-limiter-dataplane:local .
docker build --build-arg MODULE=rate-limiter-controlplane -t rate-limiter-controlplane:local .
```

## Local JVM (infra only in Docker)

```bash
docker compose up -d redis postgres

./mvnw clean package -DskipTests

java -jar rate-limiter-controlplane/target/rate-limiter-controlplane-1.0.0-SNAPSHOT.jar
java -jar rate-limiter-dataplane/target/rate-limiter-dataplane-1.0.0-SNAPSHOT.jar
```

## Tests

| Area | What is covered |
|------|-----------------|
| Functional — config CRUD | Create/update/delete/get via service + MockMvc + H2 IT |
| Functional — quota state | Remaining / consumed / available tokens + reset timestamps |
| Functional — evaluate | `namespace`+`tenantId` and `apiKey` enforcement |
| Algorithms | `TOKEN_BUCKET`, `FIXED_WINDOW`, `SLIDING_WINDOW` |
| Multi-instance | Concurrent shared-state unit test + Redis dual-evaluator IT |
| Hot reload | Config sync resets Redis buckets; evaluate IT raises capacity live |
| Consistency | Evaluate then `GET /quota` matches consumed/remaining (Redis IT) |
| Redis (optional) | Testcontainers ITs (skipped if Docker missing) |

Run Redis-backed tests when Docker is available:

```bash
docker compose up -d redis
./mvnw test
```

Local H2 profile for controlplane (no Postgres):

```bash
java -jar rate-limiter-controlplane/target/rate-limiter-controlplane-1.0.0-SNAPSHOT.jar --spring.profiles.active=local
```

## APIs

### Dataplane

`POST /evaluate`

```json
{
  "namespace": "payments",
  "tenantId": "11111111-1111-1111-1111-111111111111"
}
```

Optional: `{ "namespace": "payments", "apiKey": "sk_live_xxx" }`

### Controlplane

- `POST /configuration/{namespace}/{tenantId}` — create
- `PUT /configuration/{namespace}/{tenantId}` — update
- `GET /configuration/{namespace}/{tenantId}` — read
- `DELETE /configuration/{namespace}/{tenantId}` — delete
- `GET /quota/{namespace}/{tenantId}` — burstable + sustained availability

Example create body:

```json
{
  "burstableRateLimitConfig": {
    "rateLimitAlgorithm": "TOKEN_BUCKET",
    "availableToken": 100,
    "timeWindowInSeconds": 60
  },
  "fixedRateLimitConfig": {
    "rateLimitAlgorithm": "SLIDING_WINDOW",
    "availableToken": 1000,
    "timeWindowInSeconds": 3600
  },
  "tokenConfiguration": {
    "tokenCount": 100
  },
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "namespace": "payments"
}
```

## Dataplane evaluate path

1. `ConfigurationStore.find(namespace, tenant)` — Redis or in-memory  
2. `AlgorithmEvaluatorFactory` → burstable + sustained evaluators  
3. `DualBucketQuotaStore.tryConsume(...)` — Redis Lua **or** in-memory synchronized state  

```yaml
rate-limiter:
  store: redis   # default production
  # store: memory  # local / integration tests (no Redis)
```

Integration tests use `rate-limiter.store=memory` (no Docker).

## Redis configuration model

Per `namespace` + `tenantId`, controlplane writes:

| Key | Purpose |
|-----|---------|
| `rl:config:{namespace}:{tenantId}` | Full configuration JSON (algorithms, capacities, windows) — **source of truth** |
| `rl:quota:burstable:{namespace}:{tenantId}` | Runtime burstable counters |
| `rl:quota:sustained:{namespace}:{tenantId}` | Runtime sustained counters |
| `…:events` | Sliding-window event log (ZSET) when that algorithm is configured |

Dataplane `POST /evaluate` loads the config document first, then applies the configured algorithms (`TOKEN_BUCKET` / `FIXED_WINDOW` / `SLIDING_WINDOW`) against runtime state. Missing config → deny (`NO_CONFIG`).

## Algorithms

- **TOKEN_BUCKET** — continuous refill based on `availableToken / timeWindowInSeconds`
- **FIXED_WINDOW** — hard reset when the window elapses
- **SLIDING_WINDOW** — rolling window of request timestamps (Redis ZSET); oldest event leaving the window frees capacity

Both burstable and sustained buckets must allow a request for `/evaluate` to return `allowed: true`. Consumption is atomic across both keys via a Redis Lua script that reads the config key.

`GET /quota` returns `availableToken`, `remainingToken`, `currentConsumedToken`, and `resetTimestamp` for burstable and sustained, also driven by the Redis config document.
