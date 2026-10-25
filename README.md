# Scenario Console

Integrated console cho Nexon Game Ops — chạy multi-step scenarios qua một điểm truy cập.

📖 **Đọc trước:** [CLAUDE.md](CLAUDE.md) (project guide) — [plan.md](plan.md) (implementation roadmap) — [docs/](docs/) (canonical design)

📘 **Developer guide:** [Setup, configuration, API usage, testing, and troubleshooting](docs/developer-guide.md).

---

## Repo Layout

```
.
├── common/          Library: shared entities, ULID utils
├── console-api/     Service: execution engine (port 8080)
├── backoffice-api/  Service: definition CRUD + Flyway migrations (port 8081)
├── docker-compose.yml
├── docs/            Canonical design docs (xem docs/README.md)
├── CLAUDE.md, plan.md
```

---

## Quick Start (Local Dev)

### 1. Spin up infrastructure

```bash
# Start docker daemon (if needed)
sudo systemctl start docker

# Postgres + Redis only — đủ cho Phase 1-6
docker compose up -d postgres redis

# Full stack incl. Kafka + Debezium (Phase 7)
docker compose --profile full up -d
```

### 2. Run migrations (via backoffice-api startup)

```bash
./gradlew :backoffice-api:bootRun
```

Flyway tự apply `V1..V6` từ `backoffice-api/src/main/resources/db/migration/`.

### 3. Run console-api

```bash
./gradlew :console-api:bootRun
```

### 4. Build all

```bash
./gradlew build
```

---

## Stack

- Java 25, Spring Boot 4.0.6, Gradle 9.4
- PostgreSQL 15 + Flyway (migrations owned by backoffice-api)
- Redis 7 (single shard — Lua script atomic)
- Kafka 3.7 + Debezium CDC (Phase 7+)

---

## Service Endpoints

| Service | URL | Health |
|---|---|---|
| console-api | http://localhost:8080 | /actuator/health |
| backoffice-api | http://localhost:8081 | /actuator/health |

---

## Tests

```bash
./gradlew test                 # all modules
./gradlew :backoffice-api:test # one module
```

Integration tests dùng Testcontainers — cần docker daemon running.
