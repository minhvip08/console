# SQL — Hướng dẫn & Interview Q&A

> Database: PostgreSQL 15+. Ví dụ lấy từ schema thực tế của project (action_execution, trigger_execution, scenario_execution...).

---

## 1. Nền tảng — Thứ tự thực thi SQL

Nhiều người học SQL theo thứ tự *viết*, nhưng database thực thi theo thứ tự **khác**:

```
Thứ tự VIẾT:        SELECT → FROM → WHERE → GROUP BY → HAVING → ORDER BY → LIMIT
Thứ tự THỰC THI:    FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → LIMIT
```

> **Tại sao quan trọng?** Vì `WHERE` không dùng được alias từ `SELECT` (chưa tính), nhưng `HAVING` thì dùng được aggregate.

---

## 2. SELECT cơ bản

### 2.1 Cú pháp

```sql
SELECT column1, column2
FROM   table_name
WHERE  condition
ORDER BY column1 ASC/DESC
LIMIT  10;
```

### 2.2 Lọc dữ liệu — WHERE

```sql
-- So sánh
WHERE status = 'COMPLETED'
WHERE created_at >= '2024-01-01'
WHERE amount BETWEEN 100 AND 500

-- Nhiều điều kiện
WHERE status = 'FAILED' AND retry_count > 3

-- Danh sách giá trị
WHERE status IN ('FAILED', 'TIMEOUT')
WHERE status NOT IN ('COMPLETED', 'COMPENSATED')

-- Text matching
WHERE error_message LIKE '%timeout%'         -- chứa 'timeout'
WHERE error_message ILIKE '%Timeout%'        -- case-insensitive (PostgreSQL)
WHERE error_message LIKE 'Connection%'       -- bắt đầu bằng 'Connection'

-- NULL check
WHERE completed_at IS NULL                   -- chưa hoàn thành
WHERE s3_ref IS NOT NULL                     -- có claim-check S3
```

### 2.3 Ví dụ thực tế — Query action đang lỗi

```sql
SELECT id,
       scenario_execution_id,
       status,
       error_message,
       started_at,
       completed_at
FROM   action_execution
WHERE  status IN ('FAILED', 'TIMEOUT')
  AND  created_at >= NOW() - INTERVAL '24 hours'
ORDER  BY created_at DESC
LIMIT  50;
```

---

## 3. JOIN — Kết hợp bảng

### 3.1 Các loại JOIN

```
TableA     TableB
  ┌──┐      ┌──┐
  │  │──────│  │
  └──┘      └──┘

INNER JOIN : chỉ lấy row khớp cả 2 bên
LEFT JOIN  : tất cả từ A, khớp từ B (NULL nếu không có)
RIGHT JOIN : tất cả từ B, khớp từ A (NULL nếu không có)
FULL JOIN  : tất cả từ cả 2, NULL khi không khớp
CROSS JOIN : tích Cartesian (A × B rows)
```

### 3.2 INNER JOIN

```sql
-- Lấy thông tin đầy đủ của action execution
SELECT ae.id,
       se.status           AS scenario_status,
       ad.name             AS action_name,
       ae.status           AS execution_status,
       ae.started_at
FROM   action_execution  ae
INNER JOIN scenario_execution se ON ae.scenario_execution_id = se.id
INNER JOIN action_definition  ad ON ae.action_definition_id  = ad.id
WHERE  se.status = 'RUNNING';
```

### 3.3 LEFT JOIN — Tìm row "mồ côi"

```sql
-- Tìm scenario execution không có action nào (outlier / bug)
SELECT se.id,
       se.status,
       se.created_at,
       ae.id AS action_execution_id
FROM   scenario_execution se
LEFT JOIN action_execution ae ON ae.scenario_execution_id = se.id
WHERE  ae.id IS NULL;           -- NULL ở LEFT JOIN = không có cặp
```

### 3.4 JOIN nhiều bảng

```sql
-- Saga compensation log đầy đủ context
SELECT scl.id,
       se.id                AS scenario_exec_id,
       ad.name              AS compensate_action,
       scl.step_order,
       scl.status           AS compensation_status,
       scl.context_snapshot
FROM   saga_compensation_log scl
INNER JOIN scenario_execution se ON scl.scenario_execution_id = se.id
INNER JOIN action_definition  ad ON scl.action_definition_id  = ad.id
WHERE  scl.status IN ('PENDING', 'COMPENSATING')
ORDER  BY scl.step_order DESC;
```

---

## 4. GROUP BY & Aggregate

### 4.1 Các hàm aggregate

| Hàm | Ý nghĩa |
|---|---|
| `COUNT(*)` | Đếm tất cả row |
| `COUNT(col)` | Đếm row có giá trị khác NULL |
| `COUNT(DISTINCT col)` | Đếm giá trị distinct |
| `SUM(col)` | Tổng |
| `AVG(col)` | Trung bình |
| `MAX(col)` | Lớn nhất |
| `MIN(col)` | Nhỏ nhất |

### 4.2 GROUP BY

```sql
-- Thống kê action execution theo status
SELECT status,
       COUNT(*)                          AS total,
       COUNT(DISTINCT scenario_execution_id) AS affected_scenarios
FROM   action_execution
WHERE  created_at >= CURRENT_DATE
GROUP  BY status
ORDER  BY total DESC;
```

Kết quả:
```
status      | total | affected_scenarios
------------|-------|-------------------
COMPLETED   |  1842 |  312
FAILED      |    47 |   23
PROCESSING  |    12 |   10
TIMEOUT     |     5 |    5
```

### 4.3 HAVING — Lọc SAU khi GROUP

```sql
-- Tìm scenario execution có nhiều action FAILED (> 2)
SELECT scenario_execution_id,
       COUNT(*) AS failed_count
FROM   action_execution
WHERE  status = 'FAILED'
GROUP  BY scenario_execution_id
HAVING COUNT(*) > 2           -- lọc AGGREGATE, không phải raw row
ORDER  BY failed_count DESC;
```

> `WHERE` lọc trước GROUP BY (từng row). `HAVING` lọc sau GROUP BY (từng nhóm).

---

## 5. Subquery & CTE

### 5.1 Subquery

```sql
-- Tìm action_execution có thời gian chạy > average
SELECT id, scenario_execution_id, started_at, completed_at
FROM   action_execution
WHERE  EXTRACT(EPOCH FROM (completed_at - started_at))
       > (
           SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at)))
           FROM   action_execution
           WHERE  status = 'COMPLETED'
             AND  completed_at IS NOT NULL
         )
  AND  status = 'COMPLETED';
```

### 5.2 CTE — Common Table Expression (WITH)

CTE = "bảng tạm được đặt tên", code dễ đọc hơn subquery lồng nhau.

```sql
-- Tính tỉ lệ thành công của mỗi scenario definition
WITH execution_stats AS (
    SELECT sd.id          AS scenario_def_id,
           sd.name        AS scenario_name,
           COUNT(*)       AS total_runs,
           SUM(CASE WHEN se.status = 'COMPLETED' THEN 1 ELSE 0 END) AS success_count
    FROM   scenario_definition sd
    INNER JOIN scenario_execution se ON se.scenario_definition_id = sd.id
    WHERE  se.created_at >= NOW() - INTERVAL '7 days'
    GROUP  BY sd.id, sd.name
)
SELECT scenario_def_id,
       scenario_name,
       total_runs,
       success_count,
       ROUND(success_count * 100.0 / NULLIF(total_runs, 0), 2) AS success_rate_pct
FROM   execution_stats
WHERE  total_runs >= 10           -- chỉ scenario có đủ data
ORDER  BY success_rate_pct ASC;   -- xem scenario hay lỗi nhất
```

### 5.3 CTE đệ quy (RECURSIVE)

Dùng cho cấu trúc tree/hierarchy (ví dụ: org chart, category tree).

```sql
-- Ví dụ: duyệt category tree
WITH RECURSIVE category_tree AS (
    -- Base case: root node
    SELECT id, name, parent_id, 0 AS depth, name::TEXT AS path
    FROM   categories
    WHERE  parent_id IS NULL

    UNION ALL

    -- Recursive case: con của node đã tìm được
    SELECT c.id, c.name, c.parent_id,
           ct.depth + 1,
           ct.path || ' > ' || c.name
    FROM   categories c
    INNER JOIN category_tree ct ON c.parent_id = ct.id
)
SELECT * FROM category_tree ORDER BY path;
```

---

## 6. Window Functions — Hàm phân tích nâng cao

Window function tính toán trên **một tập row liên quan** mà **không gộp (collapse) chúng** như GROUP BY.

```
FUNCTION() OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE ...)
                 └─────────────┘  └──────────┘  └───────────┘
                   Chia nhóm      Sắp xếp trong  Kích thước frame
                   (optional)     nhóm (optional)  (optional)
```

### 6.1 ROW_NUMBER, RANK, DENSE_RANK

```sql
-- Xếp hạng action execution trong mỗi scenario theo thời gian bắt đầu
SELECT id,
       scenario_execution_id,
       action_definition_id,
       started_at,
       ROW_NUMBER() OVER (
           PARTITION BY scenario_execution_id
           ORDER BY started_at
       ) AS step_seq
FROM   action_execution
WHERE  status = 'COMPLETED';
```

| Hàm | Tie (bằng nhau) | Khoảng |
|---|---|---|
| `ROW_NUMBER()` | Không — số khác nhau mọi lúc | Không có khoảng |
| `RANK()` | Cùng rank, bỏ khoảng tiếp theo | Có khoảng (1,1,3) |
| `DENSE_RANK()` | Cùng rank, không bỏ khoảng | Không có khoảng (1,1,2) |

### 6.2 LAG / LEAD — Lấy giá trị row trước/sau

```sql
-- Tính thời gian giữa các step trong cùng scenario
SELECT id,
       scenario_execution_id,
       started_at,
       LAG(started_at) OVER (
           PARTITION BY scenario_execution_id
           ORDER BY started_at
       ) AS prev_step_started_at,
       started_at - LAG(started_at) OVER (
           PARTITION BY scenario_execution_id
           ORDER BY started_at
       ) AS gap_between_steps
FROM   action_execution
ORDER  BY scenario_execution_id, started_at;
```

### 6.3 SUM / AVG running total

```sql
-- Running total số action execution theo ngày
SELECT DATE(created_at)                            AS exec_date,
       COUNT(*)                                    AS daily_count,
       SUM(COUNT(*)) OVER (ORDER BY DATE(created_at)) AS running_total
FROM   action_execution
GROUP  BY DATE(created_at)
ORDER  BY exec_date;
```

### 6.4 FIRST_VALUE / LAST_VALUE

```sql
-- Lần đầu và lần cuối mỗi action definition được thực thi
SELECT DISTINCT action_definition_id,
       FIRST_VALUE(started_at) OVER (
           PARTITION BY action_definition_id
           ORDER BY started_at
           ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
       ) AS first_run,
       LAST_VALUE(started_at)  OVER (
           PARTITION BY action_definition_id
           ORDER BY started_at
           ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
       ) AS last_run
FROM   action_execution
WHERE  status = 'COMPLETED';
```

---

## 7. Index — Khi nào cần, khi nào không

### 7.1 Các loại index

```sql
-- B-Tree (mặc định) — range query, equality, ORDER BY
CREATE INDEX idx_ae_status ON action_execution(status);
CREATE INDEX idx_ae_created_at ON action_execution(created_at DESC);

-- Composite index — thứ tự cột quan trọng
CREATE INDEX idx_ae_scenario_status
    ON action_execution(scenario_execution_id, status);

-- Partial index — chỉ index một subset row (nhỏ hơn, nhanh hơn)
CREATE INDEX idx_ae_pending
    ON action_execution(created_at)
    WHERE status IN ('PENDING', 'PROCESSING');

-- UNIQUE index
CREATE UNIQUE INDEX idx_ae_idempotency
    ON action_execution(idempotency_key);

-- GIN index — dùng cho JSONB
CREATE INDEX idx_ae_response ON action_execution USING GIN(response_snapshot);
```

### 7.2 EXPLAIN ANALYZE — đọc query plan

```sql
EXPLAIN ANALYZE
SELECT * FROM action_execution
WHERE  status = 'FAILED'
  AND  created_at >= NOW() - INTERVAL '1 hour';
```

Đầu ra cần nhìn:
```
Seq Scan      → đọc toàn bảng (BAD nếu bảng lớn)
Index Scan    → dùng index (GOOD)
Bitmap Scan   → kết hợp nhiều index (OK)

actual rows=47    → bao nhiêu row thực tế
loops=1           → thực thi bao nhiêu lần
actual time=0.042 → milliseconds
```

### 7.3 Index không giúp được khi

```sql
-- ❌ Function trên indexed column → full scan
WHERE LOWER(email) = 'test@example.com'
-- ✅ Fix: functional index
CREATE INDEX idx_user_email_lower ON users(LOWER(email));

-- ❌ LIKE với wildcard đầu
WHERE name LIKE '%console%'
-- ✅ Fix: full-text search hoặc GIN trigram index

-- ❌ OR trên nhiều cột khác nhau (thường tệ)
WHERE status = 'FAILED' OR scenario_execution_id = 123
-- ✅ Fix: UNION ALL
```

---

## 8. Transaction & Isolation

### 8.1 ACID

| | Ý nghĩa |
|---|---|
| **Atomicity** | All or nothing — commit hết hoặc rollback hết |
| **Consistency** | DB luôn ở trạng thái hợp lệ theo constraint |
| **Isolation** | Transaction không thấy uncommitted data của transaction khác |
| **Durability** | Sau commit, data tồn tại dù crash |

### 8.2 Isolation levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read |
|---|---|---|---|
| `READ UNCOMMITTED` | Có thể | Có thể | Có thể |
| `READ COMMITTED` (PG default) | Không | Có thể | Có thể |
| `REPEATABLE READ` | Không | Không | Có thể |
| `SERIALIZABLE` | Không | Không | Không |

```sql
BEGIN;
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;

SELECT * FROM action_execution WHERE id = 42;
-- ... làm gì đó ...
UPDATE action_execution SET status = 'PROCESSING' WHERE id = 42;

COMMIT;
-- hoặc ROLLBACK;
```

### 8.3 SELECT FOR UPDATE — Pessimistic lock

```sql
BEGIN;

-- Lock row để không transaction khác update cùng lúc
SELECT id, status
FROM   action_execution
WHERE  id = 42
  AND  status = 'PENDING'
FOR UPDATE;

-- Nếu có row → update an toàn
UPDATE action_execution
SET    status = 'PROCESSING', started_at = NOW()
WHERE  id = 42;

COMMIT;
```

> `FOR UPDATE SKIP LOCKED` — bỏ qua row đang bị lock, dùng cho job queue.

---

## 9. Query thực chiến hay gặp trong phỏng vấn

### 9.1 Tìm duplicate

```sql
-- Tìm idempotency_key bị duplicate (không nên xảy ra, nhưng debug)
SELECT idempotency_key, COUNT(*) AS cnt
FROM   action_execution
GROUP  BY idempotency_key
HAVING COUNT(*) > 1;
```

### 9.2 Top-N per group

```sql
-- 3 action execution gần nhất của mỗi scenario
WITH ranked AS (
    SELECT *,
           ROW_NUMBER() OVER (
               PARTITION BY scenario_execution_id
               ORDER BY started_at DESC
           ) AS rn
    FROM   action_execution
)
SELECT * FROM ranked WHERE rn <= 3;
```

### 9.3 Gap detection — Tìm khoảng trống số liên tiếp

```sql
-- Tìm ID bị thiếu trong dãy
SELECT s.id + 1 AS missing_from,
       MIN(n.id) - 1 AS missing_to
FROM   action_execution s
INNER JOIN action_execution n ON n.id > s.id
WHERE  NOT EXISTS (
    SELECT 1 FROM action_execution m WHERE m.id = s.id + 1
)
GROUP  BY s.id
ORDER  BY s.id;
```

### 9.4 Pivot — chuyển row thành column

```sql
-- Đếm action theo status, mỗi ngày = 1 row
SELECT DATE(created_at) AS exec_date,
       SUM(CASE WHEN status = 'COMPLETED'  THEN 1 ELSE 0 END) AS completed,
       SUM(CASE WHEN status = 'FAILED'     THEN 1 ELSE 0 END) AS failed,
       SUM(CASE WHEN status = 'TIMEOUT'    THEN 1 ELSE 0 END) AS timeout,
       SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END) AS processing
FROM   action_execution
WHERE  created_at >= CURRENT_DATE - INTERVAL '7 days'
GROUP  BY DATE(created_at)
ORDER  BY exec_date;
```

### 9.5 Delete duplicate, giữ 1 row

```sql
-- Xóa duplicate, giữ row có ID nhỏ nhất
DELETE FROM action_execution
WHERE  id NOT IN (
    SELECT MIN(id)
    FROM   action_execution
    GROUP  BY idempotency_key
);

-- Cách hiệu quả hơn với CTE
WITH duplicates AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY idempotency_key
               ORDER BY id
           ) AS rn
    FROM   action_execution
)
DELETE FROM action_execution
WHERE  id IN (SELECT id FROM duplicates WHERE rn > 1);
```

### 9.6 Update từ bảng khác

```sql
-- Update scenario_execution.status dựa trên action_execution
UPDATE scenario_execution se
SET    status = 'FAILED',
       updated_at = NOW()
FROM   (
    SELECT DISTINCT scenario_execution_id
    FROM   action_execution
    WHERE  status = 'FAILED'
      AND  created_at >= NOW() - INTERVAL '5 minutes'
) failed_actions
WHERE  se.id = failed_actions.scenario_execution_id
  AND  se.status = 'RUNNING';
```

---

## 10. JSONB — PostgreSQL specific

Project dùng JSONB cho `response_snapshot`, `context_snapshot`, `parameters`.

```sql
-- Lấy field từ JSONB
SELECT id,
       response_snapshot->>'status'        AS api_status,   -- text
       response_snapshot->'data'           AS data_json,    -- jsonb
       response_snapshot->'data'->>'id'    AS data_id       -- text nested

FROM   action_execution
WHERE  response_snapshot IS NOT NULL;

-- Lọc theo JSONB field
WHERE  response_snapshot->>'status' = '200'

-- Check key tồn tại
WHERE  response_snapshot ? 'errorCode'

-- GIN index để query nhanh
CREATE INDEX idx_ae_response_gin ON action_execution USING GIN(response_snapshot);
-- Query dùng @> (contains)
WHERE  response_snapshot @> '{"status": "200"}'::jsonb
```

---

## 11. Interview Q&A

---

**Q: Sự khác nhau giữa WHERE và HAVING?**

> - `WHERE` lọc **row đơn lẻ** trước khi GROUP BY — không dùng được aggregate.
> - `HAVING` lọc **nhóm** sau khi GROUP BY — dùng được aggregate function.
>
> ```sql
> -- ❌ Sai: WHERE không dùng aggregate
> WHERE COUNT(*) > 5
>
> -- ✅ Đúng
> GROUP BY department_id
> HAVING COUNT(*) > 5
> ```

---

**Q: INNER JOIN vs LEFT JOIN — chọn khi nào?**

> - `INNER JOIN`: chỉ lấy row **tồn tại cả 2 bảng**. Dùng khi data phải có ở cả 2.
> - `LEFT JOIN`: lấy **tất cả từ bảng trái**, bảng phải NULL nếu không có. Dùng khi muốn giữ row dù không có cặp, hoặc tìm row "mồ côi" (`WHERE right.id IS NULL`).
>
> Sai lầm phổ biến: dùng `WHERE right.col = 'x'` sau LEFT JOIN → vô tình biến thành INNER JOIN.

---

**Q: Index là gì? Khi nào thêm index?**

> Index = cấu trúc dữ liệu phụ (thường B-Tree) giúp tìm kiếm nhanh mà không cần đọc toàn bảng.
>
> **Nên index:**
> - Cột trong `WHERE`, `JOIN ON`, `ORDER BY` thường xuyên.
> - Cột có cardinality cao (nhiều giá trị distinct).
> - FK column (PostgreSQL không tự tạo index cho FK).
>
> **Không nên index mọi thứ:**
> - Bảng nhỏ (< vài nghìn row) — full scan nhanh hơn.
> - Cột thay đổi liên tục (index cần update mỗi lần write).
> - Query ít chạy.
>
> Kiểm tra: `EXPLAIN ANALYZE` xem có `Index Scan` hay `Seq Scan`.

---

**Q: Giải thích N+1 query problem?**

> Xảy ra khi code chạy 1 query lấy danh sách N item, sau đó chạy thêm 1 query **cho mỗi item** → tổng N+1 queries.
>
> ```java
> // ❌ N+1: 1 query lấy scenarios + N query lấy actions của mỗi scenario
> List<Scenario> scenarios = scenarioRepo.findAll();        // 1 query
> for (Scenario s : scenarios) {
>     List<Action> actions = actionRepo.findByScenarioId(s.getId()); // N queries
> }
>
> // ✅ Fix: JOIN trong 1 query hoặc dùng @EntityGraph / fetch join
> SELECT s.*, ae.*
> FROM scenario_execution s
> INNER JOIN action_execution ae ON ae.scenario_execution_id = s.id;
> ```

---

**Q: Difference giữa UNION và UNION ALL?**

> - `UNION`: loại bỏ duplicate → tốn thêm thời gian sort/compare.
> - `UNION ALL`: giữ tất cả, kể cả duplicate → nhanh hơn.
>
> Dùng `UNION ALL` khi biết chắc không có duplicate hoặc không cần deduplicate.
>
> ```sql
> -- Lấy tất cả execution ID có vấn đề (UNION ALL nếu execId không thể trùng)
> SELECT scenario_execution_id FROM action_execution WHERE status = 'FAILED'
> UNION ALL
> SELECT scenario_execution_id FROM action_execution WHERE status = 'TIMEOUT';
> ```

---

**Q: Giải thích transaction isolation level?**

> **READ COMMITTED** (PostgreSQL default): Mỗi statement đọc snapshot tại thời điểm statement đó chạy. Tránh dirty read nhưng non-repeatable read vẫn xảy ra (đọc 2 lần trong cùng transaction có thể ra kết quả khác).
>
> **REPEATABLE READ**: Snapshot cố định từ khi transaction bắt đầu. Mọi SELECT trong transaction đọc cùng 1 snapshot. Phantom read vẫn có thể ở SQL chuẩn (PostgreSQL thực tế không có phantom read ở level này).
>
> **SERIALIZABLE**: Giao dịch chạy như sequential. An toàn nhất nhưng overhead cao nhất — có thể bị serialization failure.
>
> Dự án này dùng **DB First 2PC**: PostgreSQL ACID là source of truth; Redis chỉ là cache, rebuild được từ DB.

---

**Q: Tối ưu query chậm — làm gì đầu tiên?**

> 1. `EXPLAIN ANALYZE` — xem query plan, tìm `Seq Scan` trên bảng lớn.
> 2. Kiểm tra index có tồn tại không, có được dùng không.
> 3. Check cardinality — index trên cột ít distinct (boolean, status vài giá trị) thường không hiệu quả.
> 4. Xem có function wrap trên indexed column không (`WHERE UPPER(name) = ...`).
> 5. Check `pg_stat_statements` để tìm top slow queries.
> 6. Xem `autovacuum` có chạy không — stale statistics gây query plan sai.

---

**Q: Clustered index là gì? PostgreSQL có không?**

> **Clustered index** (SQL Server / MySQL InnoDB): Data trên disk được lưu theo thứ tự của index đó — chỉ có 1 clustered index per table. Range scan rất nhanh vì data gần nhau.
>
> **PostgreSQL không có clustered index tự động**. PostgreSQL có lệnh `CLUSTER table USING index` để reorder data một lần, nhưng sau đó insert/update mới sẽ không maintain thứ tự.
>
> PostgreSQL dùng MVCC — data heap riêng, index riêng (heap-based storage). Tương đương closest: `BRIN index` cho dữ liệu tự nhiên có thứ tự (timestamp, sequential ID).

---

**Q: CTE có tốt hơn subquery không?**

> Phụ thuộc:
> - **PostgreSQL 12+**: CTE không còn "optimization fence" — optimizer có thể push condition vào CTE. Hiệu năng tương đương subquery.
> - **PostgreSQL < 12**: CTE là optimization fence — được vật liệu hóa (materialized) một lần, optimizer không push WHERE vào. Subquery thường nhanh hơn.
> - **CTE RECURSIVE**: Trường hợp duy nhất buộc phải dùng CTE.
> - **Readable code**: CTE thắng rõ khi logic phức tạp nhiều bước.
>
> Dùng `WITH ... AS MATERIALIZED` hoặc `AS NOT MATERIALIZED` để control explicit (PostgreSQL 12+).

---

**Q: Kể tên các loại JOIN và giải thích CROSS JOIN?**

> `INNER JOIN`, `LEFT JOIN`, `RIGHT JOIN`, `FULL OUTER JOIN`, `CROSS JOIN`, `SELF JOIN`.
>
> `CROSS JOIN` = tích Cartesian: mỗi row của A kết hợp với mỗi row của B.
> 10 row × 10 row = 100 row kết quả. Không có điều kiện ON.
>
> Dùng khi nào: tạo combination (e.g., mọi combination ngày × sản phẩm cho report), seed data, generate range.
>
> ```sql
> SELECT a.name, b.name
> FROM   team_a a CROSS JOIN team_b b;
> ```

---

## 12. Checklist trước phỏng vấn

```
☐ Hiểu thứ tự thực thi SQL (FROM → WHERE → GROUP BY → HAVING → SELECT → ORDER BY)
☐ Viết được 4 loại JOIN và giải thích khi nào dùng từng loại
☐ Phân biệt WHERE vs HAVING với ví dụ cụ thể
☐ Viết được Window Function: ROW_NUMBER, RANK, LAG/LEAD, running total
☐ Viết được CTE đơn và giải thích tại sao dùng thay subquery
☐ Giải thích được ACID và isolation levels
☐ Giải thích N+1 query problem và cách fix
☐ Đọc được EXPLAIN ANALYZE output cơ bản
☐ Biết khi nào thêm index, khi nào không
☐ Viết được: top-N per group, delete duplicate, pivot với CASE WHEN
```

---

*Tài liệu liên quan: `docs/08-database.md` — schema chi tiết của project.*
