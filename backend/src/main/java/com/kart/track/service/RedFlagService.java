package com.kart.track.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.kart.track.common.BizException;
import com.kart.track.common.Operator;
import com.kart.track.common.OperatorContext;
import com.kart.track.domain.Driver;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.Maintenance;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagCheck;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.RedFlagLap;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.RedFlagDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.MaintenanceRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagCheckRepository;
import com.kart.track.repository.RedFlagEventRepository;
import com.kart.track.repository.RedFlagLapRepository;

/**
 * 红旗事件处置台核心服务。
 *
 * <p>把「红旗触发 → 计时隔离 → 车辆核查 → 主管裁定 → 恢复 / 强制终止」整条链路收在一处：
 * <ul>
 *   <li>发起：行级悲观锁 + 场次状态校验 + uk_active_session 唯一索引，三重保证同场次同时只有一个
 *       ACTIVE；并发/重复请求拿到的是同一个事件、同一套核查清单；</li>
 *   <li>核查：每车一行、结论提交即冻结，一台车退出/送修不影响其它车；送修立刻建待修单并置车维修；</li>
 *   <li>裁定/恢复/终止仅主管；恢复需「全核查 + 全裁定 + 事件仍 ACTIVE」三条件齐备；</li>
 *   <li>强制终止走单次事务收口：场次结束、车手释放、在赛车收车一步到位，
 *       退出/送修车保持状态；任一环节失败整体回滚，不留半完成状态。</li>
 * </ul>
 */
@Service
public class RedFlagService {

    private static final DateTimeFormatter EVENT_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RedFlagEventRepository eventRepository;
    private final RedFlagCheckRepository checkRepository;
    private final RedFlagLapRepository lapRepository;
    private final RaceSessionRepository sessionRepository;
    private final KartRepository kartRepository;
    private final DriverRepository driverRepository;
    private final LapRecordRepository lapRecordRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final SessionService sessionService;
    private final TransactionTemplate transactionTemplate;

    public RedFlagService(RedFlagEventRepository eventRepository,
                          RedFlagCheckRepository checkRepository,
                          RedFlagLapRepository lapRepository,
                          RaceSessionRepository sessionRepository,
                          KartRepository kartRepository,
                          DriverRepository driverRepository,
                          LapRecordRepository lapRecordRepository,
                          MaintenanceRepository maintenanceRepository,
                          SessionService sessionService,
                          PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.checkRepository = checkRepository;
        this.lapRepository = lapRepository;
        this.sessionRepository = sessionRepository;
        this.kartRepository = kartRepository;
        this.driverRepository = driverRepository;
        this.lapRecordRepository = lapRecordRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.sessionService = sessionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================================================ 处置台一屏

    @Transactional(readOnly = true)
    public Views.RedFlagConsoleView console(Long sessionId) {
        RaceSession session = resolveSession(sessionId);
        RedFlagEvent active = eventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE").orElse(null);
        List<Views.RedFlagEventView> history = new ArrayList<>();
        for (RedFlagEvent event : eventRepository.findAllBySessionIdOrderByOccurredAtDesc(session.getId())) {
            history.add(toEventView(event, session));
        }
        return new Views.RedFlagConsoleView(
                session.getId(),
                session.getSessionNo(),
                session.getStatus(),
                TrackDict.sessionStatusName(session.getStatus()),
                active != null,
                active == null ? null : toEventView(active, session),
                history);
    }

    @Transactional(readOnly = true)
    public Views.RedFlagEventView eventDetail(Long eventId) {
        RedFlagEvent event = eventRepository.findById(eventId).orElseThrow(() -> new BizException("红旗事件不存在"));
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        return toEventView(event, session);
    }

    // ============================================================ 发起红旗

    /**
     * 值班人员发起红旗。
     *
     * <p>外层不开事务：用 {@link TransactionTemplate} 开一个「持锁到提交」的短事务做查重+插入，
     * 若已有生效中事件（包括并发请求刚落库的），不开新事务再把同一事件读出来返回。
     * 重复请求返回 {@code created=false} + 同一事件结果，绝不生成两套清单。
     */
    public RaiseOutcome raise(Long sessionId, RedFlagDtos.RaiseReq req) {
        Operator operator = OperatorContext.require();
        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (reason.isEmpty()) {
            throw new BizException("红旗原因不能为空");
        }
        if (reason.length() > 200) {
            throw new BizException("红旗原因不超过 200 字");
        }
        RaceSession session = resolveSession(sessionId);

        // 快路径：已有生效中事件，直接返回同一个
        RedFlagEvent existing = eventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE").orElse(null);
        if (existing != null) {
            return new RaiseOutcome(false, eventDetail(existing.getId()));
        }

        AtomicReference<RedFlagEvent> createdRef = new AtomicReference<>();
        AtomicReference<Boolean> insertedRef = new AtomicReference<>(Boolean.TRUE);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                RaceSession locked = sessionRepository.findByIdForUpdate(session.getId())
                        .orElseThrow(() -> new BizException("场次不存在"));
                // 持行锁后再查一次：并发的另一个事务必须等本行锁释放，届时一定能看到刚提交的 ACTIVE
                RedFlagEvent concurrent = eventRepository.findActiveForUpdate(locked.getId()).orElse(null);
                if (concurrent != null) {
                    insertedRef.set(Boolean.FALSE);
                    createdRef.set(concurrent);
                    return;
                }
                if (!"RUNNING".equals(locked.getStatus())) {
                    throw new BizException("只有「进行中」的场次可以发起红旗，当前为"
                            + TrackDict.sessionStatusName(locked.getStatus()));
                }
                LocalDateTime now = LocalDateTime.now();
                RedFlagEvent event = new RedFlagEvent();
                event.setEventNo(nextEventNo(now, locked.getId()));
                event.setSessionId(locked.getId());
                event.setReason(reason);
                event.setOccurredAt(now);
                event.setStatus("ACTIVE");
                event.setRaisedBy(operator.id());
                event.setCreatedAt(now);
                RedFlagEvent saved = eventRepository.saveAndFlush(event);

                // 以当时仍在赛的车辆生成待核查清单（每车一条，车手取本场最后一次圈速快照）
                Map<Long, Long> kartDriver = new HashMap<>();
                for (LapRecord lap : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(locked.getId())) {
                    kartDriver.putIfAbsent(lap.getKartId(), lap.getDriverId());
                }
                List<Kart> onTrack = kartRepository.findAllByStatusOrderByKartNoAsc("ON_TRACK");
                for (Kart kart : onTrack) {
                    RedFlagCheck check = new RedFlagCheck();
                    check.setEventId(saved.getId());
                    check.setSessionId(locked.getId());
                    check.setKartId(kart.getId());
                    check.setDriverId(kartDriver.get(kart.getId()));
                    check.setCreatedAt(now);
                    checkRepository.save(check);
                }
                checkRepository.flush();

                locked.setStatus("RED_FLAG");
                sessionRepository.save(locked);

                createdRef.set(saved);
            });
        } catch (DataIntegrityViolationException e) {
            // uk_active_session 最后兜底：极端并发下后到者直接复用已生效事件
            RedFlagEvent winner = eventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE")
                    .orElseThrow(() -> e);
            return new RaiseOutcome(false, eventDetail(winner.getId()));
        }
        RedFlagEvent result = createdRef.get();
        if (result == null) {
            throw new BizException("红旗发起失败");
        }
        return new RaiseOutcome(insertedRef.get(), eventDetail(result.getId()));
    }

    /** 发起结果：created 标记是否本次新建（false = 复用已生效的同一事件） */
    public record RaiseOutcome(boolean created, Views.RedFlagEventView eventView) {
    }

    private String nextEventNo(LocalDateTime at, Long sessionId) {
        // 带上场次 id，即使不同场次同一秒发起，事件编号也不会撞 uk_redflag_event_no
        String base = "RF" + at.format(EVENT_NO_FMT) + "S" + sessionId;
        String candidate = base;
        int suffix = 1;
        while (eventRepository.existsByEventNo(candidate)) {
            suffix += 1;
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    // ============================================================ 车辆核查

    /** 值班人员核查一辆车：CONTINUE / RETIRE / REPAIR，提交即冻结 */
    @Transactional
    public Views.RedFlagCheckView submitCheck(Long eventId, Long checkId, RedFlagDtos.CheckReq req) {
        Operator operator = OperatorContext.require();
        String conclusion = req.getConclusion();
        if (!TrackDict.isValidCheckConclusion(conclusion)) {
            throw new BizException("核查结论只能是 可继续/退出比赛/需要维修");
        }
        RedFlagEvent event = eventRepository.findById(eventId).orElseThrow(() -> new BizException("红旗事件不存在"));
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new BizException("该红旗事件已" + TrackDict.redFlagStatusName(event.getStatus()) + "，车辆核查已锁定，不能覆盖");
        }
        RedFlagCheck check = checkRepository.findById(checkId).orElseThrow(() -> new BizException("核查记录不存在"));
        if (!check.getEventId().equals(eventId)) {
            throw new BizException("核查记录与红旗事件不匹配（页面可能已过期，请刷新后重试）");
        }
        if (check.getConclusion() != null) {
            // 过期页面重复提交：明确返回既有结论，不覆盖
            throw new BizException(check.getKartId() + " 号车已核查为「"
                    + TrackDict.checkConclusionName(check.getConclusion()) + "」，结论提交后不可修改");
        }
        Kart kart = kartRepository.findById(check.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));

        LocalDateTime now = LocalDateTime.now();
        check.setConclusion(conclusion);
        check.setRemark(trim(req.getRemark()));
        check.setCheckedBy(operator.id());
        check.setCheckedAt(now);

        if ("REPAIR".equals(conclusion)) {
            // 需要维修：车立即进入维修状态，并形成可追踪的待修维修单（与核查行互相回链）
            String fault = check.getRemark() == null || check.getRemark().isBlank()
                    ? "红旗 #" + event.getEventNo() + " 核查送修" : check.getRemark();
            Maintenance order = new Maintenance();
            String orderNo = nextMaintOrderNo(event, kart);
            order.setOrderNo(orderNo);
            order.setKartId(kart.getId());
            order.setMaintType("BODY");
            order.setFaultDesc(fault);
            order.setWorkHours(BigDecimal.ZERO);
            order.setPartsFee(BigDecimal.ZERO);
            order.setTotalFee(BigDecimal.ZERO);
            order.setWorker(null);
            order.setStatus("WAITING");
            order.setStartTime(now);
            order.setEndTime(null);
            maintenanceRepository.save(order);
            check.setMaintOrderNo(orderNo);

            kart.setStatus("MAINTENANCE");
            kart.setUpdatedAt(now);
            kartRepository.save(kart);
            releaseDriverIfPresent(check, false);
        } else if ("RETIRE".equals(conclusion)) {
            // 退出比赛：车离赛道回可用池、该车手本场结束；其它在赛车的核查结果一概不动
            kart.setStatus("AVAILABLE");
            kart.setUpdatedAt(now);
            kartRepository.save(kart);
            releaseDriverIfPresent(check, false);
        }
        checkRepository.save(check);
        return toCheckView(check, kart, driverNameOf(check.getDriverId()));
    }

    /** 释放该车手（离赛/送修即离开本场）；terminate=true 时为场次终止的统一收口 */
    private void releaseDriverIfPresent(RedFlagCheck check, boolean terminate) {
        if (check.getDriverId() == null) {
            return;
        }
        Driver driver = driverRepository.findById(check.getDriverId()).orElse(null);
        if (driver == null || driver.getSessionId() == null
                || !driver.getSessionId().equals(check.getSessionId())) {
            return;
        }
        driver.setSessionId(null);
        if (!terminate) {
            // 单车离赛即计入其本场成绩；终止时 finishSession 会统一 +1，避免重复
            driver.setTotalSessions(driver.getTotalSessions() + 1);
        }
        driverRepository.save(driver);
    }

    private String nextMaintOrderNo(RedFlagEvent event, Kart kart) {
        String suffix = kart.getKartNo() == null ? String.valueOf(kart.getId())
                : kart.getKartNo().replaceAll("[^0-9]", "");
        String candidate = "MT-RF" + event.getId() + "-" + suffix;
        int n = 1;
        while (maintenanceRepository.existsByOrderNo(candidate)) {
            n += 1;
            candidate = "MT-RF" + event.getId() + "-" + suffix + "-" + n;
        }
        return candidate;
    }

    // ============================================================ 圈速裁定

    /** 主管裁定红旗期间一条圈速：CONFIRMED 确认有效 / VOID 作废；只能裁一次 */
    @Transactional
    public Views.RedFlagLapView ruleLap(Long eventId, Long redFlagLapId, RedFlagDtos.RulingReq req) {
        Operator operator = OperatorContext.requireSupervisor();
        String ruling = req.getRuling();
        if (!"CONFIRMED".equals(ruling) && !"VOID".equals(ruling)) {
            throw new BizException("裁定结果只能是 确认有效/作废");
        }
        RedFlagEvent event = eventRepository.findById(eventId).orElseThrow(() -> new BizException("红旗事件不存在"));
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new BizException("该红旗事件已" + TrackDict.redFlagStatusName(event.getStatus())
                    + "，圈速裁定已锁定，不能覆盖新状态");
        }
        RedFlagLap row = lapRepository.findById(redFlagLapId).orElseThrow(() -> new BizException("待裁定记录不存在"));
        if (!row.getEventId().equals(eventId)) {
            throw new BizException("待裁定记录与红旗事件不匹配（页面可能已过期，请刷新后重试）");
        }
        if (!"PENDING".equals(row.getRuling())) {
            // 过期页面 / 重复点击：明确提示既有裁定，旧记录不会因重复操作再次入榜
            throw new BizException("该圈已于此前裁定为「" + TrackDict.lapRulingName(row.getRuling())
                    + "」，不能重复裁定");
        }
        LapRecord lap = lapRecordRepository.findById(row.getLapId())
                .orElseThrow(() -> new BizException("圈速记录不存在"));
        if ("CONFIRMED".equals(ruling) && !"VALID".equals(lap.getValid())) {
            // 设备判定为出界/碰撞的圈不能确认为有效——先拦住，不改动待裁定状态
            throw new BizException("该圈设备判定为" + TrackDict.lapValidName(lap.getValid())
                    + "，不能确认为有效；请选择作废");
        }

        LocalDateTime now = LocalDateTime.now();
        row.setRuling(ruling);
        row.setRuledBy(operator.id());
        row.setRuledAt(now);
        lapRepository.save(row);

        if ("CONFIRMED".equals(ruling)) {
            applyConfirmedLap(event, lap, now);
        }
        // VOID：保留留底，永不排榜、不刷最佳，不做任何副作用
        return toLapView(row, lap, names());
    }

    /** 确认有效：此时才补排本场最快圈、补刷车手历史最好成绩与车辆里程 */
    private void applyConfirmedLap(RedFlagEvent event, LapRecord lap, LocalDateTime now) {
        // 本场最快圈：排除其它待裁定/作废记录后重算
        Set<Long> excluded = new HashSet<>();
        for (RedFlagLap other : lapRepository.findAllBySessionIdOrderByIdDesc(event.getSessionId())) {
            if (!"CONFIRMED".equals(other.getRuling())) {
                excluded.add(other.getLapId());
            }
        }
        Integer currentBest = null;
        for (LapRecord other : lapRecordRepository
                .findBySessionIdAndValidOrderByLapTimeMsAsc(event.getSessionId(), "VALID")) {
            // 排除其它待裁定/作废记录，以及本次正在确认的圈速自身（基线只能取“别的圈”）
            if (other.getId().equals(lap.getId()) || excluded.contains(other.getId())) {
                continue;
            }
            currentBest = other.getLapTimeMs();
            break;
        }
        if (currentBest == null || lap.getLapTimeMs() < currentBest) {
            for (LapRecord other : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(event.getSessionId())) {
                if (other.isFastest()) {
                    other.setFastest(false);
                    lapRecordRepository.save(other);
                }
            }
            lap.setFastest(true);
        }
        lapRecordRepository.save(lap);

        Driver driver = driverRepository.findById(lap.getDriverId()).orElse(null);
        if (driver != null && (driver.getBestLapMs() == null || driver.getBestLapMs() <= 0
                || lap.getLapTimeMs() < driver.getBestLapMs())) {
            driver.setBestLapMs(lap.getLapTimeMs());
            driverRepository.save(driver);
        }
        Kart kart = kartRepository.findById(lap.getKartId()).orElse(null);
        if (kart != null) {
            RaceSession session = sessionRepository.findById(event.getSessionId()).orElse(null);
            BigDecimal km = session == null ? BigDecimal.valueOf(1.2)
                    : TrackDict.configLapKm(session.getTrackConfig());
            kart.setTotalKm(kart.getTotalKm().add(km));
            kart.setTotalHours(kart.getTotalHours().add(BigDecimal.valueOf(lap.getLapTimeMs() / 3600000.0)));
            kart.setUpdatedAt(now);
            kartRepository.save(kart);
        }
    }

    // ============================================================ 恢复比赛

    /** 主管恢复比赛：全部车辆已核查 + 红旗圈速全部裁定 + 事件仍生效，三者缺一不可 */
    @Transactional
    public Views.RedFlagEventView resume(Long eventId) {
        Operator operator = OperatorContext.requireSupervisor();
        RedFlagEvent event = eventRepository.findById(eventId).orElseThrow(() -> new BizException("红旗事件不存在"));
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new BizException("该红旗事件已" + TrackDict.redFlagStatusName(event.getStatus())
                    + "，不能重复恢复（比赛当前状态以场次为准）");
        }
        long unchecked = checkRepository.countByEventIdAndConclusionIsNull(eventId);
        if (unchecked > 0) {
            throw new BizException("还有 " + unchecked + " 台车未完成核查，全部核查完毕后才能恢复比赛");
        }
        long pendingLaps = lapRepository.countByEventIdAndRuling(eventId, "PENDING");
        if (pendingLaps > 0) {
            throw new BizException("还有 " + pendingLaps + " 条红旗期间圈速未裁定，全部裁定后才能恢复比赛");
        }
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"RED_FLAG".equals(session.getStatus())) {
            throw new BizException("场次当前为「" + TrackDict.sessionStatusName(session.getStatus())
                    + "」，不在本次红旗事件控制下，恢复请求已过期");
        }

        LocalDateTime now = LocalDateTime.now();
        event.setStatus("RESUMED");
        event.setResumedBy(operator.id());
        event.setResumedAt(now);
        eventRepository.save(event);

        // 恢复后继续记圈；仍在赛的车（CONTINUE）保持 ON_TRACK，RETIRE/REPAIR 已在核查时离赛/送修
        session.setStatus("RUNNING");
        sessionRepository.save(session);
        return eventDetail(eventId);
    }

    // ============================================================ 强制终止

    /**
     * 主管强制终止：单次事务一次性收口。
     *
     * <p>场次置 FINISHED + 释放全部仍占用该车手 + 仍在赛车辆收车；退出/送修车辆保持其状态。
     * 任一步失败整体回滚——不会留下「场次已结束但车手仍占用」或「车辆已放行但维修记录缺失」。
     * 不要求核查/裁定已完成（红旗可能在混乱中被直接终止），但未裁定圈速在终止时统一作废留底。
     */
    @Transactional
    public Views.RedFlagEventView terminate(Long eventId) {
        Operator operator = OperatorContext.requireSupervisor();
        RedFlagEvent event = eventRepository.findById(eventId).orElseThrow(() -> new BizException("红旗事件不存在"));
        if (!"ACTIVE".equals(event.getStatus())) {
            throw new BizException("该红旗事件已" + TrackDict.redFlagStatusName(event.getStatus())
                    + "，不能重复终止");
        }
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"RED_FLAG".equals(session.getStatus())) {
            throw new BizException("场次当前为「" + TrackDict.sessionStatusName(session.getStatus())
                    + "」，不在本次红旗事件控制下，终止请求已过期");
        }

        LocalDateTime now = LocalDateTime.now();

        // 仍未裁定的圈速随终止收口为「作废」，保留留底且永不入榜
        for (RedFlagLap row : lapRepository.findAllByEventIdOrderByIdAsc(eventId)) {
            if ("PENDING".equals(row.getRuling())) {
                row.setRuling("VOID");
                row.setRuledBy(operator.id());
                row.setRuledAt(now);
                lapRepository.save(row);
            }
        }

        // 场次收口（结束 + 释放车手 + 在赛车收车），退出/送修车保持状态
        sessionService.finishSession(session, now);

        // finishSession 只按圈速记录收车；核查清单是“发起红旗时全部在赛车”的快照
        // （含尚未跑出圈的车），这些车若仍在赛也要在此一并收车，送修车保持 MAINTENANCE
        for (RedFlagCheck check : checkRepository.findAllByEventIdOrderByKartIdAsc(eventId)) {
            Kart kart = kartRepository.findById(check.getKartId()).orElse(null);
            if (kart != null && "ON_TRACK".equals(kart.getStatus())) {
                kart.setStatus("AVAILABLE");
                kart.setUpdatedAt(now);
                kartRepository.save(kart);
            }
        }

        event.setStatus("TERMINATED");
        event.setTerminatedBy(operator.id());
        event.setTerminatedAt(now);
        eventRepository.save(event);
        return eventDetail(eventId);
    }

    // ============================================================ 视图组装

    private RaceSession resolveSession(Long sessionId) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId).orElseThrow(() -> new BizException("场次不存在"));
        }
        List<RaceSession> running = sessionRepository.findAllByStatusOrderByStartTimeAsc("RUNNING");
        if (!running.isEmpty()) {
            return running.get(0);
        }
        List<RaceSession> flagged = sessionRepository.findAllByStatusOrderByStartTimeAsc("RED_FLAG");
        if (!flagged.isEmpty()) {
            return flagged.get(0);
        }
        List<RaceSession> all = sessionRepository.findAllByOrderByStartTimeDesc();
        if (all.isEmpty()) {
            throw new BizException("还没有任何场次");
        }
        return all.get(0);
    }

    private record Names(Map<Long, String> driver, Map<Long, String> kart, Map<Long, Kart> kartMap,
                         Map<Long, String> session) {
    }

    private Names names() {
        Map<Long, String> driver = new HashMap<>();
        for (Driver d : driverRepository.findAll()) {
            driver.put(d.getId(), d.getName());
        }
        Map<Long, String> kart = new HashMap<>();
        Map<Long, Kart> kartMap = new HashMap<>();
        for (Kart k : kartRepository.findAll()) {
            kart.put(k.getId(), k.getKartNo());
            kartMap.put(k.getId(), k);
        }
        Map<Long, String> session = new HashMap<>();
        for (RaceSession s : sessionRepository.findAll()) {
            session.put(s.getId(), s.getSessionNo());
        }
        return new Names(driver, kart, kartMap, session);
    }

    private String driverNameOf(Long driverId) {
        if (driverId == null) {
            return null;
        }
        return driverRepository.findById(driverId).map(Driver::getName).orElse(null);
    }

    private Views.RedFlagEventView toEventView(RedFlagEvent event, RaceSession session) {
        Names names = names();
        List<Views.RedFlagCheckView> checks = new ArrayList<>();
        for (RedFlagCheck check : checkRepository.findAllByEventIdOrderByKartIdAsc(event.getId())) {
            Kart kart = names.kartMap().get(check.getKartId());
            checks.add(toCheckView(check, kart, names.driver().get(check.getDriverId())));
        }
        List<Views.RedFlagLapView> laps = new ArrayList<>();
        for (RedFlagLap row : lapRepository.findAllByEventIdOrderByIdAsc(event.getId())) {
            LapRecord lap = lapRecordRepository.findById(row.getLapId()).orElse(null);
            if (lap != null) {
                laps.add(toLapView(row, lap, names));
            }
        }

        long total = checks.size();
        long checked = checks.stream().filter(Views.RedFlagCheckView::checked).count();
        long cont = checkRepository.countByEventIdAndConclusion(event.getId(), "CONTINUE");
        long retire = checkRepository.countByEventIdAndConclusion(event.getId(), "RETIRE");
        long repair = checkRepository.countByEventIdAndConclusion(event.getId(), "REPAIR");
        long pending = lapRepository.countByEventIdAndRuling(event.getId(), "PENDING");
        long confirmed = lapRepository.countByEventIdAndRuling(event.getId(), "CONFIRMED");
        long voided = lapRepository.countByEventIdAndRuling(event.getId(), "VOID");
        boolean allChecked = total > 0 && checked == total;
        boolean allRuled = pending == 0;
        LocalDateTime closedAt = event.getResumedAt() != null ? event.getResumedAt() : event.getTerminatedAt();
        long durationSec = closedAt == null ? Duration.between(event.getOccurredAt(), LocalDateTime.now()).getSeconds()
                : Duration.between(event.getOccurredAt(), closedAt).getSeconds();
        if (durationSec < 0) {
            durationSec = 0;
        }
        String finalAction = "RESUMED".equals(event.getStatus()) ? "恢复比赛"
                : "TERMINATED".equals(event.getStatus()) ? "强制终止" : null;
        String finalBy = "RESUMED".equals(event.getStatus()) ? event.getResumedBy()
                : "TERMINATED".equals(event.getStatus()) ? event.getTerminatedBy() : null;
        return new Views.RedFlagEventView(
                event.getId(),
                event.getEventNo(),
                event.getSessionId(),
                session.getSessionNo(),
                event.getReason(),
                event.getStatus(),
                TrackDict.redFlagStatusName(event.getStatus()),
                "ACTIVE".equals(event.getStatus()),
                event.getRaisedBy(),
                event.getOccurredAt(),
                event.getResumedBy(),
                event.getResumedAt(),
                event.getTerminatedBy(),
                event.getTerminatedAt(),
                durationSec,
                formatDuration(durationSec),
                total,
                checked,
                cont,
                retire,
                repair,
                laps.size(),
                pending,
                confirmed,
                voided,
                allChecked,
                allRuled,
                "ACTIVE".equals(event.getStatus()) && allChecked && allRuled
                        && "RED_FLAG".equals(session.getStatus()),
                finalAction,
                finalBy,
                closedAt,
                checks,
                laps);
    }

    private Views.RedFlagCheckView toCheckView(RedFlagCheck check, Kart kart, String driverName) {
        return new Views.RedFlagCheckView(
                check.getId(),
                check.getEventId(),
                check.getKartId(),
                kart == null ? String.valueOf(check.getKartId()) : kart.getKartNo(),
                kart == null ? "—" : TrackDict.kartModelName(kart.getModel()),
                kart == null ? null : kart.getStatus(),
                kart == null ? "—" : TrackDict.kartStatusName(kart.getStatus()),
                check.getDriverId(),
                driverName,
                check.getConclusion(),
                TrackDict.checkConclusionName(check.getConclusion()),
                check.getConclusion() != null,
                check.getRemark(),
                check.getCheckedBy(),
                check.getCheckedAt(),
                check.getMaintOrderNo());
    }

    private Views.RedFlagLapView toLapView(RedFlagLap row, LapRecord lap, Names names) {
        return new Views.RedFlagLapView(
                row.getId(),
                lap.getId(),
                row.getEventId(),
                row.getSessionId(),
                names.session().get(row.getSessionId()),
                lap.getDriverId(),
                names.driver().get(lap.getDriverId()),
                lap.getKartId(),
                names.kart().get(lap.getKartId()),
                lap.getLapIndex(),
                lap.getLapTimeMs(),
                TrackDict.formatLap(lap.getLapTimeMs()),
                lap.isFastest(),
                lap.getValid(),
                TrackDict.lapValidName(lap.getValid()),
                lap.getCreatedAt(),
                row.getRuling(),
                TrackDict.lapRulingName(row.getRuling()),
                row.getRuledBy(),
                row.getRuledAt());
    }

    static String formatDuration(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
