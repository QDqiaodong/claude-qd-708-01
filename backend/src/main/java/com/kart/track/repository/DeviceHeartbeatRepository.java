package com.kart.track.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.DeviceHeartbeat;

public interface DeviceHeartbeatRepository extends JpaRepository<DeviceHeartbeat, Long> {

    List<DeviceHeartbeat> findAllByDeviceIdOrderByReceivedAtDesc(Long deviceId);

    List<DeviceHeartbeat> findAllBySessionIdOrderByReceivedAtDesc(Long sessionId);
}
