package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingHeartbeat;

public interface TimingHeartbeatRepository extends JpaRepository<TimingHeartbeat, Long> {

    Optional<TimingHeartbeat> findByDeviceIdAndHbSeq(Long deviceId, long hbSeq);

    List<TimingHeartbeat> findTop30ByDeviceIdOrderByOccurredAtDesc(Long deviceId);

    List<TimingHeartbeat> findAllBySessionIdOrderByOccurredAtAsc(Long sessionId);

    long countByDeviceId(Long deviceId);
}
