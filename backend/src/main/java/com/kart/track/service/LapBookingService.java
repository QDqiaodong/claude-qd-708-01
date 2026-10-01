package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.domain.Driver;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.RedFlagLap;
import com.kart.track.domain.TrackDict;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.RedFlagEventRepository;
import com.kart.track.repository.RedFlagLapRepository;

/**
 * 圈速入账：手工登记（模块四）与计时设备事件（模块九）共用的落账动作。
 *
 * <p>收口三件只有「正式入账」才允许发生的副作用，保证设备重放补偿与手工登记口径一致：
 * <ol>
 *   <li>写 lap_record 并维护本场最快圈（旧最快复位，同一时刻全场唯一）；</li>
 *   <li>刷新车手历史最好成绩；</li>
 *   <li>累计车辆里程/运行小时。</li>
 * </ol>
 *
 * <p>若该圈产生于红旗覆盖时段（{@code coveringRedFlag} 非空）：lap_record 照常留底，
 * 但不写最快圈、不刷最佳、不计里程，同时挂一条 red_flag_lap 待主管裁定——
 * 设备断线重放恢复出来的旧圈也按其【实际产生时刻】落入红旗窗口判断，不能绕过红旗规则。
 */
@Service
public class LapBookingService {

    private final LapRecordRepository lapRecordRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final RedFlagEventRepository redFlagEventRepository;
    private final RedFlagLapRepository redFlagLapRepository;

    public LapBookingService(LapRecordRepository lapRecordRepository,
                             DriverRepository driverRepository,
                             KartRepository kartRepository,
                             RedFlagEventRepository redFlagEventRepository,
                             RedFlagLapRepository redFlagLapRepository) {
        this.lapRecordRepository = lapRecordRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.redFlagEventRepository = redFlagEventRepository;
        this.redFlagLapRepository = redFlagLapRepository;
    }

    /** 入账结果：落库的圈速 + 是否挂红旗待裁定（及对应挂起行） */
    public record BookedLap(LapRecord lap, boolean underRedFlag, RedFlagLap redFlagLap) {
    }

    /**
     * 找出处在某时刻红旗覆盖区间内的红旗事件。
     *
     * <p>ACTIVE：时刻在发生之后即覆盖；RESUMED/TERMINATED：时刻落在 [发生, 恢复/终止] 之间。
     * 设备重放的旧圈按 occurredAt 而不是收到时刻判定。
     */
    @Transactional(readOnly = true)
    public RedFlagEvent findCoveringRedFlag(Long sessionId, LocalDateTime at) {
        for (RedFlagEvent event : redFlagEventRepository.findAllBySessionIdOrderByOccurredAtDesc(sessionId)) {
            LocalDateTime closedAt = event.getResumedAt() != null ? event.getResumedAt() : event.getTerminatedAt();
            if ("ACTIVE".equals(event.getStatus())) {
                if (!at.isBefore(event.getOccurredAt())) {
                    return event;
                }
            } else if (closedAt != null && !at.isBefore(event.getOccurredAt()) && !at.isAfter(closedAt)) {
                return event;
            }
        }
        return null;
    }

    /**
     * 正式入账一条圈速。
     *
     * @param lapAt             圈速记录时间（手工=服务端当前；设备=事件实际产生时刻）
     * @param coveringRedFlag   覆盖该圈产生时刻的红旗事件，null 表示正常时段
     */
    @Transactional
    public BookedLap book(RaceSession session, Driver driver, Kart kart, int lapTimeMs, String valid,
                          LocalDateTime lapAt, RedFlagEvent coveringRedFlag) {
        boolean underRedFlag = coveringRedFlag != null;
        int lapIndex = (int) lapRecordRepository.findByDriverIdOrderByLapTimeMsAsc(driver.getId()).stream()
                .filter(lap -> lap.getSessionId().equals(session.getId()))
                .count() + 1;

        LapRecord lap = new LapRecord();
        lap.setSessionId(session.getId());
        lap.setDriverId(driver.getId());
        lap.setKartId(kart.getId());
        lap.setLapIndex(lapIndex);
        lap.setLapTimeMs(lapTimeMs);
        lap.setValid(valid);
        lap.setCreatedAt(lapAt);

        boolean fastest = false;
        if (!underRedFlag && "VALID".equals(valid)) {
            Set<Long> excluded = boardExcludedLapIds(session.getId());
            Integer currentBest = null;
            for (LapRecord old : lapRecordRepository
                    .findBySessionIdAndValidOrderByLapTimeMsAsc(session.getId(), "VALID")) {
                if (!excluded.contains(old.getId())) {
                    currentBest = old.getLapTimeMs();
                    break;
                }
            }
            fastest = currentBest == null || lapTimeMs < currentBest;
            if (fastest && currentBest != null) {
                for (LapRecord old : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId())) {
                    if (old.isFastest()) {
                        old.setFastest(false);
                        lapRecordRepository.save(old);
                    }
                }
            }
        }
        lap.setFastest(fastest);
        LapRecord saved = lapRecordRepository.save(lap);

        RedFlagLap held = null;
        if (underRedFlag) {
            held = new RedFlagLap();
            held.setLapId(saved.getId());
            held.setEventId(coveringRedFlag.getId());
            held.setSessionId(session.getId());
            held.setRuling("PENDING");
            redFlagLapRepository.save(held);
        } else if ("VALID".equals(valid)) {
            if (driver.getBestLapMs() == null || driver.getBestLapMs() <= 0 || lapTimeMs < driver.getBestLapMs()) {
                driver.setBestLapMs(lapTimeMs);
                driverRepository.save(driver);
            }
            BigDecimal km = TrackDict.configLapKm(session.getTrackConfig());
            kart.setTotalKm(kart.getTotalKm().add(km));
            kart.setTotalHours(kart.getTotalHours().add(BigDecimal.valueOf(lapTimeMs / 3600000.0)));
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
        }
        return new BookedLap(saved, underRedFlag, held);
    }

    /** 该场次不允许进榜的圈速 id：红旗期间待裁定 + 已作废（已确认有效的不在内） */
    private Set<Long> boardExcludedLapIds(Long sessionId) {
        Set<Long> ids = new HashSet<>();
        List<RedFlagLap> rows = redFlagLapRepository.findAllBySessionIdOrderByIdDesc(sessionId);
        for (RedFlagLap row : rows) {
            if (!"CONFIRMED".equals(row.getRuling())) {
                ids.add(row.getLapId());
            }
        }
        return ids;
    }
}
