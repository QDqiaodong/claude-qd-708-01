package com.kart.track.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingGap;

public interface TimingGapRepository extends JpaRepository<TimingGap, Long> {

    List<TimingGap> findAllBySessionIdOrderByIdAsc(Long sessionId);

    List<TimingGap> findAllByDeviceIdAndSessionIdOrderByIdAsc(Long deviceId, Long sessionId);

    List<TimingGap> findAllByStatusOrderByIdAsc(String status);

    long countBySessionIdAndStatus(Long sessionId, String status);

    long countByStatus(String status);
}
