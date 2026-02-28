package com.kaameo.event_platform.coupon.repository;

import com.kaameo.event_platform.coupon.domain.CouponIssue;
import com.kaameo.event_platform.coupon.domain.IssueStatus;
import com.kaameo.event_platform.coupon.dto.CouponIssueSearchCondition;
import com.kaameo.event_platform.coupon.dto.CouponIssueStats;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.kaameo.event_platform.coupon.domain.QCouponIssue.couponIssue;

@Repository
@RequiredArgsConstructor
public class CouponIssueQueryRepository {

    private final JPAQueryFactory queryFactory;

    public Page<CouponIssue> searchIssues(CouponIssueSearchCondition condition, Pageable pageable) {
        List<CouponIssue> content = queryFactory
                .selectFrom(couponIssue)
                .where(
                        couponEventIdEq(condition.couponEventId()),
                        userIdEq(condition.userId()),
                        statusEq(condition.status()),
                        createdAtGoe(condition.startDate()),
                        createdAtLoe(condition.endDate())
                )
                .orderBy(couponIssue.createdAt.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(couponIssue.count())
                .from(couponIssue)
                .where(
                        couponEventIdEq(condition.couponEventId()),
                        userIdEq(condition.userId()),
                        statusEq(condition.status()),
                        createdAtGoe(condition.startDate()),
                        createdAtLoe(condition.endDate())
                );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    public CouponIssueStats getIssueStats(UUID couponEventId) {
        long totalIssued = countByStatus(couponEventId, IssueStatus.ISSUED);
        long totalPending = countByStatus(couponEventId, IssueStatus.PENDING);
        long totalFailed = countByStatus(couponEventId, IssueStatus.FAILED);
        return new CouponIssueStats(totalIssued, totalPending, totalFailed);
    }

    private long countByStatus(UUID couponEventId, IssueStatus status) {
        Long count = queryFactory
                .select(couponIssue.count())
                .from(couponIssue)
                .where(
                        couponIssue.couponEventId.eq(couponEventId),
                        couponIssue.status.eq(status)
                )
                .fetchOne();
        return count != null ? count : 0;
    }

    private BooleanExpression couponEventIdEq(UUID couponEventId) {
        return couponEventId != null ? couponIssue.couponEventId.eq(couponEventId) : null;
    }

    private BooleanExpression userIdEq(Long userId) {
        return userId != null ? couponIssue.userId.eq(userId) : null;
    }

    private BooleanExpression statusEq(IssueStatus status) {
        return status != null ? couponIssue.status.eq(status) : null;
    }

    private BooleanExpression createdAtGoe(LocalDateTime startDate) {
        return startDate != null ? couponIssue.createdAt.goe(startDate) : null;
    }

    private BooleanExpression createdAtLoe(LocalDateTime endDate) {
        return endDate != null ? couponIssue.createdAt.loe(endDate) : null;
    }
}
