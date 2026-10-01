package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.DeviceTakeover;

public interface DeviceTakeoverRepository extends JpaRepository<DeviceTakeover, Long> {

    /** 同场次生效中的接管（数据库 uk_active_takeover 唯一约束也会兜底） */
    Optional<DeviceTakeover> findBySessionIdAndStatus(Long sessionId, String status);

    List<DeviceTakeover> findAllBySessionIdOrderByRequestedAtDesc(Long sessionId);

    List<DeviceTakeover> findAllByStatusOrderByRequestedAtAsc(String status);

    long countBySessionIdAndStatus(Long sessionId, String status);
}
