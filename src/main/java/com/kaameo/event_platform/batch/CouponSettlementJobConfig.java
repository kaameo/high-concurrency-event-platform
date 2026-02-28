package com.kaameo.event_platform.batch;

import com.kaameo.event_platform.coupon.domain.CouponEvent;
import com.kaameo.event_platform.coupon.domain.SettlementReport;
import com.kaameo.event_platform.coupon.dto.CouponIssueStats;
import com.kaameo.event_platform.coupon.repository.CouponIssueQueryRepository;
import com.kaameo.event_platform.coupon.repository.CouponIssueRepository;
import com.kaameo.event_platform.coupon.repository.SettlementReportRepository;
import com.kaameo.event_platform.coupon.service.RedisStockManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CouponSettlementJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final CouponIssueRepository couponIssueRepository;
    private final CouponIssueQueryRepository couponIssueQueryRepository;
    private final RedisStockManager redisStockManager;
    private final SettlementReportRepository settlementReportRepository;

    @Bean
    public Job couponSettlementJob() {
        return new JobBuilder("couponSettlementJob", jobRepository)
                .start(settlementStep())
                .build();
    }

    @Bean
    public Step settlementStep() {
        return new StepBuilder("settlementStep", jobRepository)
                .<CouponEvent, SettlementReport>chunk(10)
                .reader(couponEventReader())
                .processor(settlementProcessor())
                .writer(settlementWriter())
                .transactionManager(transactionManager)
                .build();
    }

    @Bean
    public JpaPagingItemReader<CouponEvent> couponEventReader() {
        return new JpaPagingItemReaderBuilder<CouponEvent>()
                .name("couponEventReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT e FROM CouponEvent e ORDER BY e.id")
                .pageSize(10)
                .build();
    }

    @Bean
    public ItemProcessor<CouponEvent, SettlementReport> settlementProcessor() {
        return event -> {
            CouponIssueStats stats = couponIssueQueryRepository.getIssueStats(event.getId());
            long redisRemaining = redisStockManager.getRemainingStock(event.getId());
            long dbIssuedCount = couponIssueRepository.countByCouponEventId(event.getId());

            long expectedRemaining = event.getTotalStock() - dbIssuedCount;
            boolean isConsistent = redisRemaining == expectedRemaining;

            if (!isConsistent) {
                log.warn("정합성 불일치: couponEventId={}, redis={}, expected={}",
                        event.getId(), redisRemaining, expectedRemaining);
            }

            return SettlementReport.builder()
                    .couponEventId(event.getId())
                    .totalIssued(stats.totalIssued())
                    .totalPending(stats.totalPending())
                    .totalFailed(stats.totalFailed())
                    .redisRemaining(redisRemaining)
                    .dbIssuedCount(dbIssuedCount)
                    .isConsistent(isConsistent)
                    .settledAt(LocalDateTime.now())
                    .build();
        };
    }

    @Bean
    public ItemWriter<SettlementReport> settlementWriter() {
        return items -> {
            for (SettlementReport report : items) {
                settlementReportRepository.save(report);
                log.info("정산 완료: couponEventId={}, issued={}, consistent={}",
                        report.getCouponEventId(), report.getTotalIssued(), report.isConsistent());
            }
        };
    }
}
