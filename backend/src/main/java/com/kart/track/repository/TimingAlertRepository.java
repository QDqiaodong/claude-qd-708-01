package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingAlert;

public interface TimingAlertRepository extends JpaRepository<TimingAlert, Long> {

    Optional<TimingAlert> findByDedupKeyAndStatus(String dedupKey, String status);

    List<TimingAlert> findAllByDeviceIdOrderByRaisedAtDesc(Long deviceId);

    List<TimingAlert> findAllByStatusOrderByRaisedAtDesc(String status);

    List<TimingAlert> findAllByOrderByRaisedAtDesc();

    long countByStatus(String status);
}
