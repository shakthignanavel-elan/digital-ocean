package com.ratelimiter.dataplane;

import com.ratelimiter.common.dto.ConfigurationDTO;
import com.ratelimiter.common.dto.EvaluationRequestDTO;
import com.ratelimiter.common.dto.EvaluationResponseDTO;
import com.ratelimiter.common.enums.RateLimitAlgorithm;
import com.ratelimiter.common.model.RateLimitConfig;
import com.ratelimiter.common.model.TokenConfiguration;
import com.ratelimiter.dataplane.support.SharedMemoryStoreTestConfig;
import com.ratelimiter.dataplane.support.SharedMemoryStores;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST end-to-end: publish configuration over HTTP, then hit {@code POST /evaluate}
 * on two independently booted dataplane instances that share quota state
 * (shared-memory stand-in for Redis). Concurrent cross-plane traffic must never
 * oversell capacity.
 */
class DualDataPlaneRestIntegrationTest {

    private static final String NAMESPACE = "payments";
    private static final UUID TENANT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String REDIS_EXCLUDE =
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration";

    private static final ConfigurableApplicationContext PLANE_A = startPlane("dataplane-a");
    private static final ConfigurableApplicationContext PLANE_B = startPlane("dataplane-b");
    private static final String BASE_A = baseUrl(PLANE_A);
    private static final String BASE_B = baseUrl(PLANE_B);
    private static final RestClient HTTP = RestClient.create();

    @AfterAll
    static void tearDown() {
        PLANE_A.close();
        PLANE_B.close();
        SharedMemoryStores.dispose();
    }

    @BeforeEach
    void resetSharedState() {
        SharedMemoryStores.reset();
    }

    @Test
    void configurationPublishedOnceIsVisibleToBothPlanesViaEvaluate() {
        int capacity = 3;
        publishConfiguration(BASE_A, capacity);

        EvaluationResponseDTO fromA = evaluate(BASE_A);
        EvaluationResponseDTO fromB = evaluate(BASE_B);
        EvaluationResponseDTO fromAAgain = evaluate(BASE_A);

        assertThat(fromA.isAllowed()).isTrue();
        assertThat(fromB.isAllowed()).isTrue();
        assertThat(fromAAgain.isAllowed()).isTrue();

        EvaluationResponseDTO denied = evaluate(BASE_B);
        assertThat(denied.isAllowed()).isFalse();
        assertThat(denied.getBurstableTokenAvailability().getCurrentConsumedToken()).isEqualTo(capacity);
    }

    @Test
    void concurrentRequestsAcrossBothPlanesNeverOversellSharedCapacity() throws Exception {
        int capacity = 40;
        publishConfiguration(BASE_B, capacity);

        int workers = 120;
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                String base = (i % 2 == 0) ? BASE_A : BASE_B;
                futures.add(pool.submit(() -> {
                    start.await();
                    if (evaluate(base).isAllowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(capacity);

        EvaluationResponseDTO exhausted = evaluate(BASE_A);
        assertThat(exhausted.isAllowed()).isFalse();
        assertThat(exhausted.getBurstableTokenAvailability().getCurrentConsumedToken()).isEqualTo(capacity);
        assertThat(exhausted.getSustainedTokenAvailability().getCurrentConsumedToken()).isEqualTo(capacity);
    }

    @Test
    void hotReloadViaPutOnOnePlaneAppliesOnTheOther() {
        publishConfiguration(BASE_A, 1);
        assertThat(evaluate(BASE_A).isAllowed()).isTrue();
        assertThat(evaluate(BASE_B).isAllowed()).isFalse();

        ConfigurationDTO raised = sampleConfig(2);
        ResponseEntity<ConfigurationDTO> updated = HTTP.put()
                .uri(BASE_B + "/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body(raised)
                .retrieve()
                .toEntity(ConfigurationDTO.class);
        assertThat(updated.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(evaluate(BASE_A).isAllowed()).isTrue();
        assertThat(evaluate(BASE_B).isAllowed()).isTrue();
        assertThat(evaluate(BASE_A).isAllowed()).isFalse();
    }

    private static void publishConfiguration(String baseUrl, int capacity) {
        ResponseEntity<ConfigurationDTO> created = HTTP.post()
                .uri(baseUrl + "/configuration/{namespace}/{tenantId}", NAMESPACE, TENANT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .body(sampleConfig(capacity))
                .retrieve()
                .toEntity(ConfigurationDTO.class);

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getBurstableRateLimitConfig().getAvailableToken()).isEqualTo(capacity);
    }

    private static EvaluationResponseDTO evaluate(String baseUrl) {
        EvaluationRequestDTO request = new EvaluationRequestDTO(NAMESPACE, TENANT_ID);
        return HTTP.post()
                .uri(baseUrl + "/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EvaluationResponseDTO.class);
    }

    private static ConfigurationDTO sampleConfig(int capacity) {
        ConfigurationDTO config = new ConfigurationDTO();
        config.setNamespace(NAMESPACE);
        config.setTenantId(TENANT_ID);
        config.setBurstableRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.TOKEN_BUCKET, capacity, 3600));
        config.setFixedRateLimitConfig(new RateLimitConfig(RateLimitAlgorithm.SLIDING_WINDOW, capacity, 3600));
        config.setTokenConfiguration(new TokenConfiguration(capacity));
        return config;
    }

    private static ConfigurableApplicationContext startPlane(String applicationName) {
        SpringApplication app = new SpringApplication(DataPlaneApplication.class, SharedMemoryStoreTestConfig.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        Map<String, Object> props = new HashMap<>();
        props.put("server.port", "0");
        props.put("spring.application.name", applicationName);
        props.put("rate-limiter.store", "shared-memory");
        props.put("spring.autoconfigure.exclude", REDIS_EXCLUDE);
        props.put("spring.main.banner-mode", "off");
        app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource(applicationName + "-dual-plane", props)));
        return app.run();
    }

    private static String baseUrl(ConfigurableApplicationContext context) {
        String port = context.getEnvironment().getProperty("local.server.port");
        assertThat(port).isNotBlank();
        return "http://127.0.0.1:" + port;
    }
}
