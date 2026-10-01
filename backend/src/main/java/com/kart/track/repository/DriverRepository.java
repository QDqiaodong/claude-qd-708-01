package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.Driver;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findAllByOrderByBestLapMsAsc();

    List<Driver> findAllByOrderByMemberNoAsc();

    Optional<Driver> findByMemberNo(String memberNo);

    boolean existsByMemberNo(String memberNo);

    List<Driver> findAllBySessionIdOrderByBestLapMsAsc(Long sessionId);

    long countByAgeGroup(String ageGroup);

    List<Driver> findAllByBestLapMsGreaterThanOrderByBestLapMsAsc(int bestLapMs);
}
