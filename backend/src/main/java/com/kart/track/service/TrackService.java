package com.kart.track.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Driver;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.Maintenance;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.RedFlagLap;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.MaintenanceRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagEventRepository;
import com.kart.track.repository.RedFlagLapRepository;

/** 首页「赛道实况」：大计时器 + 场次信息条 + 赛道点位 + 实时圈速排行榜。 */
@Service
public class TrackService {

    private final RaceSessionRepository sessionRepository;
    private final LapRecordRepository lapRecordRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final RedFlagLapRepository redFlagLapRepository;
    private final RedFlagEventRepository redFlagEventRepository;

    public TrackService(RaceSessionRepository sessionRepository,
                        LapRecordRepository lapRecordRepository,
                        DriverRepository driverRepository,
                        KartRepository kartRepository,
                        MaintenanceRepository maintenanceRepository,
                        RedFlagLapRepository redFlagLapRepository,
                        RedFlagEventRepository redFlagEventRepository) {
        this.sessionRepository = sessionRepository;
        this.lapRecordRepository = lapRecordRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.redFlagLapRepository = redFlagLapRepository;
        this.redFlagEventRepository = redFlagEventRepository;
    }

    /** 顶栏计数器 */
    @Transactional(readOnly = true)
    public Views.Overview overview() {
        long maintDone = 0;
        BigDecimal fee = BigDecimal.ZERO;
        for (Maintenance item : maintenanceRepository.findAll()) {
            if ("DONE".equals(item.getStatus())) {
                maintDone += 1;
                fee = fee.add(item.getTotalFee() == null ? BigDecimal.ZERO : item.getTotalFee());
            }
        }
        long maintOpen = 0;
        for (Maintenance item : maintenanceRepository.findAll()) {
            if (!"DONE".equals(item.getStatus())) {
                maintOpen += 1;
            }
        }
        long redFlagActive = sessionRepository.countByStatus("RED_FLAG");
        long pendingLaps = redFlagLapRepository.countByRuling("PENDING");
        return new Views.Overview(
                kartRepository.count(),
                kartRepository.countByStatus("AVAILABLE"),
                kartRepository.countByStatus("ON_TRACK"),
                kartRepository.countByStatus("MAINTENANCE"),
                kartRepository.countByStatus("DISABLED"),
                driverRepository.count(),
                sessionRepository.countByStatus("PENDING"),
                sessionRepository.countByStatus("RUNNING"),
                lapRecordRepository.count(),
                maintOpen,
                maintDone,
                fee,
                redFlagActive,
                pendingLaps);
    }

    /**
     * 一屏数据：当前场次 + 排行榜 + 赛道点位。
     *
     * @param sessionId 为空时取「进行中」的场次，没有进行中的就取最近一场
     */
    @Transactional(readOnly = true)
    public Views.LiveView live(Long sessionId) {
        RaceSession session = pickSession(sessionId);
        if (session == null) {
            return new Views.LiveView(null, List.of(), List.of(), 0, "--:--.---", "暂无场次");
        }
        Map<Long, String> driverName = new HashMap<>();
        for (Driver driver : driverRepository.findAll()) {
            driverName.put(driver.getId(), driver.getName());
        }
        Map<Long, String> kartNo = new HashMap<>();
        for (Kart kart : kartRepository.findAll()) {
            kartNo.put(kart.getId(), kart.getKartNo());
        }

        List<LapRecord> laps = lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId());

        // 红旗计时隔离：待裁定 / 已作废的红旗圈速不进实时排行榜、不计最快圈、不占圈数
        java.util.Set<Long> excludedLapIds = new HashSet<>();
        for (RedFlagLap row : redFlagLapRepository.findAllBySessionIdOrderByIdDesc(session.getId())) {
            if (!"CONFIRMED".equals(row.getRuling())) {
                excludedLapIds.add(row.getLapId());
            }
        }
        List<LapRecord> boardLaps = new ArrayList<>();
        for (LapRecord lap : laps) {
            if (!excludedLapIds.contains(lap.getId())) {
                boardLaps.add(lap);
            }
        }

        // 排行榜：有效圈按车手取最快，升序
        Map<Long, List<LapRecord>> byDriver = new LinkedHashMap<>();
        for (LapRecord lap : boardLaps) {
            if (!"VALID".equals(lap.getValid())) {
                continue;
            }
            byDriver.computeIfAbsent(lap.getDriverId(), key -> new ArrayList<>()).add(lap);
        }
        List<Views.LeaderRow> rows = new ArrayList<>();
        for (Map.Entry<Long, List<LapRecord>> entry : byDriver.entrySet()) {
            LapRecord best = entry.getValue().stream()
                    .min((a, b) -> Integer.compare(a.getLapTimeMs(), b.getLapTimeMs()))
                    .orElse(null);
            if (best == null) {
                continue;
            }
            rows.add(new Views.LeaderRow(0, driverName.getOrDefault(entry.getKey(), "—"),
                    kartNo.getOrDefault(best.getKartId(), "—"), best.getLapTimeMs(),
                    TrackDict.formatLap(best.getLapTimeMs()), "", entry.getValue().size(), false));
        }
        rows.sort((a, b) -> Integer.compare(a.bestLapMs(), b.bestLapMs()));
        List<Views.LeaderRow> leaderboard = new ArrayList<>();
        Integer bestMs = rows.isEmpty() ? null : rows.get(0).bestLapMs();
        int rank = 0;
        for (Views.LeaderRow row : rows) {
            rank += 1;
            leaderboard.add(new Views.LeaderRow(rank, row.driverName(), row.kartNo(), row.bestLapMs(),
                    row.lapText(), TrackDict.formatDelta(row.bestLapMs(), bestMs), row.lapCount(), rank == 1));
        }

        // 赛道点位：在赛车辆沿赛道均匀分布；没有在赛车就退回本场跑过的车
        List<Kart> onTrack = kartRepository.findAllByStatusOrderByKartNoAsc("ON_TRACK");
        if (onTrack.isEmpty()) {
            List<Long> kartIds = laps.stream().map(LapRecord::getKartId).distinct().toList();
            for (Long kartId : kartIds) {
                kartRepository.findById(kartId).ifPresent(onTrack::add);
            }
        }
        List<Views.TrackDot> dots = new ArrayList<>();
        int total = Math.max(onTrack.size(), 1);
        Map<Long, Long> kartDriver = new HashMap<>();
        for (LapRecord lap : laps) {
            kartDriver.putIfAbsent(lap.getKartId(), lap.getDriverId());
        }
        for (int i = 0; i < onTrack.size(); i++) {
            Kart kart = onTrack.get(i);
            Long driverId = kartDriver.get(kart.getId());
            dots.add(new Views.TrackDot(
                    kart.getId(),
                    kart.getKartNo(),
                    kart.getStatus(),
                    TrackDict.kartStatusName(kart.getStatus()),
                    driverId == null ? "待发车" : driverName.getOrDefault(driverId, "—"),
                    (double) i / total,
                    speedOf(kart)));
        }

        String summary = TrackDict.trackConfigName(session.getTrackConfig()) + " · " + session.getGroupCode()
                + " 组 · " + session.getSignedCount() + "/" + session.getMaxKarts() + " 台";
        return new Views.LiveView(
                sessionView(session, boardLaps, laps.size()),
                leaderboard,
                dots,
                laps.size(),
                TrackDict.formatLap(bestMs),
                summary);
    }

    /** 场次计时：已进行时长（秒）。红旗处理中计时不暂停（处置台另显示红旗持续时长） */
    public long elapsedSec(RaceSession session) {
        if (session == null || session.getStartTime() == null) {
            return 0;
        }
        LocalDateTime end = "RUNNING".equals(session.getStatus()) || "RED_FLAG".equals(session.getStatus())
                ? LocalDateTime.now() : session.getEndTime();
        if (end == null) {
            end = LocalDateTime.now();
        }
        long seconds = Duration.between(session.getStartTime(), end).getSeconds();
        return seconds < 0 ? 0 : seconds;
    }

    private RaceSession pickSession(Long sessionId) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId).orElseThrow(() -> new BizException("场次不存在"));
        }
        List<RaceSession> running = sessionRepository.findAllByStatusOrderByStartTimeAsc("RUNNING");
        if (!running.isEmpty()) {
            return running.get(0);
        }
        // 没有进行中但有红旗处理中的场次时，实况页也应落到该场
        List<RaceSession> flagged = sessionRepository.findAllByStatusOrderByStartTimeAsc("RED_FLAG");
        if (!flagged.isEmpty()) {
            return flagged.get(0);
        }
        List<RaceSession> all = sessionRepository.findAllByOrderByStartTimeDesc();
        return all.isEmpty() ? null : all.get(0);
    }

    private Views.SessionView sessionView(RaceSession session, List<LapRecord> boardLaps, long allLapCount) {
        Integer fastest = null;
        for (LapRecord lap : boardLaps) {
            if ("VALID".equals(lap.getValid()) && (fastest == null || lap.getLapTimeMs() < fastest)) {
                fastest = lap.getLapTimeMs();
            }
        }
        return new Views.SessionView(
                session.getId(),
                session.getSessionNo(),
                session.getStartTime(),
                session.getEndTime(),
                session.getTrackConfig(),
                TrackDict.trackConfigName(session.getTrackConfig()),
                session.getGroupCode(),
                session.getMaxKarts(),
                session.getSignedCount(),
                session.getStatus(),
                TrackDict.sessionStatusName(session.getStatus()),
                elapsedSec(session),
                allLapCount,
                TrackDict.formatLap(fastest),
                redFlagEventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE").isPresent(),
                redFlagEventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE")
                        .map(RedFlagEvent::getId).orElse(null),
                redFlagEventRepository.findAllBySessionIdOrderByOccurredAtDesc(session.getId()).size());
    }

    /** 车辆在赛道上的相对速度（用于前端小车点位的移动速度） */
    private double speedOf(Kart kart) {
        return switch (kart.getModel()) {
            case "RACE" -> 1.0;
            case "FUN" -> 0.8;
            case "DOUBLE" -> 0.65;
            default -> 0.55;
        };
    }

}
