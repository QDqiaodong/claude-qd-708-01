package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.Maintenance;

public interface MaintenanceRepository extends JpaRepository<Maintenance, Long> {

    List<Maintenance> findAllByOrderByStartTimeDesc();

    Optional<Maintenance> findByOrderNo(String orderNo);

    boolean existsByOrderNo(String orderNo);

    List<Maintenance> findAllByKartIdOrderByStartTimeDesc(Long kartId);

    List<Maintenance> findAllByStatusOrderByStartTimeAsc(String status);

    long countByStatus(String status);
}
