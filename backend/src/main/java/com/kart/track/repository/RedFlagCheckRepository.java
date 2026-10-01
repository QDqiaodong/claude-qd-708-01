package com.kart.track.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.RedFlagCheck;

public interface RedFlagCheckRepository extends JpaRepository<RedFlagCheck, Long> {

    List<RedFlagCheck> findAllByEventIdOrderByKartIdAsc(Long eventId);

    long countByEventId(Long eventId);

    long countByEventIdAndConclusion(Long eventId, String conclusion);

    long countByEventIdAndConclusionIsNull(Long eventId);
}
