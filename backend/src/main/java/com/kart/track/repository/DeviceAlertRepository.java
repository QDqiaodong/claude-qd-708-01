package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.DeviceAlert;

public interface DeviceAlertRepository extends JpaRepository<DeviceAlert, Long> {

    /** 活跃告警去重：同键只保留一条 ACTIVE（uk_alert_dedup 唯一索引兜底） */
    Optional<DeviceAlert> findByDedupKeyAndStatus(String dedupKey, String status);

    List<DeviceAlert> findAllByStatusOrderByCreatedAtDesc(String status);

    List<DeviceAlert> findAllBySessionIdOrderByCreatedAtDesc(Long sessionId);

    long countByStatus(String status);

    long countByTypeAndStatus(String type, String status);
}
