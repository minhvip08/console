# Scenario Console — Developer Guide

This guide describes the current repository implementation. Scenario Console lets game operations teams define scenarios and execute their steps through a shared console API. Backoffice manages definitions; the console executes actions and maintains execution context; reconciliation processes CDC events and scheduled recovery work.

## Project layout

| Module or directory | Responsibility |
|---|---|
| `common/` | Shared domain entities, enums, utilities, and Flyway SQL migrations. A library, not a standalone application. |
| `backoffice-api/` | Scenario, step, service, and action management; platform Swagger import; database migration runner. |
| `console-api/` | Scenario execution, trigger handling, Redis context, idempotency, compensation, and permission checks. |
| `reconciliation-service/` | Kafka CDC consumers, verification, polling, and compensation recovery. |
| `infra/` | Kubernetes examples, LocalStack initialization, and disaster recovery runbook. |
| `docs/` | Detailed architecture, data model, execution, operations, and testing documentation. |

The root `settings.gradle` includes all four modules. Source packages begin with `com.simi`.

## Requirements

- JDK 25: configured by the root Gradle Java toolchain.
- Docker Engine with Docker Compose for local PostgreSQL and Redis, and for integration tests.
- Network access on the first build to download Gradle and dependencies.
- `curl` for the API checks below.

Use the checked-in `./gradlew` wrapper. It selects Gradle 9.4.1; a separate Gradle installation is unnecessary. The build declares Spring Boot 4.0.6.

Run commands below from the repository root. Check the prerequisites with:

```bash
java -version
./gradlew --version
docker version
docker compose version
```

## Start the core development environment

### 1. Start PostgreSQL and Redis

```bash
docker compose up -d postgres redis
docker compose ps
```

Wait for both containers to become healthy. PostgreSQL listens on port 5432 and Redis on port 6379. The development database is `console`, with username `console` and password `console_dev`.

Compose starts infrastructure only. Start the Java applications separately.

### 2. Start backoffice and apply migrations

In one terminal:

```bash
./gradlew :backoffice-api:bootRun
```

Backoffice starts on port 8081. It runs Flyway migrations from `classpath:db/migration`; the SQL files are physically located in `common/src/main/resources/db/migration/` and reach backoffice through its `common` dependency. The current migrations are V1 through V6.

Wait for startup to finish before starting the other applications. Their Hibernate configuration uses `ddl-auto: validate` and expects the schema to exist.

In another terminal, check readiness:

```bash
curl --fail --silent --show-error http://localhost:8081/actuator/health
```

Expected health status: `UP`.

### 3. Start the execution API

In another terminal:

```bash
./gradlew :console-api:bootRun
```

Then check the running APIs:

```bash
curl --fail --silent --show-error http://localhost:8080/actuator/health
curl --fail --silent --show-error http://localhost:8080/api/scenarios
curl --fail --silent --show-error http://localhost:8081/api/platform/services
```

On a fresh database, the scenario list should be empty. The platform endpoint uses a bundled mock client and lists `platform_coupon` and `platform_payment`.

### 4. Stop development services

Stop each `bootRun` process with Ctrl+C, then stop infrastructure:

```bash
docker compose down
```

This retains the named PostgreSQL data volume for the next run.

## Configuration

These environment variables are read by the checked-in application configurations:

| Variable | Default | Used by |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/console` | All three applications |
| `DB_USER` | `console` | All three applications |
| `DB_PASS` | `console_dev` | All three applications |
| `REDIS_HOST` | `localhost` | Console |
| `REDIS_PORT` | `6379` | Console |
| `ROLESTORE_MOCK` | `true` | Console |
| `ROLESTORE_CACHE_TTL` | `300` seconds | Console |
| `AWS_ENDPOINT` | `http://localhost:4566` | Console |
| `AWS_REGION` | `ap-northeast-2` | Console |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` | `test` / `test` | Console |
| `S3_ARTIFACTS_BUCKET` | `console-artifacts` | Console |
| `S3_CLAIM_CHECK_THRESHOLD` | `102400` bytes | Console |
| `CLOUDWATCH_METRICS_ENABLED` | `false` | Console |
| `KAFKA_BOOTSTRAP` | `localhost:9092` | Reconciliation |

Default application ports are 8080 for console, 8081 for backoffice, and 8082 for reconciliation. Spring Boot's `SERVER_PORT` override can be set per process when a port is occupied.

`ROLESTORE_MOCK=true` uses a client that returns all scenario IDs for any user. The console controllers accept `X-User-Id` for permission checks; several paths skip those checks when the header/user context is absent. The current local configuration does not demonstrate production authentication.

## API orientation

The two APIs share some paths but serve different purposes. Use port 8081 to manage definitions and port 8080 to execute them.

| Service | Method and route | Purpose |
|---|---|---|
| Backoffice | `GET /api/scenarios` | List scenario definitions with step summaries |
| Backoffice | `POST /api/scenarios` | Create a scenario definition |
| Backoffice | `GET /api/scenarios/{scenarioId}/steps` | List steps and trigger configuration |
| Backoffice | `GET /api/services`, `GET /api/actions` | List service/action definitions |
| Backoffice | `POST /api/platform/imports` | Import a bundled platform service's Swagger definitions |
| Console | `GET /api/scenarios` | List available scenarios, filtered when user context is supplied |
| Console | `POST /api/executions` | Start an execution; body contains `scenarioId` and optional `userId` |
| Console | `GET /api/executions/{id}` | Read execution and step information |
| Console | `POST /api/executions/{id}/trigger` | Execute a trigger with `triggerId`, request ID, and input variables |
| Console | `POST /api/executions/{id}/resume` | Restore execution context into Redis from the database snapshot |
| Console | `GET /api/executions/{id}/compensation-status` | Inspect compensation state |
| Console | `POST /api/executions/{id}/retry-compensation` | Request compensation retry |

Starting an execution requires an existing scenario with at least one step; an empty scenario returns HTTP 400. Define the scenario, steps, triggers, and actions in backoffice before using the execution endpoints. The DTOs alongside the backoffice controllers describe their request shapes.

Trigger requests require a nonblank `X-Request-Id` header or `requestId` body field. The header takes precedence. Reuse the request ID when retrying the same logical request. Inputs are a map of variable IDs to values.

Inspect the response body as well as HTTP status: cached trigger results return 200, requests already in progress return 202, and action timeouts also return 202. A response can contain failed actions even when its HTTP status is 200. Resume returns 409 for executions already marked `COMPLETED` or `COMPENSATED`.

## Optional infrastructure

### LocalStack

For local AWS emulation:

```bash
docker compose --profile localstack up -d
```

The endpoint is `http://localhost:4566`. The initialization script is `infra/localstack/init-aws.sh`. Console defaults already target this endpoint; CloudWatch metrics export is disabled by default. LocalStack is needed when exercising S3 claim-check behavior rather than only basic API checks.

### Kafka, Debezium, and reconciliation

The `full` Compose profile adds Kafka, Debezium Connect, and a connector registration container. It does not launch `reconciliation-service`.

The checked-in broker advertises `kafka:9092`, a hostname intended for the Compose network. A reconciliation process launched on the host defaults to `localhost:9092`, but Kafka clients subsequently use the advertised broker address. Configure a host-reachable advertised listener and a matching `KAFKA_BOOTSTRAP` before using this mode. Keep a Docker-network listener available for Debezium.

After configuring connectivity, start the optional infrastructure:

```bash
docker compose --profile full up -d
docker compose --profile full ps
curl --fail --silent --show-error http://localhost:8083/connectors/console-cdc/status
```

Check that the connector and its tasks report `RUNNING`. A healthy Debezium container alone does not confirm successful connector registration. The connector targets the `action_execution`, `scenario_execution`, and `saga_compensation_log` tables.

Then start the worker in another terminal:

```bash
./gradlew :reconciliation-service:bootRun
```

Its health endpoint is `http://localhost:8082/actuator/health`. See `docs/07-cdc-reconciliation.md` and `docs/kafka-guide.md` for the design and Kafka background.

## Build and test

```bash
./gradlew build
./gradlew test
./gradlew :console-api:test
./gradlew :common:test
```

`build` includes tests. Run `test` separately when you only need verification. The console integration test uses Testcontainers to start its own PostgreSQL and Redis containers, so Docker must be available even if the Compose stack is already running.

For checks that do not start the integration test:

```bash
./gradlew :common:test
./gradlew :console-api:test --tests '*ParameterResolverTest' --tests '*TriggerControllerTest' --tests '*ActionExecutorTest'
```

JUnit reports are under `<module>/build/reports/tests/test/index.html`. JaCoCo HTML reports are under `<module>/build/reports/jacoco/test/html/index.html` and are configured to run after tests.

The integration test uses Hibernate `create-drop` with isolated containers. It exercises application/database behavior but does not validate Flyway migration scripts. Backoffice startup against the development database is a separate migration check.

## Troubleshooting

| Symptom | Check |
|---|---|
| Gradle cannot find Java 25 | Check `java -version` and the installed JDK/toolchain. Use `./gradlew`, not a different system Gradle. |
| Database connection refused | Check `docker compose ps`, port 5432, and `DB_URL`, `DB_USER`, and `DB_PASS`. |
| Hibernate schema validation fails | Confirm backoffice completed Flyway migration against the same database. |
| Redis connection refused | Check the Redis container and the console's `REDIS_HOST` / `REDIS_PORT`. |
| Scenario list is empty | Create definitions through backoffice. Importing platform actions does not create a complete scenario. |
| Execution start returns 400 | Confirm the scenario has at least one step. |
| Trigger returns 400 for request ID | Supply a nonblank `X-Request-Id` or body `requestId`. |
| Kafka bootstrap connects but consumption fails | Check advertised broker addresses, connector status, and `KAFKA_BOOTSTRAP`. |
| S3 operation fails locally | Check LocalStack, its initialization script, and the configured artifacts bucket. |
| Testcontainers cannot start | Check Docker daemon access; Compose containers do not replace the test-owned containers. |

## Where to read next

- `docs/README.md`: index of the design documents.
- `docs/01-architecture.md` and `docs/02-core-concepts.md`: architecture and domain vocabulary.
- `docs/03-execution-engine.md` through `docs/07-cdc-reconciliation.md`: execution, context, idempotency, compensation, and recovery.
- `docs/08-database.md` and `common/src/main/resources/db/migration/`: schema design and actual migrations.
- `docs/how-to-read-code.md`, `docs/code-guide.md`, and `docs/testing.md`: code navigation and testing guidance.

## Verification scope

This guide was checked against the repository's build files, application YAML, Compose configuration, controller source, migrations, and existing tests. Application startup, Docker profiles, and test execution were not performed as part of writing it. The optional Kafka setup requires the listener adjustment described above before host-run reconciliation can be verified.
