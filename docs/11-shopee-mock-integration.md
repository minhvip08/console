# Shopee + Shopee Ads — Mock-first Integration

This backend milestone uses **synthetic data only**. It has no vendor HTTP client, credentials, OAuth callback or scheduler. The [Elton reference](https://docs.eltondata.com/docs/data-sources/shopee-and-shopee-ads/) informs configuration/lifecycle UX; it does not supply a public API or data schema. The endpoints and fixture types below are Console-owned and are not Elton or Shopee endpoints/types.

## Enable locally

Backoffice uses the [Spring Boot Flyway starter](https://docs.spring.io/spring-boot/reference/using/build-systems.html) to apply migrations, including `V7__shopee_mock_integration.sql`, before JPA validation. Run it against PostgreSQL and run Console with Redis as usual. Configure **both** services:

```sh
export SHOPEE_MOCK_ENABLED=true
export SHOPEE_MOCK_ADMIN_USERS=mock-admin
```

Defaults are disabled and no admins. These settings enable mocks only; no combination enables live access or scheduled sync. The existing mock RoleStore may be used locally; a real scenario grant is also required when a real RoleStore is configured. Forward `X-User-Id` only from a trusted gateway; this header is not authentication by itself. Keep direct development ports private. Existing generic Backoffice management endpoints still require the application's upstream Admin protection.

## Management API (Backoffice, port 8081)

All requests require a trusted `X-User-Id`. Admin users configured above can manage sources, imports and connections. Operators explicitly listed in a source's `allowedUserIds` can discover/read that source and its connections; source grants supplement scenario permissions.

| Operation | Route |
| --- | --- |
| Mock capabilities / fixture types | `GET /api/shopee/catalog` |
| List/create source | `GET/POST /api/shopee/sources` |
| Read/edit/delete source | `GET/PUT/DELETE /api/shopee/sources/{id}` |
| Simulate grant / regrant | `POST /api/shopee/sources/{id}/authorize` or `/reauthorize` |
| List/create connection | `GET/POST /api/shopee/connections` |
| Read/edit/delete connection | `GET/PUT/DELETE /api/shopee/connections/{id}` |
| Provision scenario once | `POST /api/shopee/connections/{id}/scenario` |

Example source request (POST or PUT):

```json
{
  "kind": "SHOPEE",
  "displayName": "Mock sales shop",
  "description": "Synthetic demonstration",
  "enabled": true,
  "allowedUserIds": ["mock-operator"]
}
```

Use `SHOPEE_ADS` for advertising fixtures. Save with `enabled: false` to disable. Source kind is immutable. Source states are `AUTH_REQUIRED`, `AUTHORIZED`, `EXPIRED` and `DISABLED`.

Simulated authorization request:

```json
{"accountType":"SHOP_ACCOUNT","shopAccountId":"fixture_shop_1","outcome":"GRANTED"}
```

Only synthetic `fixture_...` account IDs are accepted; never enter credentials. `outcome: "CANCELLED"` leaves the current grant unchanged. Main Account is rejected. Grants expire after **30 synthetic days**, purely a local simulation, not an assertion about provider token lifetimes. Reauthorization uses the same shared flow for both source kinds. Reauthorizing to a different account blocks old executions; start a fresh execution.

Example connection request:

```json
{
  "sourceId": 1,
  "name": "Mock sales preview",
  "description": "Manual fixture retrieval",
  "dataTypes": ["fixture_sales", "fixture_sales_summary"],
  "firstLoadDate": "2026-10-01",
  "lookbackDays": 7,
  "frequencyMinutes": 60,
  "executionMode": "MANUAL"
}
```

Choose a nonfuture date. Lookback is 1–3650 days (a local mock bound). Ads supports `fixture_ads_performance` and `fixture_ads_summary`. Empty, duplicate or incompatible selections are rejected. An authorized, enabled source is required to create/edit a connection. Connection source is immutable.

`frequencyMinutes` is optional, positive, **inactive metadata**; responses always say `frequencyActive: false`. `SCHEDULED` and other modes are rejected. No automatic run is created.

Deletion is local and soft, preserving historical FKs. Delete dependent connections before deleting a source. Deleting a connection prevents new runs and reading/resuming its existing runs; it does not delete execution records or imply vendor revocation. Disabling, expiring or removing a user's source grant similarly blocks subsequent requests, including cached results. An already dispatched synthetic request may finish; no new request may bypass the current policy.

## Import and run

1. Create and authorize a source; save its connection.
2. Provision with `POST /api/shopee/connections/{id}/scenario` as Admin. The result includes `scenarioDefinitionId`. Repeated calls return the same scenario. Provisioning imports `mock_shopee` or `mock_shopee_ads` through the existing Platform catalog/Swagger importer and creates a step, normalized trigger/action link and variable-bound widgets. Direct `/api/platform/imports` calls for these catalog IDs also require mock Admin access.
3. Start through Console `POST /api/executions` with `{"scenarioId": <id>}` and the operator's trusted `X-User-Id`. Body `userId` cannot substitute for that header on integration scenarios. Current scenario permission and source access are both required. Run creation freezes shop identity, selected types, first-load date, lookback and the UTC run time into `shopee_run`.
4. The start response contains the step UI. Read the button's numeric `triggerId` and the select widgets' actual `outputVarId` values. Trigger `POST /api/executions/{executionId}/trigger` with `X-User-Id`, a stable `X-Request-Id`, and `{"triggerId": <id>, "inputs": {"<mode-var-id>": "INITIAL", "<outcome-var-id>": "SUCCESS"}}`. Empty inputs default to initial/success. Options are strictly limited; arbitrary inputs are rejected before persistence.
5. The trigger response includes the JSON `summary` string on the first response and cached repeats. It contains the synthetic preview and statuses. Read stored preview variables through `GET /api/shopee/executions/{executionId}/preview`; this endpoint checks current access and returns only preview action outputs.
6. `POST /api/executions/{id}/resume` restores context to Redis through the existing flow. Read the preview endpoint to hydrate results after resume. Both routes recheck source readiness and current scenario permissions, including for the execution's creator.

`INITIAL` covers the configured date at 00:00 UTC → frozen run time. `INCREMENTAL` covers frozen run time minus lookback days → frozen run time. Endpoints are reported explicitly; no dataset completeness is claimed. There are two deterministic fixture records per selected type, at most four. Responses are marked `mode: mock-only`, `liveData: false`, `complete: false`. They contain no customer data or secrets. Editing a connection does not change a started run's configuration.

`FAILED` and `TIMEOUT` are deliberate local outcome options. FAILED preserves the existing known-failure/Saga route; the provisioned read-only action has no compensation definition. TIMEOUT preserves the existing unknown-outcome/reconciliation route. It sends no vendor call and has no invented verification endpoint. Follow the manual reconciliation path for such a simulation, or start a fresh execution to demonstrate success. Changing inputs on a completed trigger does not rerun it; a new preview requires a new execution. Redis request/result keys are scoped to execution and trigger; action keys remain execution-scoped and are not business-record upsert keys.

## Live and durable-sync gates

Live access remains blocked until the owner chooses Elton-mediated or direct Shopee integration and supplies verified API entitlement, auth/callback/credential contracts, schemas/types, quotas and approved sales/Ads test access. These credential contracts are separate; consent to Elton does not authorize direct Shopee calls. No mock API route or fixture schema should be promoted as a vendor contract.

Durable scheduled sync remains outside v1. It needs separate approval plus destination/upsert keys, checkpoints, resumable pagination, quotas/backoff, token refresh/revocation, leases/recovery, sanitized durable storage/logging, retention and operational runbooks. If Elton owns those capabilities, integrate its documented guarantees rather than duplicating them. Disabling mock mode in both services is the immediate rollback; retained rows and definitions remain inaccessible through the integration flows.
