package com.kart.track.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingSource;

public interface TimingSourceRepository extends JpaRepository<TimingSource, Long> {

    Optional<TimingSource> findBySessionId(Long sessionId);
}
