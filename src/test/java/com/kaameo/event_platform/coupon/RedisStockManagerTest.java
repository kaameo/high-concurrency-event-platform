package com.kaameo.event_platform.coupon;

import com.kaameo.event_platform.coupon.service.RedisStockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RedisStockManagerTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private RedisStockManager redisStockManager;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);
        redisTemplate.afterPropertiesSet();
        redisStockManager = new RedisStockManager(redisTemplate);
    }

    @Test
    void initAndGetStock() {
        UUID eventId = UUID.randomUUID();
        redisStockManager.initStock(eventId, 100);
        assertThat(redisStockManager.getRemainingStock(eventId)).isEqualTo(100);
    }

    @Test
    void decrementStock_success() {
        UUID eventId = UUID.randomUUID();
        redisStockManager.initStock(eventId, 1);

        assertThat(redisStockManager.decrementStock(eventId)).isTrue();
        assertThat(redisStockManager.getRemainingStock(eventId)).isEqualTo(0);
    }

    @Test
    void decrementStock_soldOut_returnsFalse() {
        UUID eventId = UUID.randomUUID();
        redisStockManager.initStock(eventId, 0);

        assertThat(redisStockManager.decrementStock(eventId)).isFalse();
        assertThat(redisStockManager.getRemainingStock(eventId)).isEqualTo(0);
    }

    @Test
    void decrementStock_concurrent_noOversell() throws InterruptedException {
        UUID eventId = UUID.randomUUID();
        int totalStock = 100;
        int totalRequests = 200;
        redisStockManager.initStock(eventId, totalStock);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    if (redisStockManager.decrementStock(eventId)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertThat(successCount.get()).isEqualTo(totalStock);
        assertThat(redisStockManager.getRemainingStock(eventId)).isEqualTo(0);
    }
}
