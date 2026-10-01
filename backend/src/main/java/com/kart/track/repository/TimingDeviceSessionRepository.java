package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingDeviceSession;

public interface TimingDeviceSessionRepository extends JpaRepository<TimingDeviceSession, Long> {

    Optional<TimingDeviceSession> findByDeviceIdAndSessionId(Long deviceId, Long sessionId);

    List<TimingDeviceSession> findAllBySessionIdOrderByIdAsc(Long sessionId);

    List<TimingDeviceSession> findAllByDeviceIdOrderByIdAsc(Long deviceId);
}
