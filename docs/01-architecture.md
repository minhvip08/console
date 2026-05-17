# System Architecture

## Tổng quan

**Scenario Console** là integrated console cho phép Technical PM và Game Operations thực hiện workflow phức tạp qua một điểm truy cập duy nhất, thay vì phải thao tác trên nhiều platform console riêng lẻ.

---

## High-Level Components

```
┌─────────────────────────────────────────────────────────┐
│                     User Domain                          │
│         Console Front          Backoffice Front          │
└──────────────────┬──────────────────┬────────────────────┘
                   │ HTTP/REST        │ HTTP/REST
┌──────────────────▼──────────────────▼────────────────────┐
│              GameScale Console Gateway                    │
│         (Auth, Routing, Permission pre-check)            │
└──────────────────┬──────────────────┬────────────────────┘
                   │                  │
       ┌───────────▼────┐  ┌──────────▼──────────┐
       │  Console API   │  │   Backoffice API     │
       │  (Execution)   │  │   (Management CRUD)  │
       └───────┬────────┘  └─────────────────────┘
               │
   ┌───────────┼───────────┐
   ▼           ▼           ▼
  RDS       Redis         MSK
(PostgreSQL) (ElastiCache) (Kafka/Debezium)
               │
        External Services
   (Platform APIs / Payment GW / ...)
```

---

## Console vs Backoffice

| | Console API | Backoffice API |
|---|---|---|
| **Mục đích** | Thực thi scenario | Quản lý definition |
| **User** | Technical PM, Operations | Admin |
| **Thao tác chính** | Start, Execute step, Resume | CRUD scenario/action |
| **Data chủ yếu** | scenario_execution, action_execution | scenario_definition, step_definition, action_definition |

---

## Application Architecture — Clean Architecture

```
┌────────────────────────────────────────────┐
│         Frameworks & Drivers               │  Spring Boot, JPA, Redis Client, S3 SDK
├────────────────────────────────────────────┤
│          Interface Adapters                │  Controller, Presenter, Repository Impl
├────────────────────────────────────────────┤
│          Use Cases (Application)           │  Execution Engine, Context Manager
├────────────────────────────────────────────┤
│          Entities (Domain)                 │  Scenario, Step, Action
└────────────────────────────────────────────┘
         Dependency Rule: hướng vào trong →
```

**Lợi ích:** Domain logic không phụ thuộc framework, dễ test, dễ swap infrastructure.

---

## Package Structure — Vertical Slice by Feature

```
src/
├── feature/
│   ├── executor/               ← Console: Scenario Execution features
│   │   ├── scenario.start/
│   │   ├── scenario.resume/
│   │   ├── step.execute/
│   │   └── action.execute/
│   ├── context/                ← Runtime Context Management
│   │   ├── context.save/
│   │   ├── context.get/
│   │   └── context.restore/
│   └── management/             ← Backoffice: Component CRUD
│       ├── scenario.crud/
│       ├── step.crud/
│       └── action.crud/
└── infrastructure/             ← Shared implementations
    ├── repository/postgresql/
    ├── repository/redis/
    ├── repository/s3/
    └── client/external-api/
```

Mỗi feature slice có full stack riêng: Controller → UseCase → DataProvider — team members có thể develop parallel trên các feature khác nhau.
