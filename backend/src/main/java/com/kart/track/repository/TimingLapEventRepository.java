package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.kart.track.domain.TimingLapEvent;

public interface TimingLapEventRepository extends JpaRepository<TimingLapEvent, Long> {

    Optional<TimingLapEvent> findByDeviceIdAndEventUid(Long deviceId, String eventUid);

    Optional<TimingLapEvent> findByDeviceIdAndSessionIdAndSeq(Long deviceId, Long sessionId, long seq);

    List<TimingLapEvent> findAllBySessionIdOrderBySeqAscDeviceIdAsc(Long sessionId);

    List<TimingLapEvent> findAllByDeviceIdAndSessionIdOrderBySeqAsc(Long deviceId, Long sessionId);

    List<TimingLapEvent> findAllByStageOrderByIdAsc(String stage);

    List<TimingLapEvent> findAllBySessionIdAndStageOrderByIdAsc(Long sessionId, String stage);

    List<TimingLapEvent> findAllByGapIdOrderBySeqAsc(Long gapId);

    List<TimingLapEvent> findAllBySessionIdOrderByReceivedAtDesc(Long sessionId);

    long countBySessionIdAndStage(Long sessionId, String stage);

    long countByStage(String stage);
}
