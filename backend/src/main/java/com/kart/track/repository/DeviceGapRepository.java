package com.kart.track.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.DeviceGap;

public interface DeviceGapRepository extends JpaRepository<DeviceGap, Long> {

    List<DeviceGap> findAllBySessionIdOrderByOpenedAtDesc(Long sessionId);

    List<DeviceGap> findAllByDeviceIdAndStatusOrderByFromSeqAsc(Long deviceId, String status);

    List<DeviceGap> findAllBySessionIdAndStatusOrderByFromSeqAsc(Long sessionId, String status);

    long countBySessionIdAndStatus(Long sessionId, String status);
}
