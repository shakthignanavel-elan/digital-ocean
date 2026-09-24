package com.ratelimiter.dataplane.store.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.RedisQuotaKeys;
import com.ratelimiter.common.redis.RateLimitScripts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisDualBucketQuotaStoreTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private DefaultRedisScript<String> evaluateDualScript;

    private RedisDualBucketQuotaStore store;

    @BeforeEach
    void setUp() {
        store = new RedisDualBucketQuotaStore(redisTemplate, new ObjectMapper(), evaluateDualScript);
    }

    @Test
    void singleRedisEvalReceivesBothAlgorithmArgv() {
        when(redisTemplate.execute(eq(evaluateDualScript), anyList(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("""
                        {"allowed":true,"reason":"OK",
                         "burstable":{"tokens":9,"capacity":10,"resetAt":1,"consumed":1,"algorithm":"TOKEN_BUCKET"},
                         "sustained":{"tokens":99,"capacity":100,"resetAt":1,"consumed":1,"algorithm":"SLIDING_WINDOW"}}
                        """);

        var result = store.tryConsume(
                NAMESPACE, TENANT_ID,
                new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, 10, 60),
                new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, 100, 3600),
                1
        );

        assertThat(result.allowed()).isTrue();
        assertThat(result.burstable().getRemainingToken()).isEqualTo(9);

        ArgumentCaptor<Object> argv = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(
                eq(evaluateDualScript),
                eq(List.of(
                        RedisQuotaKeys.burstableKey(NAMESPACE, TENANT_ID),
                        RedisQuotaKeys.sustainedKey(NAMESPACE, TENANT_ID)
                )),
                argv.capture(), argv.capture(), argv.capture(), argv.capture(),
                argv.capture(), argv.capture(), argv.capture(), argv.capture()
        );
        List<Object> args = argv.getAllValues();
        assertThat(args.get(2)).isEqualTo("TOKEN_BUCKET");
        assertThat(args.get(5)).isEqualTo("SLIDING_WINDOW");
        assertThat(RateLimitScripts.EVALUATE_DUAL).contains("ARGV[3]");
    }
}
