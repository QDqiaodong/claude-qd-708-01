package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.Kart;

public interface KartRepository extends JpaRepository<Kart, Long> {

    List<Kart> findAllByOrderByKartNoAsc();

    Optional<Kart> findByKartNo(String kartNo);

    boolean existsByKartNo(String kartNo);

    List<Kart> findAllByStatusOrderByKartNoAsc(String status);

    long countByStatus(String status);
}
