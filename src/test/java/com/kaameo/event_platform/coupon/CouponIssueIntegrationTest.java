package com.kaameo.event_platform.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.domain.CouponEventStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.repository.CouponEventRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import com.kaameo.event_platform.coupon.service.RedisStockManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CouponIssueIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("event_platform_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.autoconfigure.exclude", () ->
                "org.redisson.spring.starter.RedissonAutoConfigurationV2," +
                "org.redisson.spring.starter.RedissonAutoConfigurationV4");
    }

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private CouponEventRepository couponEventRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private RedisStockManager redisStockManager;

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAll();
        couponEventRepository.deleteAll();
    }

    @Test
    void fullIssuanceFlow_asyncPipeline() throws Exception {
        CouponEvent event = CouponEvent.builder()
                .name("Integration Test Coupon")
                .totalStock(10)
                .status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1))
                .endAt(LocalDateTime.now().plusHours(1))
                .build();
        CouponEvent savedEvent = couponEventRepository.save(event);
        redisStockManager.initStock(savedEvent.getId(), savedEvent.getTotalStock());

        CouponIssueRequest request = new CouponIssueRequest(savedEvent.getId(), 1L);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(couponIssueRepository.countByCouponEventId(savedEvent.getId())).isEqualTo(1));

        assertThat(redisStockManager.getRemainingStock(savedEvent.getId())).isEqualTo(9);
    }

    @Test
    void idempotency_sameKey_returnsExisting() throws Exception {
        CouponEvent event = CouponEvent.builder()
                .name("Idempotency Test").totalStock(10).status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1)).endAt(LocalDateTime.now().plusHours(1))
                .build();
        CouponEvent savedEvent = couponEventRepository.save(event);
        redisStockManager.initStock(savedEvent.getId(), savedEvent.getTotalStock());

        CouponIssueRequest request = new CouponIssueRequest(savedEvent.getId(), 1L);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.message").value("이미 처리된 요청입니다."));
    }

    @Test
    void soldOut_returns410() throws Exception {
        CouponEvent event = CouponEvent.builder()
                .name("SoldOut Test").totalStock(1).status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1)).endAt(LocalDateTime.now().plusHours(1))
                .build();
        CouponEvent savedEvent = couponEventRepository.save(event);
        redisStockManager.initStock(savedEvent.getId(), 0);

        CouponIssueRequest request = new CouponIssueRequest(savedEvent.getId(), 1L);

        mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isGone());
    }
}
