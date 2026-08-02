-- KEYS[1] = key
-- ARGV[1] = limit
-- ARGV[2] = ttl 秒数
local limit = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])
local current = tonumber(redis.call('GET', KEYS[1]) or '0')
if current >= limit then
  return -1
end
local newVal = redis.call('INCR', KEYS[1])
if newVal == 1 then
  redis.call('EXPIRE', KEYS[1], ttl)
end
return limit - newVal