# Scenario System Design

## Hierarchy

```
Scenario
  └── Step[]
        ├── step_definition.ui     (JSONB) — widget layout, mỗi widget có outputVarId
        ├── step_trigger           (table) — trigger config (button_click, on_load, on_change)
        └── step_trigger_action    (table) — trigger → action mapping với thứ tự
```

UI elements trong step kết nối với Action qua **Variable** (`VAR_ULID`) — `outputVarId` của widget khớp với `varId` của action parameter.

---

## Core Concepts

| Concept | Mô tả | Ví dụ |
|---|---|---|
| **Scenario** | Luồng công việc hoàn chỉnh, gồm nhiều Step tuần tự | "Release Coupon Campaign" |
| **Step** | Một bước trong scenario — chứa UI elements và Actions | "Configure Coupon" |
| **Variable** | Kết nối output của widget với input của action — ID do frontend tự gen | `VAR_01JWXYZ` |
| **Action** | Thao tác nguyên tử gọi External API | POST /coupons |

---

## step_definition.ui (JSONB)

Khai báo UI elements của step. Mỗi widget có `outputVarId` do frontend tự gen khi user kéo thả kết nối.

```json
{
  "elements": [
    {
      "id": "widget_01JWB2W1",
      "type": "text_input",
      "label": "Coupon Name",
      "outputVarId": "VAR_01JWXYZ",
      "required": true
    },
    {
      "id": "widget_01JWB2W2",
      "type": "number_input",
      "label": "Discount (%)",
      "outputVarId": "VAR_01JWABC",
      "min": 1,
      "max": 100
    },
    {
      "id": "widget_01JWB2W3",
      "type": "table",
      "label": "Campaign Results",
      "outputVarId": "VAR_01JWDEF",
      "selectable": true
    },
    {
      "id": "widget_01JWB2W4",
      "type": "button",
      "label": "Create Coupon",
      "variant": "primary",
      "triggerId": "trigger_01JWB2T1"
    }
  ]
}
```

**Widget types:** `text_input` `number_input` `select` `multi_select` `date_picker` `table` `json_viewer` `text_display` `button`

---

## step_trigger — Trigger Config

Tách ra khỏi JSONB để `trigger_execution` có FK thật.

| Trigger type | Khi nào chạy |
|---|---|
| `button_click` | User bấm button có `triggerId` tương ứng |
| `on_load` | Step được render lần đầu (prefetch data) |
| `on_change` | Giá trị của `widgetId` thay đổi |

---

## action_definition — varId thay cho alias

Parameters và output dùng `varId` (VAR_ULID) thay vì alias string. Runtime bind bằng cách match `varId` trong Execution Context.

```json
{
  "service": "coupon-service",
  "adapter": "http",
  "endpoint": "/coupons",
  "method": "POST",
  "parameters": [
    { "name": "campaign_id", "varId": "VAR_01JWDEF", "in": "body", "type": "string", "required": true },
    { "name": "name",        "varId": "VAR_01JWXYZ", "in": "body", "type": "string", "required": true },
    { "name": "discount",    "varId": "VAR_01JWABC", "in": "body", "type": "number", "required": true }
  ],
  "output": [
    { "field": "coupon_id", "varId": "VAR_01JWGHI", "type": "string" }
  ]
}
```

---

## Execution Context — ContextDataItem

Mọi data item (UI input hoặc Action output) được lưu vào Execution Context:

```json
{
  "key": "exec:exec_01JW:UI_INPUT:widget_01JWB2W1:VAR_01JWXYZ",
  "varId": "VAR_01JWXYZ",
  "value": "SUMMER2024",
  "valueType": "string",
  "version": "1.1",
  "origin": {
    "sourceType": "UI_INPUT",
    "scenarioId": "scen_01JWAB3CD5EF6GH7IJ",
    "stepId": "step_01JW02",
    "stepOrder": 2,
    "sourceId": "widget_01JWB2W1",
    "sourceName": "text_input",
    "label": "Coupon Name"
  },
  "createdAt": "2026-05-15T10:30:00Z"
}
```

**Redis 3-Key Model:**

```
C key: exec:{executionId}:{sourceType}:{sourceId}:{varId}   → String (JSON)
A key: index:step:{executionId}                              → ZSET  (score = stepOrder)
B key: group:{executionId}:var:{varId}                      → Set   (members = C keys)
```

---

## Data Flow: Button Click → Action Execution

```
User điền fields → Widget lưu value vào ContextDataItem
  text_input   outputVarId: VAR_01JWXYZ → value "SUMMER2024"
  number_input outputVarId: VAR_01JWABC → value 30

User bấm button [Create Coupon]
  → triggerId → step_trigger → step_trigger_action → action_definition

Backend thực thi action:
  1. Merge context từ tất cả steps trước (ZRANGEBYSCORE) + current inputs
  2. action_definition.parameters khai báo:
       "campaign_id" ← varId: VAR_01JWDEF  ✅ lấy từ Step 1 output
       "name"        ← varId: VAR_01JWXYZ  ✅ lấy từ widget vừa nhập
       "discount"    ← varId: VAR_01JWABC  ✅ lấy từ widget vừa nhập
  3. Gọi POST /coupons
  4. Response: { "coupon_id": "cpn_01JWXYZ..." }
  5. Lưu output: varId: VAR_01JWGHI → Execution Context
```

---

## Example: Release Coupon Campaign

> PM tạo và phát hành coupon mà không cần vào từng service riêng lẻ.

### Scenario Definition

```json
{
  "id":   "scen_01JWAB3CD5EF6GH7IJ",
  "name": "Release Coupon Campaign",
  "steps": [
    { "id": "step_01JW01", "name": "Select Campaign",      "order": 1 },
    { "id": "step_01JW02", "name": "Configure Coupon",     "order": 2 },
    { "id": "step_01JW03", "name": "Select Target Users",  "order": 3 },
    { "id": "step_01JW04", "name": "Review & Release",     "order": 4 }
  ]
}
```

---

### Step 1 — Select Campaign

#### step_definition.ui

```json
{
  "elements": [
    {
      "id": "widget_01JWB1W1",
      "type": "text_input",
      "label": "Search Campaign",
      "outputVarId": "VAR_01JWSEARCH"
    },
    {
      "id": "widget_01JWB1W2",
      "type": "button",
      "label": "Search",
      "variant": "secondary",
      "triggerId": "trigger_01JWB1T1"
    },
    {
      "id": "widget_01JWB1W3",
      "type": "table",
      "label": "Campaign Results",
      "outputVarId": "VAR_01JWCAMPAIGNID",
      "columns": ["id", "name", "status", "start_date"],
      "selectable": true
    }
  ]
}
```

#### step_trigger + step_trigger_action

| Trigger | Type | Widget |
|---|---|---|
| `trigger_01JWB1T1` — search_campaigns | `button_click` | widget_01JWB1W2 |

→ actions: `actdef_search_campaigns` (order 1)

#### action_definition — `actdef_search_campaigns`

```json
{
  "service":  "campaign-service",
  "endpoint": "/campaigns/search",
  "method":   "GET",
  "parameters": [
    { "name": "query", "varId": "VAR_01JWSEARCH", "in": "query", "type": "string", "required": true }
  ],
  "output": [
    { "field": "campaign_id", "varId": "VAR_01JWCAMPAIGNID", "type": "string" }
  ]
}
```

**Data flow Step 1:**
```
[text_input] VAR_01JWSEARCH ──▶ actdef_search_campaigns ──▶ table results
                                                              user chọn row
                                                              → VAR_01JWCAMPAIGNID ✓
```

---

### Step 2 — Configure Coupon

#### step_definition.ui

```json
{
  "elements": [
    {
      "id": "widget_01JWB2W0",
      "type": "text_display",
      "label": "Campaign",
      "outputVarId": "VAR_01JWCAMPAIGNID",
      "readOnly": true
    },
    {
      "id": "widget_01JWB2W1",
      "type": "text_input",
      "label": "Coupon Name",
      "outputVarId": "VAR_01JWXYZ",
      "required": true
    },
    {
      "id": "widget_01JWB2W2",
      "type": "number_input",
      "label": "Discount (%)",
      "outputVarId": "VAR_01JWABC",
      "min": 1,
      "max": 100,
      "required": true
    },
    {
      "id": "widget_01JWB2W3",
      "type": "date_picker",
      "label": "Expiry Date",
      "outputVarId": "VAR_01JWEXPIRY",
      "required": true
    },
    {
      "id": "widget_01JWB2W4",
      "type": "button",
      "label": "Create Coupon",
      "variant": "primary",
      "triggerId": "trigger_01JWB2T1"
    }
  ]
}
```

#### step_trigger + step_trigger_action

| Trigger | Type | Widget |
|---|---|---|
| `trigger_01JWB2T1` — create_coupon | `button_click` | widget_01JWB2W4 |

→ actions: `actdef_create_coupon` (order 1)

#### action_definition — `actdef_create_coupon`

```json
{
  "service":  "coupon-service",
  "endpoint": "/coupons",
  "method":   "POST",
  "parameters": [
    { "name": "campaign_id",  "varId": "VAR_01JWCAMPAIGNID", "in": "body", "type": "string", "required": true },
    { "name": "name",         "varId": "VAR_01JWXYZ",        "in": "body", "type": "string", "required": true },
    { "name": "discount",     "varId": "VAR_01JWABC",        "in": "body", "type": "number", "required": true },
    { "name": "expiry_date",  "varId": "VAR_01JWEXPIRY",     "in": "body", "type": "string", "required": true }
  ],
  "output": [
    { "field": "coupon_id", "varId": "VAR_01JWGHI", "type": "string" }
  ]
}
```

**Data flow Step 2:**
```
VAR_01JWCAMPAIGNID (từ Step 1)  ──┐
VAR_01JWXYZ   ← widget nhập tên  ─┤
VAR_01JWABC   ← widget nhập %    ─┼──▶ POST /coupons ──▶ VAR_01JWGHI ✓
VAR_01JWEXPIRY← widget nhập date ─┘
```

---

### Step 3 — Select Target Users

#### step_definition.ui

```json
{
  "elements": [
    {
      "id": "widget_01JWB3W1",
      "type": "multi_select",
      "label": "User Segments",
      "outputVarId": "VAR_01JWSEGMENTS",
      "optionsVarId": "VAR_01JWSEGLIST",
      "required": true
    },
    {
      "id": "widget_01JWB3W2",
      "type": "text_display",
      "label": "Estimated Users",
      "outputVarId": "VAR_01JWUSERCOUNT",
      "readOnly": true
    },
    {
      "id": "widget_01JWB3W3",
      "type": "button",
      "label": "Next",
      "variant": "primary",
      "triggerId": null
    }
  ]
}
```

#### step_trigger + step_trigger_action

| Trigger | Type | Widget |
|---|---|---|
| `trigger_01JWB3T1` — load_segments | `on_load` | null |
| `trigger_01JWB3T2` — estimate_targets | `on_change` | widget_01JWB3W1 |

→ trigger_01JWB3T1 actions: `actdef_get_segments` (order 1)
→ trigger_01JWB3T2 actions: `actdef_estimate_targets` (order 1)

#### action_definition — `actdef_get_segments`

```json
{
  "service":    "segment-service",
  "endpoint":   "/segments",
  "method":     "GET",
  "parameters": [],
  "output": [
    { "field": "segments", "varId": "VAR_01JWSEGLIST", "type": "array" }
  ]
}
```

#### action_definition — `actdef_estimate_targets`

```json
{
  "service":  "segment-service",
  "endpoint": "/segments/estimate",
  "method":   "POST",
  "parameters": [
    { "name": "segment_ids", "varId": "VAR_01JWSEGMENTS", "in": "body", "type": "array", "required": true }
  ],
  "output": [
    { "field": "total_users", "varId": "VAR_01JWUSERCOUNT", "type": "number" }
  ]
}
```

---

### Step 4 — Review & Release

#### step_definition.ui

```json
{
  "elements": [
    {
      "id": "widget_01JWB4W1",
      "type": "json_viewer",
      "label": "Release Preview",
      "outputVarId": "VAR_01JWPREVIEW",
      "readOnly": true
    },
    {
      "id": "widget_01JWB4W2",
      "type": "button",
      "label": "Confirm & Release",
      "variant": "danger",
      "triggerId": "trigger_01JWB4T2"
    },
    {
      "id": "widget_01JWB4W3",
      "type": "json_viewer",
      "label": "Release Result",
      "outputVarId": "VAR_01JWRESULT",
      "readOnly": true
    }
  ]
}
```

#### step_trigger + step_trigger_action

| Trigger | Type | Widget |
|---|---|---|
| `trigger_01JWB4T1` — preview_release | `on_load` | null |
| `trigger_01JWB4T2` — confirm_release | `button_click` | widget_01JWB4W2 |

→ trigger_01JWB4T1 actions: `actdef_preview_release` (order 1)
→ trigger_01JWB4T2 actions: `actdef_confirm_release` (order 1)

#### action_definition — `actdef_preview_release`

```json
{
  "service":  "release-service",
  "endpoint": "/releases/preview",
  "method":   "POST",
  "parameters": [
    { "name": "coupon_id",   "varId": "VAR_01JWGHI",       "in": "body", "type": "string", "required": true },
    { "name": "segment_ids", "varId": "VAR_01JWSEGMENTS",  "in": "body", "type": "array",  "required": true },
    { "name": "total_users", "varId": "VAR_01JWUSERCOUNT", "in": "body", "type": "number", "required": false }
  ],
  "output": [
    { "field": "preview", "varId": "VAR_01JWPREVIEW", "type": "object" }
  ]
}
```

#### action_definition — `actdef_confirm_release`

```json
{
  "service":  "release-service",
  "endpoint": "/releases",
  "method":   "POST",
  "parameters": [
    { "name": "coupon_id",   "varId": "VAR_01JWGHI",      "in": "body", "type": "string", "required": true },
    { "name": "segment_ids", "varId": "VAR_01JWSEGMENTS", "in": "body", "type": "array",  "required": true }
  ],
  "output": [
    { "field": "result", "varId": "VAR_01JWRESULT", "type": "object" }
  ]
}
```

---

## Full Variable Data Flow — End to End

```
Step 1
  [text_input] VAR_01JWSEARCH ──▶ actdef_search_campaigns ──▶ table results
                                                               user chọn row ──▶ VAR_01JWCAMPAIGNID ✓

Step 2
  VAR_01JWCAMPAIGNID (từ Step 1) ──────────────────────────────────────────────────────────────────┐
  [text_input]   VAR_01JWXYZ    ──┐                                                                  │
  [number_input] VAR_01JWABC   ──┼──▶ actdef_create_coupon ──▶ VAR_01JWGHI ✓                       │
  [date_picker]  VAR_01JWEXPIRY ──┘                                                                  │
                                                                                                    │
Step 3                                                                                              │
  on_load ──▶ actdef_get_segments ──▶ VAR_01JWSEGLIST (options cho multi_select)                   │
  [multi_select] VAR_01JWSEGMENTS ──▶ actdef_estimate_targets ──▶ VAR_01JWUSERCOUNT ✓             │
                                                                                                    │
Step 4                                                                                              │
  VAR_01JWGHI (từ Step 2) ◀──────────────────────────────────────────────────────────────────────────┘
  VAR_01JWSEGMENTS (từ Step 3)
  VAR_01JWUSERCOUNT (từ Step 3)
      │
      ▼ on_load
  actdef_preview_release ──▶ VAR_01JWPREVIEW (json_viewer)
      │
      ▼ button_click [Confirm & Release]
  actdef_confirm_release ──▶ VAR_01JWRESULT ✓ (Done)
```

---

## Database Tables

| Table | Column | Type | Nội dung |
|---|---|---|---|
| `step_definition` | `ui` | JSONB | Widget layout — elements với `outputVarId: VAR_ULID` |
| `step_trigger` | — | relational | Trigger config (type, widgetId) |
| `step_trigger_action` | — | junction | Trigger → action mapping + execution order |
| `action_definition` | `parameters` | JSONB | Endpoint, method, param `varId` mapping |
| `action_definition` | `output` | JSONB | Output field → `varId` mapping |
| `scenario_execution` | `context` | JSONB | Snapshot của toàn bộ ContextDataItems |

---

## Design Rules

1. **Variable là hợp đồng** giữa widget `outputVarId` và action parameter `varId` — phải khớp để runtime bind được.
2. **VAR_ULID do frontend tự gen** khi user kéo thả kết nối — không bao giờ collision, không typo.
3. **Action merge context** từ tất cả steps trước + current inputs trước khi bind — varId sau ghi đè varId trước nếu trùng.
4. **`on_load` trigger** chạy tự động khi Step render — dùng để prefetch options hoặc preview.
5. **`on_change` trigger** chạy khi widget thay đổi — dùng cho realtime estimate/validation.
6. **Button không chứa logic** — chỉ reference `triggerId`, logic nằm trong `step_trigger_action` và `action_definition`.
7. **Mỗi Action execution được idempotency-guard** bằng `SHA256(executionId + actionDefinitionId + sorted required varIds)`.
