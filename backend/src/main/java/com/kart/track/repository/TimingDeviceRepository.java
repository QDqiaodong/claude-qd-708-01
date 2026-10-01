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

    Optional<TimingDevice> findByDeviceNo(String deviceNo);

    boolean existsByDeviceNo(String deviceNo);

    List<TimingDevice> findAllByOrderByDeviceNoAsc();

    List<TimingDevice> findAllBySessionId(Long sessionId);

    Optional<TimingDevice> findBySessionIdAndRole(Long sessionId, String role);

    /** 接管/关联收口时的行级悲观锁：设备状态 + 场次状态必须一起改，串行化防双来源 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TimingDevice d where d.id = :id")
    Optional<TimingDevice> findByIdForUpdate(@Param("id") Long id);
}
