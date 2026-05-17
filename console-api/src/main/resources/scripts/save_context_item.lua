-- Atomic 3-key save for one ContextDataItem (docs/04 §Lua Script — Atomic Save).
--
-- KEYS[1] = C key (data item)
-- KEYS[2] = A key (step index ZSET)
-- KEYS[3] = B key (varId group Set)
--
-- ARGV[1] = JSON serialized ContextDataItem
-- ARGV[2] = stepOrder (integer as string)
--
-- Cả 3 ghi atomic — không có khả năng partial state (1 key được set còn 2 key kia chưa).
-- Kết hợp với 2PC DB-First ở Java side: nếu Redis fail → DB rollback (docs/04 §2PC).

local dataKey   = KEYS[1]
local stepKey   = KEYS[2]
local varKey    = KEYS[3]
local value     = ARGV[1]
local stepOrder = tonumber(ARGV[2])

redis.call('SET',  dataKey, value)
redis.call('ZADD', stepKey, stepOrder, dataKey)
redis.call('SADD', varKey,  dataKey)
return 1
