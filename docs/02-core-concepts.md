# Core Concepts — Scenario / Step / UI / Action

## Hierarchy

```
Scenario
  └── Step[]
        ├── UI Elements[]     (JSONB) — widget layout, mỗi widget output ra Variable
        └── Action[]          (JSONB) — endpoint + parameter binding qua Variable
```

Không có tầng **Block** trong schema. Step trực tiếp chứa UI elements và Actions, kết nối với nhau qua **Variable**.

| Concept | Mô tả | Ví dụ |
|---|---|---|
| **Scenario** | Luồng công việc hoàn chỉnh, gồm nhiều Step tuần tự | "Release Coupon Campaign" |
| **Step** | Một bước trong scenario — chứa UI elements và Actions | "Configure Coupon" |
| **Variable** | Đơn vị data truyền giữa UI và Action, ID tự gen từ frontend | `VAR_01JWXYZ` |
| **Action** | Thao tác nguyên tử gọi External API | POST /coupons |

---

## Variable — Kết nối UI và Action

**Variable** thay thế alias. Thay vì dùng string do người tạo tự đặt tên (dễ collision, dễ typo), frontend **tự gen VAR_ULID** khi user kéo thả kết nối widget với action parameter.

```
Widget text_input "Coupon Name"
  outputVarId: "VAR_01JWXYZ"   ← frontend tự gen khi tạo widget
        │
        │ user kéo nối vào action parameter
        ▼
Action create_coupon — parameter "name"
  varId: "VAR_01JWXYZ"         ← cùng ID → runtime bind tự động
```

**Tại sao VAR_ULID tốt hơn alias string:**

| | Alias (cũ) | Variable VAR_ULID (mới) |
|---|---|---|
| Collision | "couponName" của Step 1 và Step 3 trùng nhau | VAR_01JW ≠ VAR_02JW — không bao giờ trùng |
| Typo | "couponName" vs "CouponName" → không match | ID là hệ thống gen, không người nhập |
| Rename | Đổi alias phải sửa cả UI lẫn action | Đổi label widget không ảnh hưởng VAR_ID |
| Explicit | Không rõ widget nào nối với param nào | Kết nối explicit qua ID |

---

## step_definition.ui (JSONB)

Khai báo UI elements. Mỗi widget có `outputVarId` — Variable ID do frontend tự gen.

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

## Step Triggers — logical view

Khai báo trigger: khi nào chạy action, thứ tự nào. Đây là **logical view** dùng để hình dung; DB lưu chuẩn hóa qua `step_trigger` + `step_trigger_action` (xem [08-database.md](08-database.md)) chứ không phải JSONB.

```json
{
  "triggers": [
    {
      "id": "trigger_01JWB2T1",
      "name": "create_coupon",
      "type": "button_click",
      "widgetId": "widget_01JWB2W4",
      "actions": [
        { "actionDefinitionId": "actdef_create_coupon", "order": 1 }
      ]
    },
    {
      "id": "trigger_01JWB3T1",
      "name": "load_campaigns",
      "type": "on_load",
      "widgetId": null,
      "actions": [
        { "actionDefinitionId": "actdef_get_campaigns", "order": 1 }
      ]
    },
    {
      "id": "trigger_01JWB3T2",
      "name": "on_segment_change",
      "type": "on_change",
      "widgetId": "widget_01JWB2W3",
      "actions": [
        { "actionDefinitionId": "actdef_estimate_targets", "order": 1 }
      ]
    }
  ]
}
```

| Trigger type | Khi nào chạy |
|---|---|
| `button_click` | User bấm button có `triggerId` tương ứng |
| `on_load` | Step được render lần đầu (prefetch data) |
| `on_change` | Giá trị của `widgetId` thay đổi |

---

## action_definition.schema (JSONB)

Parameters và output dùng `varId` thay vì `alias`. Runtime bind bằng cách match `varId` trong Execution Context.

```json
{
  "service": "coupon-service",
  "adapter": "http",
  "endpoint": "/coupons",
  "method": "POST",
  "timeout_ms": 10000,
  "retry": {
    "max_attempts": 3,
    "backoff": "exponential",
    "retryable_status": [502, 503, 504]
  },
  "parameters": [
    { "name": "campaign_id", "varId": "VAR_01JWDEF", "in": "body", "type": "string", "required": true },
    { "name": "name",        "varId": "VAR_01JWXYZ", "in": "body", "type": "string", "required": true },
    { "name": "discount",    "varId": "VAR_01JWABC", "in": "body", "type": "number", "required": true }
  ],
  "output": [
    { "field": "coupon_id", "varId": "VAR_01JWGHI", "type": "string" }
  ],
  "compensation": {
    "actionDefinitionId": "actdef_delete_coupon",
    "trigger": "auto",
    "required_var_ids": ["VAR_01JWGHI"]
  }
}
```

---

## Variable Data Flow — End to End

```
Step 1: search_campaigns
  [table widget] outputVarId: VAR_01JWDEF (user chọn row → campaignId)
        │
        │ VAR_01JWDEF lưu vào Execution Context
        ▼
Step 2: create_coupon
  [text_input]   outputVarId: VAR_01JWXYZ  (user nhập coupon name)
  [number_input] outputVarId: VAR_01JWABC  (user nhập discount)
  action create_coupon:
    parameter "campaign_id" ← varId: VAR_01JWDEF  (từ Step 1, auto-resolved)
    parameter "name"        ← varId: VAR_01JWXYZ  (từ widget vừa nhập)
    parameter "discount"    ← varId: VAR_01JWABC  (từ widget vừa nhập)
    output "coupon_id"      → varId: VAR_01JWGHI  (lưu vào context)
        │
        │ VAR_01JWGHI lưu vào Execution Context
        ▼
Step 4: confirm_release
  action confirm_release:
    parameter "coupon_id" ← varId: VAR_01JWGHI  (từ Step 2, auto-resolved)
```

Runtime lookup: tìm variable trong Execution Context theo `varId`, không phụ thuộc tên hay thứ tự.

---

## Adapter types

| Adapter | Use case | Pattern |
|---|---|---|
| `http` | Internal service, external REST API (sync) | Request → Response |
| `async` | Long-running job (batch export, send email blast) | Fire → Poll / Callback |
| `mock` | Testing, staging | - |
