package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingTakeover;

public interface TimingTakeoverRepository extends JpaRepository<TimingTakeover, Long> {

    List<TimingTakeover> findAllBySessionIdOrderByIdAsc(Long sessionId);

    Optional<TimingTakeover> findBySessionIdAndStatus(Long sessionId, String status);

    boolean existsByTakeoverNo(String takeoverNo);
}
