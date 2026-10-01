package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.TimingDevice;

import jakarta.persistence.LockModeType;

public interface TimingDeviceRepository extends JpaRepository<TimingDevice, Long> {

    Optional<TimingDevice> findByDeviceCode(String deviceCode);

    boolean existsByDeviceCode(String deviceCode);

    List<TimingDevice> findAllByOrderByDeviceCodeAsc();

    List<TimingDevice> findAllBySessionIdOrderByDeviceCodeAsc(Long sessionId);

    List<TimingDevice> findAllByTrackCodeOrderByDeviceCodeAsc(String trackCode);

    long countByQuarantinedTrue();

    /** 心跳/圈速上报时给设备行加悲观写锁：同设备并发上报在此串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TimingDevice d where d.deviceCode = :code")
    Optional<TimingDevice> findByDeviceCodeForUpdate(@Param("code") String code);
}
