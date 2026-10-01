package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.RaceSession;

import jakarta.persistence.LockModeType;

public interface RaceSessionRepository extends JpaRepository<RaceSession, Long> {

    List<RaceSession> findAllByOrderByStartTimeDesc();

    Optional<RaceSession> findBySessionNo(String sessionNo);

    boolean existsBySessionNo(String sessionNo);

    List<RaceSession> findAllByStatusOrderByStartTimeAsc(String status);

    long countByStatus(String status);

    /** 红旗发起时的行级悲观锁：并发请求在同一行上排队，保证「查重—改状态—建清单」串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RaceSession s where s.id = :id")
    Optional<RaceSession> findByIdForUpdate(@Param("id") Long id);
}
