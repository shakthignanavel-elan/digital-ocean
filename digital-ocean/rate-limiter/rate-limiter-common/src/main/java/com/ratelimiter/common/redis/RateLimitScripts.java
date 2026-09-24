package com.ratelimiter.common.redis;

/**
 * Shared Redis Lua scripts.
 * <p>
 * Dataplane evaluate is ARGV-driven (algorithms come from Java after reading config).
 * Controlplane quota read is config-key-driven for observability.
 */
public final class RateLimitScripts {

    private RateLimitScripts() {
    }

    /**
     * Atomically evaluate burstable + sustained in <b>one</b> Redis round-trip.
     * <p>
     * KEYS[1]=burstable state, KEYS[2]=sustained state<br>
     * ARGV: now, cost,
     * burstableAlg, burstableCapacity, burstableWindow,
     * sustainedAlg, sustainedCapacity, sustainedWindow
     */
    public static final String EVALUATE_DUAL = """
            local function apply(stateKey, algorithm, capacity, window, now)
              local tokens = tonumber(redis.call('HGET', stateKey, 'tokens'))
              local resetAt = tonumber(redis.call('HGET', stateKey, 'resetAt') or '0')
              local lastRefill = tonumber(redis.call('HGET', stateKey, 'lastRefill') or '0')
              local eventsKey = stateKey .. ':events'

              if tokens == nil then
                tokens = capacity
                lastRefill = now
                resetAt = now + window
              end

              if algorithm == 'TOKEN_BUCKET' and window > 0 and capacity > 0 then
                local elapsed = math.max(0, now - lastRefill)
                local refill = math.floor(elapsed * (capacity / window))
                if refill > 0 then
                  tokens = math.min(capacity, tokens + refill)
                  lastRefill = now
                end
                tokens = math.min(tokens, capacity)
                resetAt = now + math.ceil(((capacity - tokens) * window) / math.max(capacity, 1))
              elseif algorithm == 'FIXED_WINDOW' and window > 0 then
                if now >= resetAt then
                  tokens = capacity
                  resetAt = now + window
                  lastRefill = now
                end
                tokens = math.min(tokens, capacity)
              elseif algorithm == 'SLIDING_WINDOW' and window > 0 then
                redis.call('ZREMRANGEBYSCORE', eventsKey, 0, now - window)
                local used = redis.call('ZCARD', eventsKey)
                tokens = math.max(0, capacity - used)
                local oldest = redis.call('ZRANGE', eventsKey, 0, 0, 'WITHSCORES')
                if oldest[2] then
                  resetAt = math.floor(tonumber(oldest[2]) + window)
                else
                  resetAt = now + window
                end
                lastRefill = now
              end

              return {
                tokens=tokens,
                capacity=capacity,
                resetAt=resetAt,
                algorithm=algorithm,
                window=window,
                lastRefill=lastRefill,
                eventsKey=eventsKey,
                stateKey=stateKey
              }
            end

            local function persist(state)
              redis.call('HSET', state.stateKey,
                'tokens', state.tokens,
                'lastRefill', state.lastRefill,
                'resetAt', state.resetAt)
            end

            local function consume(state, now, cost)
              if state.algorithm == 'SLIDING_WINDOW' then
                for i = 1, cost do
                  local member = tostring(now) .. '-' .. tostring(i) .. '-' .. tostring(redis.call('INCR', state.eventsKey .. ':seq'))
                  redis.call('ZADD', state.eventsKey, now, member)
                end
                state.tokens = state.tokens - cost
                local oldest = redis.call('ZRANGE', state.eventsKey, 0, 0, 'WITHSCORES')
                if oldest[2] then
                  state.resetAt = math.floor(tonumber(oldest[2]) + state.window)
                end
              else
                state.tokens = state.tokens - cost
              end
            end

            local now = tonumber(ARGV[1])
            local cost = tonumber(ARGV[2])
            local burstable = apply(KEYS[1], ARGV[3], tonumber(ARGV[4]), tonumber(ARGV[5]), now)
            local sustained = apply(KEYS[2], ARGV[6], tonumber(ARGV[7]), tonumber(ARGV[8]), now)

            local allowed = burstable.tokens >= cost and sustained.tokens >= cost
            if allowed then
              consume(burstable, now, cost)
              consume(sustained, now, cost)
            end

            persist(burstable)
            persist(sustained)

            return cjson.encode({
              allowed=allowed,
              reason=allowed and 'OK' or 'RATE_LIMITED',
              burstable={
                tokens=burstable.tokens,
                capacity=burstable.capacity,
                resetAt=burstable.resetAt,
                consumed=burstable.capacity - burstable.tokens,
                algorithm=burstable.algorithm
              },
              sustained={
                tokens=sustained.tokens,
                capacity=sustained.capacity,
                resetAt=sustained.resetAt,
                consumed=sustained.capacity - sustained.tokens,
                algorithm=sustained.algorithm
              }
            })
            """;

    /** @deprecated use {@link #EVALUATE_DUAL}; kept name alias for older references */
    public static final String EVALUATE = EVALUATE_DUAL;

    /**
     * Read quota for one bucket using namespace/tenant configuration as source of truth.
     * KEYS[1]=config JSON key, KEYS[2]=state key.
     * ARGV[1]=nowSeconds, ARGV[2]=bucket selector: "burstable" or "sustained".
     */
    public static final String QUOTA_READ = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then
              return cjson.encode({exists=false})
            end

            local cfg = cjson.decode(raw)
            local selector = ARGV[2]
            local rl
            if selector == 'burstable' then
              rl = cfg['burstableRateLimitConfig']
            else
              rl = cfg['fixedRateLimitConfig']
            end
            if not rl then
              return cjson.encode({exists=false})
            end

            local algorithm = rl['rateLimitAlgorithm']
            local capacity = tonumber(rl['availableToken'])
            local window = tonumber(rl['timeWindowInSeconds'])
            local now = tonumber(ARGV[1])
            local stateKey = KEYS[2]
            local eventsKey = stateKey .. ':events'

            local tokens = tonumber(redis.call('HGET', stateKey, 'tokens'))
            local resetAt = tonumber(redis.call('HGET', stateKey, 'resetAt') or '0')
            local lastRefill = tonumber(redis.call('HGET', stateKey, 'lastRefill') or '0')

            if tokens == nil then
              tokens = capacity
              lastRefill = now
              resetAt = now + window
            end

            if algorithm == 'TOKEN_BUCKET' and window > 0 and capacity > 0 then
              local elapsed = math.max(0, now - lastRefill)
              local refill = math.floor(elapsed * (capacity / window))
              if refill > 0 then
                tokens = math.min(capacity, tokens + refill)
                lastRefill = now
              end
              tokens = math.min(tokens, capacity)
              resetAt = now + math.ceil(((capacity - tokens) * window) / math.max(capacity, 1))
            elseif algorithm == 'FIXED_WINDOW' and window > 0 then
              if now >= resetAt then
                tokens = capacity
                resetAt = now + window
                lastRefill = now
              end
              tokens = math.min(tokens, capacity)
            elseif algorithm == 'SLIDING_WINDOW' and window > 0 then
              redis.call('ZREMRANGEBYSCORE', eventsKey, 0, now - window)
              local used = redis.call('ZCARD', eventsKey)
              tokens = math.max(0, capacity - used)
              local oldest = redis.call('ZRANGE', eventsKey, 0, 0, 'WITHSCORES')
              if oldest[2] then
                resetAt = math.floor(tonumber(oldest[2]) + window)
              else
                resetAt = now + window
              end
              lastRefill = now
            end

            return cjson.encode({
              exists=true,
              tokens=tokens,
              capacity=capacity,
              resetAt=resetAt,
              consumed=capacity - tokens,
              algorithm=algorithm,
              windowSeconds=window,
              lastRefill=lastRefill
            })
            """;
}
