package com.kart.track.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.LapRecord;

/**
 * 圈速记录。虽然实体上的写操作走 {@code @SQLInsert / @SQLUpdate / @SQLDelete} 手写 SQL，
 * 查询仍然用 Spring Data JPA 的派生方法（由 Hibernate 生成 select）。
 */
public interface LapRecordRepository extends JpaRepository<LapRecord, Long> {

    List<LapRecord> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    List<LapRecord> findBySessionIdOrderByLapTimeMsAsc(Long sessionId);

    List<LapRecord> findBySessionIdAndValidOrderByLapTimeMsAsc(Long sessionId, String valid);

    List<LapRecord> findByDriverIdOrderByLapTimeMsAsc(Long driverId);

    List<LapRecord> findByKartIdOrderByCreatedAtDesc(Long kartId);

    long countBySessionId(Long sessionId);

    long countBySessionIdAndValid(Long sessionId, String valid);

    long countByCreatedAtAfter(java.time.LocalDateTime from);
}
