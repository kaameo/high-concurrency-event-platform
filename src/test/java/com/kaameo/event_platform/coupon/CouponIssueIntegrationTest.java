package com.kaameo.event_platform.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.domain.CouponEventStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueRequest;
import com.kaameo.event_platform.coupon.repository.CouponEventRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.redisson.spring.starter.RedissonAutoConfiguration");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CouponEventRepository couponEventRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @BeforeEach
    void setUp() {
        couponIssueRepository.deleteAll();
        couponEventRepository.deleteAll();
    }

    @Test
    void fullIssuanceFlow() throws Exception {
        CouponEvent event = CouponEvent.builder()
                .name("Integration Test Coupon")
                .totalStock(10)
                .status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1))
                .endAt(LocalDateTime.now().plusHours(1))
                .build();
        event = couponEventRepository.save(event);

        CouponIssueRequest request = new CouponIssueRequest(event.getId(), 1L);
        String idempotencyKey = UUID.randomUUID().toString();

        String responseBody = mockMvc.perform(post("/api/v1/coupons/issue")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andReturn().getResponse().getContentAsString();

        String requestId = objectMapper.readTree(responseBody).path("data").path("requestId").asText();

        mockMvc.perform(get("/api/v1/coupons/requests/{requestId}", requestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ISSUED"));

        mockMvc.perform(get("/api/v1/coupons/{couponEventId}", event.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingStock").value(9));
    }

    @Test
    void idempotency_sameKey_returnsSameResult() throws Exception {
        CouponEvent event = CouponEvent.builder()
                .name("Idempotency Test").totalStock(10).status(CouponEventStatus.ACTIVE)
                .startAt(LocalDateTime.now().minusHours(1)).endAt(LocalDateTime.now().plusHours(1))
                .build();
        event = couponEventRepository.save(event);

        CouponIssueRequest request = new CouponIssueRequest(event.getId(), 1L);
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
                .andExpect(jsonPath("$.data.status").value("ISSUED"));
    }
}
