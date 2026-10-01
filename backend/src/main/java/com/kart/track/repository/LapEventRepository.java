package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.LapEvent;

public interface LapEventRepository extends JpaRepository<LapEvent, Long> {

    /** 重放幂等：同一设备同一事件标识只落一行，无论重放多少次 */
    Optional<LapEvent> findByDeviceIdAndEventUid(Long deviceId, String eventUid);

    List<LapEvent> findAllBySessionIdAndDeviceIdOrderBySeqNoAsc(Long sessionId, Long deviceId);

    List<LapEvent> findAllBySessionIdOrderBySeqNoAsc(Long sessionId);

    List<LapEvent> findAllBySessionIdAndStatusOrderByIdAsc(Long sessionId, String status);

    List<LapEvent> findAllByStatusOrderByIdAsc(String status);

    long countBySessionIdAndStatus(Long sessionId, String status);

    Optional<LapEvent> findByLapId(Long lapId);
}
