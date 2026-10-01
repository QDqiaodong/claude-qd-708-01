package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.kart.track.domain.RedFlagEvent;

import jakarta.persistence.LockModeType;

public interface RedFlagEventRepository extends JpaRepository<RedFlagEvent, Long> {

    Optional<RedFlagEvent> findBySessionIdAndStatus(Long sessionId, String status);

    List<RedFlagEvent> findAllBySessionIdOrderByOccurredAtDesc(Long sessionId);

    List<RedFlagEvent> findAllByStatusOrderByOccurredAtAsc(String status);

    boolean existsByEventNo(String eventNo);

    long countByStatus(String status);

    /**
     * 行级悲观锁：并发发起红旗时，两个事务在此排队，后一个会看到前一个已提交的 ACTIVE，
     * 从而返回同一事件，不会生成两套核查清单。唯一索引 uk_active_session 再做最后兜底。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from RedFlagEvent e where e.sessionId = :sessionId and e.status = 'ACTIVE'")
    Optional<RedFlagEvent> findActiveForUpdate(@org.springframework.data.repository.query.Param("sessionId") Long sessionId);
}
