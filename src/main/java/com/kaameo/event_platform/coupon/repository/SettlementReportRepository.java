package com.kaameo.event_platform.coupon.repository;

import com.kaameo.event_platform.coupon.domain.SettlementReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SettlementReportRepository extends JpaRepository<SettlementReport, UUID> {
}
