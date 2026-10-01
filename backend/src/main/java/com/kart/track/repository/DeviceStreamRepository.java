package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.DeviceStream;

public interface DeviceStreamRepository extends JpaRepository<DeviceStream, Long> {

    Optional<DeviceStream> findBySessionIdAndDeviceId(Long sessionId, Long deviceId);

    List<DeviceStream> findAllBySessionIdOrderByIdAsc(Long sessionId);
}
