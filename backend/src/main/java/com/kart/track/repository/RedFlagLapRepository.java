package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.RedFlagLap;

public interface RedFlagLapRepository extends JpaRepository<RedFlagLap, Long> {

    Optional<RedFlagLap> findByLapId(Long lapId);

    List<RedFlagLap> findAllByEventIdOrderByIdAsc(Long eventId);

    List<RedFlagLap> findAllBySessionIdOrderByIdDesc(Long sessionId);

    long countByEventId(Long eventId);

    long countByEventIdAndRuling(Long eventId, String ruling);

    long countBySessionIdAndRuling(Long sessionId, String ruling);

    long countByRuling(String ruling);
}
