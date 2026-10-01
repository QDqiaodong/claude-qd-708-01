package com.kart.track.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
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
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.TimingAlert;
import com.kart.track.domain.TimingDevice;
import com.kart.track.domain.TimingDeviceSession;
import com.kart.track.domain.TimingGap;
import com.kart.track.domain.TimingHeartbeat;
import com.kart.track.domain.TimingLapEvent;
import com.kart.track.domain.TimingTakeover;
import com.kart.track.domain.TimingSource;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.TimingDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagLapRepository;
import com.kart.track.repository.TimingAlertRepository;
import com.kart.track.repository.TimingDeviceRepository;
import com.kart.track.repository.TimingDeviceSessionRepository;
import com.kart.track.repository.TimingGapRepository;
import com.kart.track.repository.TimingHeartbeatRepository;
import com.kart.track.repository.TimingLapEventRepository;
import com.kart.track.repository.TimingTakeoverRepository;
import com.kart.track.repository.TimingSourceRepository;

/**
 * 计时设备韧性台核心服务。
 *
 * <p>把设备身份、心跳状态、圈速顺序校验、断线补偿、主备接管边界、重复事件去重、
 * 红旗兼容、权限收口和失败恢复收在一处：
 * <ul>
 *   <li><b>身份与关联</b>：上报只认设备码；设备未关联本场 / 场次已结束 / 设备被隔离，
 *       圈速上报都得到明确拒绝结果，绝不混入本场成绩；</li>
 *   <li><b>顺序与去重</b>：(设备,事件uid) 唯一保证重放幂等；(设备,场次,序号) 唯一保证
 *       一条序号只落一条圈速。序号连续且来源有效才 ACCEPTED；发现缺口后其后数据 PENDING
 *       待对账，不进实时排行榜/车手历史最好；</li>
 *   <li><b>补偿检查点</b>：confirmed_seq 随每条入账前移并独立提交，补偿中途再断只从
 *       检查点继续，已确认进度不回退、已落账事件不重复处理；</li>
 *   <li><b>主备边界</b>：接管记录与当前来源切换同一事务完成；边界前只认主、边界后只认备，
 *       越界事件进隔离区由主管丢弃或重新归段；</li>
 *   <li><b>红旗兼容</b>：入账时按事件【实际产生时刻】判定红旗窗口，红旗圈挂起待裁定；
 *       场次恢复后只处理尚未核销事件，已入账/已裁定数据不随重连再次生效。</li>
 * </ul>
 */
@Service
public class TimingResilienceService {

    private static final DateTimeFormatter TAKEOVER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TimingDeviceRepository deviceRepository;
    private final TimingDeviceSessionRepository dsRepository;
    private final TimingHeartbeatRepository heartbeatRepository;
    private final TimingAlertRepository alertRepository;
    private final TimingLapEventRepository eventRepository;
    private final TimingGapRepository gapRepository;
    private final TimingTakeoverRepository takeoverRepository;
    private final TimingSourceRepository sourceRepository;
    private final RaceSessionRepository sessionRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final RedFlagLapRepository redFlagLapRepository;
    private final LapBookingService lapBookingService;
    private final TransactionTemplate requiresNew;

    public TimingResilienceService(TimingDeviceRepository deviceRepository,
                                   TimingDeviceSessionRepository dsRepository,
                                   TimingHeartbeatRepository heartbeatRepository,
                                   TimingAlertRepository alertRepository,
                                   TimingLapEventRepository eventRepository,
                                   TimingGapRepository gapRepository,
                                   TimingTakeoverRepository takeoverRepository,
                                   TimingSourceRepository sourceRepository,
                                   RaceSessionRepository sessionRepository,
                                   DriverRepository driverRepository,
                                   KartRepository kartRepository,
                                   RedFlagLapRepository redFlagLapRepository,
                                   LapBookingService lapBookingService,
                                   PlatformTransactionManager transactionManager) {
        this.deviceRepository = deviceRepository;
        this.dsRepository = dsRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.alertRepository = alertRepository;
        this.eventRepository = eventRepository;
        this.gapRepository = gapRepository;
        this.takeoverRepository = takeoverRepository;
        this.sourceRepository = sourceRepository;
        this.sessionRepository = sessionRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.redFlagLapRepository = redFlagLapRepository;
        this.lapBookingService = lapBookingService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    // ============================================================ 心跳上报

    /**
     * 设备心跳。同心跳序号重复上报幂等（不更新水位）；设备被隔离仍记录心跳留痕，
     * 但返回状态 ISOLATED 并提示其圈速上报会被拒绝。
     */
    @Transactional
    public Views.HeartbeatResult heartbeat(TimingDtos.HeartbeatReq req) {
        String code = req.getDeviceCode().trim();
        TimingDevice device = deviceRepository.findByDeviceCodeForUpdate(code)
                .orElseThrow(() -> new BizException("未登记的计时设备：" + code + "，心跳不予接收"));
        LocalDateTime now = LocalDateTime.now();
        long hbSeq = req.getHbSeq() != null ? req.getHbSeq()
                : (device.getLastHbSeq() == null ? 1 : device.getLastHbSeq() + 1);
        boolean duplicate = heartbeatRepository.findByDeviceIdAndHbSeq(device.getId(), hbSeq).isPresent();

        if (!duplicate) {
            LocalDateTime occurredAt = req.getOccurredAt() != null ? req.getOccurredAt() : now;
            long gap = device.getLastHeartbeatAt() == null ? 0
                    : Math.max(0, Duration.between(device.getLastHeartbeatAt(), occurredAt).toMillis());
            TimingHeartbeat hb = new TimingHeartbeat();
            hb.setDeviceId(device.getId());
            hb.setSessionId(device.getSessionId());
            hb.setHbSeq(hbSeq);
            hb.setOccurredAt(occurredAt);
            hb.setReceivedAt(now);
            hb.setGapMs(gap);
            heartbeatRepository.save(hb);

            // 只接受更新的心跳（乱序的旧时刻不回退水位）
            if (device.getLastHeartbeatAt() == null || !occurredAt.isBefore(device.getLastHeartbeatAt())) {
                device.setLastHeartbeatAt(occurredAt);
                device.setLastHbSeq(hbSeq);
                device.setHbGapMs(gap);
                deviceRepository.save(device);
            }
            if (!device.isQuarantined()) {
                if (gap > TrackDict.HEARTBEAT_FLAP_GAP_MS) {
                    raiseAlert(device, "FLAPPING", device.getName() + " 心跳间隔 " + (gap / 1000)
                            + " 秒，明显超出正常节奏，判定波动");
                }
                long age = Math.max(0, Duration.between(occurredAt, now).toMillis());
                if ("ONLINE".equals(TrackDict.deriveDeviceStatus(age, gap))) {
                    resolveDeviceAlerts(device.getId(), List.of("OFFLINE", "FLAPPING"));
                }
            }
        }
        String status = currentStatus(device, now);
        String message = device.isQuarantined()
                ? "设备处于隔离状态：心跳已留痕，但圈速上报一律拒绝"
                : (duplicate ? "重复心跳序号，已忽略" : "心跳已接收");
        return new Views.HeartbeatResult(device.getId(), device.getDeviceCode(), hbSeq, duplicate,
                status, TrackDict.deviceStatusName(status), true, message);
    }

    /** 离线/波动扫描：年龄超阈值产生告警，恢复在线自动消除（不依赖人工刷新页面） */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void sweepDeviceStatus() {
        requiresNew.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            for (TimingDevice device : deviceRepository.findAll()) {
                if (!device.isEnabled() || device.isQuarantined()) {
                    continue;
                }
                String derived = currentStatus(device, now);
                if ("OFFLINE".equals(derived)) {
                    raiseAlert(device, "OFFLINE", device.getName() + " 已超过 "
                            + (TrackDict.HEARTBEAT_OFFLINE_AGE_MS / 1000) + " 秒无心跳，判定离线");
                } else if ("FLAPPING".equals(derived)) {
                    raiseAlert(device, "FLAPPING", device.getName() + " 心跳节奏异常，判定波动");
                    resolveDeviceAlerts(device.getId(), List.of("OFFLINE"));
                } else if ("ONLINE".equals(derived)) {
                    resolveDeviceAlerts(device.getId(), List.of("OFFLINE", "FLAPPING"));
                }
            }
        });
    }

    // ============================================================ 圈速事件上报（核心链路）

    /**
     * 圈速事件上报（实时 / 断线重放批量调用同一入口）。
     *
     * <p>首条事件的「身份锁 + 校验 + 定级 + 落账」在一个独立事务内完成；随后按序补链的
     * 每条 PENDING 也各自独立提交——批量重放中途再断，已确认的进度和已落账圈速都保留，
     * 重放同一条事件则由 (设备,eventUid) 唯一键直接返回首次结果，绝不形成第二条圈速。
     */
    public Views.LapIngestResult ingestLap(TimingDtos.LapEventReq req) {
        String code = req.getDeviceCode().trim();
        String eventUid = req.getEventUid().trim();
        java.util.concurrent.atomic.AtomicBoolean duplicateRef = new java.util.concurrent.atomic.AtomicBoolean(false);

        // 独立事务：持设备行锁串行化同设备上报，完成查重、校验、定级与首条入账
        TimingLapEvent first = requiresNew.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            TimingDevice device = deviceRepository.findByDeviceCodeForUpdate(code)
                    .orElseThrow(() -> new BizException("未登记的计时设备：" + code + "，圈速事件不予接收"));
            if (device.isQuarantined()) {
                throw new BizException("设备 " + code + " 处于隔离状态，圈速事件不予接收（不会进入本场成绩）");
            }
            Long sessionId = device.getSessionId();
            if (sessionId == null) {
                throw new BizException("设备 " + code + " 未关联任何本场，圈速事件不予接收（不会进入本场成绩）");
            }
            RaceSession session = sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BizException("设备关联的场次不存在"));
            if ("FINISHED".equals(session.getStatus()) || "CANCELED".equals(session.getStatus())) {
                throw new BizException("场次 " + session.getSessionNo()
                        + TrackDict.sessionStatusName(session.getStatus()) + "，圈速事件不予接收（不会进入本场成绩）");
            }
            if ("PENDING".equals(session.getStatus())) {
                throw new BizException("场次 " + session.getSessionNo() + " 尚未开赛，圈速事件不予接收");
            }
            TimingLapEvent duplicate = eventRepository
                    .findByDeviceIdAndEventUid(device.getId(), eventUid).orElse(null);
            if (duplicate != null) {
                duplicateRef.set(true);
                return duplicate;
            }
            Driver driver = driverRepository.findById(req.getDriverId())
                    .orElseThrow(() -> new BizException("车手不存在，事件 " + eventUid + " 不予接收"));
            Kart kart = kartRepository.findById(req.getKartId())
                    .orElseThrow(() -> new BizException("车辆不存在，事件 " + eventUid + " 不予接收"));
            String valid = req.getValid() == null || req.getValid().isBlank() ? "VALID" : req.getValid().trim();
            if (!TrackDict.isValidLapValid(valid)) {
                throw new BizException("圈速判定只能是 有效/无效-出界/无效-碰撞");
            }
            LocalDateTime occurredAt = req.getOccurredAt() != null ? req.getOccurredAt() : now;
            return stageAndAccept(device, session, driver, kart, eventUid, req.getSeq(),
                    req.getLapTimeMs(), valid, occurredAt, now);
        });

        // 首条是重放：直接返回首次结果，不触发补链
        if (duplicateRef.get()) {
            return ingestResult(first, true, List.of());
        }

        // 独立事务逐条按序补齐其后的 PENDING 链（各自提交，中断不丢已确认进度）
        List<TimingLapEvent> advanced = new ArrayList<>();
        if ("ACCEPTED".equals(first.getStage())) {
            drainPending(first.getSessionId(), first.getDeviceId(), advanced);
        }
        return ingestResult(first, false, advanced);
    }

    /**
     * 在已持设备行锁的事务内对一条新事件定级并落账。
     * 边界判定、序号缺口、正式入账都在这里；返回带最新状态的事件。
     */
    private TimingLapEvent stageAndAccept(TimingDevice device, RaceSession session, Driver driver, Kart kart,
                                          String eventUid, long seq, int ms, String valid,
                                          LocalDateTime occurredAt, LocalDateTime now) {
        Long sessionId = session.getId();

        TimingLapEvent event = new TimingLapEvent();
        event.setEventUid(eventUid);
        event.setDeviceId(device.getId());
        event.setSessionId(sessionId);
        event.setDriverId(driver.getId());
        event.setKartId(kart.getId());
        event.setSeq(seq);
        event.setLapTimeMs(ms);
        event.setValid(valid);
        event.setOccurredAt(occurredAt);
        event.setReceivedAt(now);
        event.setSourceRole(device.getRole());

        // 序号槽位预检：(设备,场次,序号) 一条序号只允许一条事件（含已入隔离区/已丢弃的），
        // 重复序号给明确业务结果，不抛底层唯一约束错误
        eventRepository.findByDeviceIdAndSessionIdAndSeq(device.getId(), sessionId, seq)
                .ifPresent(existing -> {
                    if (!existing.getEventUid().equals(eventUid)) {
                        throw new BizException("序号 " + seq + " 已存在"
                                + TrackDict.lapEventStageName(existing.getStage())
                                + "事件（" + existing.getEventUid() + "），同一序号不能再上报不同事件");
                    }
                });

        // ---- 来源 / 接管边界校验：决定是正常链路还是隔离区 ----
        TimingSource source = sourceRepository.findBySessionId(sessionId).orElse(null);
        TimingTakeover boundary = latestDoneTakeover(sessionId);
        boolean isCurrentSource = source != null && source.getDeviceId().equals(device.getId());
        boolean isPastSource = boundary != null && boundary.getOldDeviceId().equals(device.getId());
        if (!isCurrentSource && !isPastSource) {
            throw new BizException("设备 " + device.getDeviceCode() + " 不是本场「"
                    + (source == null ? "未设置" : deviceNameOf(source.getDeviceId()))
                    + "」数据来源，圈速事件不予接收");
        }
        if (boundary != null) {
            if (isPastSource && occurredAt.isAfter(boundary.getBoundaryAt())) {
                // 旧主设备晚到的边界后事件 → 隔离区
                quarantineNewEvent(event, "OLD_AFTER_BOUNDARY",
                        "旧主设备在接管边界（" + fmt(boundary.getBoundaryAt()) + "）之后产生的圈速，未入本场成绩");
                return event;
            }
            if (isCurrentSource && occurredAt.isBefore(boundary.getBoundaryAt())) {
                // 备用设备提前产生的边界前事件 → 隔离区
                quarantineNewEvent(event, "BACKUP_BEFORE_BOUNDARY",
                        "接管设备在边界（" + fmt(boundary.getBoundaryAt()) + "）之前产生的圈速，未入本场成绩");
                return event;
            }
        }

        // ---- 圈速合理性：越界数据进隔离区留证，不入榜 ----
        if (ms < TrackDict.MIN_LAP_MS || ms > TrackDict.MAX_LAP_MS) {
            quarantineNewEvent(event, "BAD_LAP_TIME",
                    "圈速 " + ms + "ms 超出合理区间 " + (TrackDict.MIN_LAP_MS / 1000) + "~"
                            + (TrackDict.MAX_LAP_MS / 1000) + " 秒");
            return event;
        }

        // ---- 序号链：水位 + 缺口 ----
        TimingDeviceSession watermark = watermarkOf(device.getId(), sessionId, now);
        if (seq <= watermark.getConfirmedSeq()) {
            // 与已确认序号不同 uid：设备侧序号异常，明确拒绝（唯一索引也会兜底）
            throw new BizException("事件 " + eventUid + " 序号 " + seq + " 已被其他事件确认入账，"
                    + "当前确认序号为 " + watermark.getConfirmedSeq() + "，疑似设备序号异常");
        }
        TimingGap openGap = openGapOf(device.getId(), sessionId);
        long expected = watermark.getConfirmedSeq() + 1;

        // 主管已 DISCARDED 的序号视为已核销但不形成圈速：连续上报时先把这些序号的水位补齐
        while (seq > expected) {
            TimingLapEvent discarded = eventRepository
                    .findByDeviceIdAndSessionIdAndSeq(device.getId(), sessionId, expected).orElse(null);
            if (discarded == null || !"DISCARDED".equals(discarded.getStage())) {
                break;
            }
            watermark.setConfirmedSeq(expected);
            dsRepository.save(watermark);
            expected += 1;
            closeGapIfDue(device.getId(), sessionId, watermark.getConfirmedSeq(), now);
        }

        if (seq == expected) {
            // 下一号连续事件：直接入账（即使仍有已开窗缺口——本条正是重放补到的缺口起点，
            // 外层提交后会 drainPending 继续按序补其后的待对账事件）
            acceptEvent(event, session, driver, kart, watermark, now);
            closeGapIfDue(device.getId(), sessionId, watermark.getConfirmedSeq(), now);
            return event;
        }

        // seq > expected：缺口仍然存在，开窗/扩展缺口，事件 PENDING 挂在缺口上
        TimingGap gap = openGap;
        if (gap == null) {
            gap = new TimingGap();
            gap.setDeviceId(device.getId());
            gap.setSessionId(sessionId);
            gap.setMissingFrom(expected);
            gap.setMissingTo(seq - 1);
            gap.setStatus("OPEN");
            gap.setOpenedAt(now);
            gap.setPendingCount(0);
            gap = gapRepository.save(gap);
            raiseAlert(device, "GAP", device.getName() + " 圈速序号缺失 "
                    + gap.getMissingFrom() + "~" + gap.getMissingTo() + "，其后数据进入待对账");
        } else if (seq - 1 > gap.getMissingTo()) {
            gap.setMissingTo(seq - 1);
        }
        event.setStage("PENDING");
        event.setGapId(gap.getId());
        TimingLapEvent saved = eventRepository.save(event);
        gap.setPendingCount(recountPending(gap.getId()));
        gapRepository.save(gap);
        return saved;
    }

    /** 重新统计一个缺口上仍待对账的事件数 */
    private int recountPending(Long gapId) {
        return (int) eventRepository.findAllByGapIdOrderBySeqAsc(gapId).stream()
                .filter(e -> "PENDING".equals(e.getStage())).count();
    }

    /**
     * 缺口收口（首条入账 / 补链入账 / 主管归段共用）：确认序号已越过某开放缺口的区间末端、
     * 且其后不再有待对账事件时关窗。关窗时把仍挂在缺口上的事件解除挂起标记。
     */
    private void closeGapIfDue(Long deviceId, Long sessionId, long confirmedSeq, LocalDateTime now) {
        List<TimingGap> openGaps = gapRepository.findAllByDeviceIdAndSessionIdOrderByIdAsc(deviceId, sessionId)
                .stream().filter(g -> "OPEN".equals(g.getStatus())).toList();
        for (TimingGap gap : openGaps) {
            if (confirmedSeq < gap.getMissingFrom()) {
                continue;
            }
            int pending = recountPending(gap.getId());
            if (confirmedSeq >= gap.getMissingTo() && pending == 0) {
                gap.setStatus("FILLED");
                gap.setFilledAt(now);
                gap.setPendingCount(0);
                gapRepository.save(gap);
                resolveDeviceAlerts(deviceId, List.of("GAP"));
            } else {
                gap.setPendingCount(pending);
                gapRepository.save(gap);
            }
        }
    }

    /** 正式入账一条事件：写 lap_record（红旗窗口挂起待裁定），推进 confirmed_seq 检查点 */
    private void acceptEvent(TimingLapEvent event, RaceSession session, Driver driver, Kart kart,
                             TimingDeviceSession watermark, LocalDateTime now) {
        RedFlagEvent covering = lapBookingService.findCoveringRedFlag(session.getId(), event.getOccurredAt());
        LapBookingService.BookedLap booked = lapBookingService.book(
                session, driver, kart, event.getLapTimeMs(), event.getValid(), event.getOccurredAt(), covering);
        event.setStage("ACCEPTED");
        event.setLapId(booked.lap().getId());
        event.setGapId(null);
        eventRepository.save(event);
        watermark.setConfirmedSeq(event.getSeq());
        dsRepository.save(watermark);
    }

    /** 按序补齐挂起链：从 confirmed+1 开始，下一号 PENDING 入账 / DISCARDED 只推进水位；遇真正缺口停止 */
    private void drainPending(Long sessionId, Long deviceId, List<TimingLapEvent> advanced) {
        for (int guard = 0; guard < 500; guard++) {
            TimingDeviceSession watermark = dsRepository.findByDeviceIdAndSessionId(deviceId, sessionId).orElse(null);
            if (watermark == null) {
                return;
            }
            long expect = watermark.getConfirmedSeq() + 1;
            TimingLapEvent next = eventRepository
                    .findAllByDeviceIdAndSessionIdOrderBySeqAsc(deviceId, sessionId).stream()
                    .filter(e -> e.getSeq() == expect
                            && ("PENDING".equals(e.getStage()) || "DISCARDED".equals(e.getStage())))
                    .findFirst().orElse(null);
            if (next == null) {
                return;
            }
            TimingLapEvent accepted = requiresNew.execute(status -> acceptPending(next.getId()));
            if (accepted == null) {
                return;
            }
            advanced.add(accepted);
        }
    }

    /** 在独立事务里把一条 PENDING 事件入账；若因此补齐缺口则关窗并消除告警 */
    /**
     * 在独立事务里把链头事件处理掉：PENDING 入账；DISCARDED 只推进水位不形成圈速。
     * 返回处理后的事件；前面仍有真缺口（不是期待序号）返回 null。
     */
    private TimingLapEvent acceptPending(Long eventId) {
        TimingLapEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null || (!"PENDING".equals(event.getStage()) && !"DISCARDED".equals(event.getStage()))) {
            return null;
        }
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        TimingDevice device = deviceRepository.findById(event.getDeviceId())
                .orElseThrow(() -> new BizException("设备不存在"));
        TimingDeviceSession watermark = watermarkOf(device.getId(), session.getId(), LocalDateTime.now());
        if (event.getSeq() != watermark.getConfirmedSeq() + 1) {
            return null; // 前面仍有真缺口，不能跳号
        }
        LocalDateTime now = LocalDateTime.now();
        if ("DISCARDED".equals(event.getStage())) {
            watermark.setConfirmedSeq(event.getSeq());
            dsRepository.save(watermark);
        } else {
            Driver driver = driverRepository.findById(event.getDriverId())
                    .orElseThrow(() -> new BizException("车手不存在"));
            Kart kart = kartRepository.findById(event.getKartId())
                    .orElseThrow(() -> new BizException("车辆不存在"));
            acceptEvent(event, session, driver, kart, watermark, now);
        }
        closeGapIfDue(device.getId(), session.getId(), watermark.getConfirmedSeq(), now);
        return event;
    }

    private void quarantineNewEvent(TimingLapEvent event, String reason, String note) {
        event.setStage("QUARANTINED");
        event.setQuarantineReason(reason);
        event.setQuarantineNote(note);
        eventRepository.save(event);
    }

    /**
     * 在当前事务内继续按序补链（主管重新归段/丢弃链头后调用）：
     * 期待序号的 PENDING 事件入账；期待序号若已被主管 DISCARDED 则只推进水位、不形成圈速；
     * 遇到真正的缺失序号（无任何事件）立即停止。
     */
    private void drainPendingInTx(TimingDevice device, RaceSession session,
                                  TimingDeviceSession watermark, LocalDateTime now, String operatorId) {
        for (int guard = 0; guard < 500; guard++) {
            long expect = watermark.getConfirmedSeq() + 1;
            TimingLapEvent next = eventRepository
                    .findAllByDeviceIdAndSessionIdOrderBySeqAsc(device.getId(), session.getId()).stream()
                    .filter(e -> e.getSeq() == expect
                            && ("PENDING".equals(e.getStage()) || "DISCARDED".equals(e.getStage())))
                    .findFirst().orElse(null);
            if (next == null) {
                return;
            }
            if ("DISCARDED".equals(next.getStage())) {
                watermark.setConfirmedSeq(next.getSeq());
                dsRepository.save(watermark);
            } else {
                Driver nd = driverRepository.findById(next.getDriverId()).orElseThrow();
                Kart nk = kartRepository.findById(next.getKartId()).orElseThrow();
                next.setDecidedBy(operatorId);
                next.setDecidedAt(now);
                acceptEvent(next, session, nd, nk, watermark, now);
            }
            closeGapIfDue(device.getId(), session.getId(), watermark.getConfirmedSeq(), now);
            eventRepository.flush();
            dsRepository.flush();
        }
    }

    // ============================================================ 主备接管

    /** 值班人员发起接管请求（待主管执行）；重复请求返回既有请求，不覆盖 */
    @Transactional
    public Views.TimingTakeoverView requestTakeover(Long sessionId, TimingDtos.TakeoverReqReq req) {
        Operator operator = OperatorContext.require();
        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (reason.isEmpty()) {
            throw new BizException("接管原因不能为空");
        }
        if (reason.length() > 200) {
            throw new BizException("接管原因不超过 200 字");
        }
        RaceSession session = liveSession(sessionId);
        TimingTakeover existing = takeoverRepository.findBySessionIdAndStatus(sessionId, "REQUESTED").orElse(null);
        if (existing != null) {
            throw new BizException("本场已有待主管执行的接管请求（备用设备："
                    + deviceNameOf(existing.getNewDeviceId()) + "），请勿重复发起");
        }
        TimingSource source = sourceRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new BizException("本场尚未设置主计时设备，无从接管"));
        TimingDevice oldDevice = deviceRepository.findById(source.getDeviceId())
                .orElseThrow(() -> new BizException("当前主设备不存在"));
        TimingDevice backup = deviceRepository.findById(req.getBackupDeviceId())
                .orElseThrow(() -> new BizException("备用设备不存在"));
        validateBackupForTakeover(session, oldDevice, backup);

        LocalDateTime now = LocalDateTime.now();
        TimingTakeover takeover = new TimingTakeover();
        takeover.setTakeoverNo(nextTakeoverNo(session));
        takeover.setSessionId(sessionId);
        takeover.setOldDeviceId(oldDevice.getId());
        takeover.setNewDeviceId(backup.getId());
        takeover.setBoundarySeq(confirmedSeqOf(oldDevice.getId(), sessionId));
        takeover.setStatus("REQUESTED");
        takeover.setReason(reason);
        takeover.setRequestedBy(operator.id());
        takeover.setRequestedAt(now);
        takeover = takeoverRepository.save(takeover);

        raiseAlert(backup, "TAKEOVER_REQ", "值班人员 " + operator.id() + " 请求由 " + backup.getName()
                + " 接管 " + oldDevice.getName() + "，等待主管执行");
        return toTakeoverView(takeover);
    }

    /** 值班人员撤销自己发起、尚未执行的接管请求 */
    @Transactional
    public Views.TimingTakeoverView cancelTakeover(Long takeoverId) {
        OperatorContext.require();
        TimingTakeover takeover = requireTakeover(takeoverId);
        if (!"REQUESTED".equals(takeover.getStatus())) {
            throw new BizException("接管请求已" + TrackDict.takeoverStatusName(takeover.getStatus()) + "，不能撤销");
        }
        takeover.setStatus("CANCELED");
        takeover.setExecutedBy(OperatorContext.require().id());
        takeover.setExecutedAt(LocalDateTime.now());
        takeover.setExecNote("值班人员撤销");
        takeoverRepository.save(takeover);
        resolveDeviceAlerts(takeover.getNewDeviceId(), List.of("TAKEOVER_REQ"));
        return toTakeoverView(takeover);
    }

    /** 主管驳回接管请求 */
    @Transactional
    public Views.TimingTakeoverView rejectTakeover(Long takeoverId, TimingDtos.RejectReq req) {
        Operator operator = OperatorContext.requireSupervisor();
        String note = req.getNote() == null ? "" : req.getNote().trim();
        if (note.isEmpty()) {
            throw new BizException("驳回原因不能为空");
        }
        TimingTakeover takeover = requireTakeover(takeoverId);
        if (!"REQUESTED".equals(takeover.getStatus())) {
            throw new BizException("接管请求已" + TrackDict.takeoverStatusName(takeover.getStatus()) + "，不能驳回");
        }
        liveSession(takeover.getSessionId());
        takeover.setStatus("REJECTED");
        takeover.setExecutedBy(operator.id());
        takeover.setExecutedAt(LocalDateTime.now());
        takeover.setExecNote(note);
        takeoverRepository.save(takeover);
        resolveDeviceAlerts(takeover.getNewDeviceId(), List.of("TAKEOVER_REQ"));
        return toTakeoverView(takeover);
    }

    /**
     * 主管执行接管：单事务收口，任一步失败整体回滚。
     *
     * <p>校验通过后在同一事务内：落定边界时刻/序号 → 两台设备的场次水位建档 →
     * timing_source 切到备用设备 → 接管置 DONE。绝不会出现两台设备同时是当前来源，
     * 或接管记录已存在但排行榜仍沿用旧来源的半完成状态。
     */
    @Transactional
    public Views.TimingTakeoverView executeTakeover(Long takeoverId) {
        Operator operator = OperatorContext.requireSupervisor();
        TimingTakeover takeover = requireTakeover(takeoverId);
        if (!"REQUESTED".equals(takeover.getStatus())) {
            throw new BizException("接管请求已" + TrackDict.takeoverStatusName(takeover.getStatus())
                    + "，不能重复执行");
        }
        RaceSession session = liveSession(takeover.getSessionId());
        TimingSource source = sourceRepository.findBySessionId(session.getId())
                .orElseThrow(() -> new BizException("本场当前数据来源缺失，接管无法继续"));
        if (!source.getDeviceId().equals(takeover.getOldDeviceId())) {
            throw new BizException("本场当前来源已不是请求中的旧主设备（页面已过期），接管已中止，数据来源保持不变");
        }
        TimingDevice oldDevice = deviceRepository.findById(takeover.getOldDeviceId())
                .orElseThrow(() -> new BizException("旧主设备不存在"));
        TimingDevice backup = deviceRepository.findById(takeover.getNewDeviceId())
                .orElseThrow(() -> new BizException("备用设备不存在"));
        validateBackupForTakeover(session, oldDevice, backup);

        LocalDateTime now = LocalDateTime.now();
        long boundarySeq = confirmedSeqOf(oldDevice.getId(), session.getId());

        // 两台设备的场次水位：旧主封存边界序号；备用从边界之后开始，其边界前事件一律隔离
        TimingDeviceSession oldDs = watermarkOf(oldDevice.getId(), session.getId(), now);
        oldDs.setBoundarySeq(boundarySeq);
        dsRepository.save(oldDs);
        TimingDeviceSession newDs = watermarkOf(backup.getId(), session.getId(), now);
        newDs.setBoundarySeq(boundarySeq);
        dsRepository.save(newDs);

        // 备用设备关联到本场（若尚未关联），身份不变、来源切换以 timing_source 为准
        if (!session.getId().equals(backup.getSessionId())) {
            backup.setSessionId(session.getId());
            deviceRepository.save(backup);
        }

        source.setDeviceId(backup.getId());
        source.setTakeoverId(takeover.getId());
        source.setSwitchedAt(now);
        sourceRepository.save(source);

        takeover.setBoundarySeq(boundarySeq);
        takeover.setBoundaryAt(now);
        takeover.setStatus("DONE");
        takeover.setExecutedBy(operator.id());
        takeover.setExecutedAt(now);
        takeoverRepository.save(takeover);

        resolveDeviceAlerts(backup.getId(), List.of("TAKEOVER_REQ"));
        return toTakeoverView(takeover);
    }

    private void validateBackupForTakeover(RaceSession session, TimingDevice oldDevice, TimingDevice backup) {
        if (backup.getId().equals(oldDevice.getId())) {
            throw new BizException("备用设备不能与当前主设备相同");
        }
        if (!backup.isEnabled()) {
            throw new BizException(backup.getName() + " 已停用，不能接管");
        }
        if (backup.isQuarantined()) {
            throw new BizException(backup.getName() + " 处于隔离状态，不能接管");
        }
        if (!"STANDBY".equals(backup.getRole())) {
            throw new BizException(backup.getName() + " 不是备用设备，不能接管");
        }
        if (!backup.getTrackCode().equals(session.getTrackConfig())) {
            throw new BizException(backup.getName() + " 属于" + TrackDict.trackConfigName(backup.getTrackCode())
                    + "赛道，与本场" + TrackDict.trackConfigName(session.getTrackConfig()) + "不匹配");
        }
        if (backup.getSessionId() != null && !backup.getSessionId().equals(session.getId())) {
            throw new BizException(backup.getName() + " 已关联其他场次，不能接管本场");
        }
        if (oldDevice.isQuarantined()) {
            throw new BizException("当前主设备处于隔离状态，请先由主管解除隔离或更换设备");
        }
    }

    // ============================================================ 隔离区处置 / 设备隔离

    /** 主管丢弃一条隔离区事件：留底但永不入榜 */
    @Transactional
    public Views.TimingLapEventView discardQuarantined(Long eventId, TimingDtos.QuarantineDecisionReq req) {
        return decideQuarantined(eventId, "DISCARD", req.getNote());
    }

    /** 主管把隔离区事件重新归段：按主管认定跳过边界拦截，重走序号链（入正常账或继续待对账） */
    @Transactional
    public Views.TimingLapEventView resegmentQuarantined(Long eventId, TimingDtos.QuarantineDecisionReq req) {
        return decideQuarantined(eventId, "RESEGMENT", req.getNote());
    }

    private Views.TimingLapEventView decideQuarantined(Long eventId, String decision, String note) {
        Operator operator = OperatorContext.requireSupervisor();
        if (!"DISCARD".equals(decision) && !"RESEGMENT".equals(decision)) {
            throw new BizException("处理决定只能是 丢弃/重新归段");
        }
        TimingLapEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new BizException("隔离事件不存在"));
        if (!"QUARANTINED".equals(event.getStage())) {
            throw new BizException("该事件已处置为「" + TrackDict.lapEventStageName(event.getStage())
                    + "」，不能重复处理（页面可能已过期，请刷新）");
        }
        TimingDevice device = deviceRepository.findById(event.getDeviceId())
                .orElseThrow(() -> new BizException("设备不存在"));
        LocalDateTime now = LocalDateTime.now();

        if ("DISCARD".equals(decision)) {
            event.setStage("DISCARDED");
            event.setDecidedBy(operator.id());
            event.setDecidedAt(now);
            if (note != null && !note.isBlank()) {
                event.setQuarantineNote(cut(event.getQuarantineNote() + "｜主管丢弃：" + note.trim()));
            }
            eventRepository.save(event);

            // 丢弃 = 该序号已核销但不形成圈速：若它正好是链头，推进水位并继续补其后的待对账事件
            RaceSession session = sessionRepository.findById(event.getSessionId())
                    .orElseThrow(() -> new BizException("场次不存在"));
            TimingDeviceSession watermark = watermarkOf(device.getId(), session.getId(), now);
            if (event.getSeq() == watermark.getConfirmedSeq() + 1) {
                watermark.setConfirmedSeq(event.getSeq());
                dsRepository.save(watermark);
                eventRepository.flush();
                dsRepository.flush();
                drainPendingInTx(device, session, watermark, now, operator.id());
            }
            return toEventView(event, names());
        }

        // RESEGMENT：主管认定来源有效，清除边界隔离原因，回到序号链定级
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        Driver driver = driverRepository.findById(event.getDriverId())
                .orElseThrow(() -> new BizException("车手不存在"));
        Kart kart = kartRepository.findById(event.getKartId())
                .orElseThrow(() -> new BizException("车辆不存在"));
        TimingDeviceSession watermark = watermarkOf(device.getId(), session.getId(), now);
        if (event.getSeq() <= watermark.getConfirmedSeq()) {
            throw new BizException("序号 " + event.getSeq() + " 已有确认入账事件，不能重新归段");
        }
        event.setQuarantineReason(null);
        event.setQuarantineNote((note == null || note.isBlank())
                ? "主管核实后重新归段" : "主管核实后重新归段：" + note.trim());

        TimingGap gap = openGapOf(device.getId(), session.getId());
        if (event.getSeq() == watermark.getConfirmedSeq() + 1) {
            if (event.getLapTimeMs() < TrackDict.MIN_LAP_MS || event.getLapTimeMs() > TrackDict.MAX_LAP_MS) {
                throw new BizException("该圈速仍超出合理区间，不能归入正常计圈链，请选择丢弃");
            }
            event.setDecidedBy(operator.id());
            event.setDecidedAt(now);
            acceptEvent(event, session, driver, kart, watermark, now);
            // 同事务内继续按序补其后挂起事件；主管决定同时记录到每条补入的事件上
            drainPendingInTx(device, session, watermark, now, operator.id());
        } else {
            if (gap == null) {
                gap = new TimingGap();
                gap.setDeviceId(device.getId());
                gap.setSessionId(session.getId());
                gap.setMissingFrom(watermark.getConfirmedSeq() + 1);
                gap.setMissingTo(event.getSeq() - 1);
                gap.setStatus("OPEN");
                gap.setOpenedAt(now);
                gap.setPendingCount(0);
                gap = gapRepository.save(gap);
                raiseAlert(device, "GAP", device.getName() + " 重新归段时仍缺序号 "
                        + gap.getMissingFrom() + "~" + gap.getMissingTo());
            }
            event.setStage("PENDING");
            event.setGapId(gap.getId());
            event.setDecidedBy(operator.id());
            event.setDecidedAt(now);
            eventRepository.save(event);
            gap.setPendingCount(recountPending(gap.getId()));
            gapRepository.save(gap);
        }
        return toEventView(event, names());
    }

    /** 主管隔离设备：隔离后其圈速一律拒绝；心跳仍留痕 */
    @Transactional
    public Views.TimingDeviceView quarantineDevice(Long deviceId, TimingDtos.QuarantineReq req) {
        Operator operator = OperatorContext.requireSupervisor();
        TimingDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BizException("设备不存在"));
        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (reason.isEmpty()) {
            throw new BizException("隔离原因不能为空");
        }
        if (device.isQuarantined()) {
            throw new BizException("设备已处于隔离状态（" + device.getQuarantineReason() + "），不能重复隔离");
        }
        LocalDateTime now = LocalDateTime.now();
        device.setQuarantined(true);
        device.setQuarantineReason(reason);
        device.setQuarantinedBy(operator.id());
        device.setQuarantinedAt(now);
        deviceRepository.save(device);
        raiseAlert(device, "QUARANTINED", device.getName() + " 被主管隔离：" + reason);
        return deviceView(device, consoleContext(device.getSessionId()));
    }

    /** 主管解除设备隔离 */
    @Transactional
    public Views.TimingDeviceView releaseDevice(Long deviceId) {
        Operator operator = OperatorContext.requireSupervisor();
        TimingDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BizException("设备不存在"));
        if (!device.isQuarantined()) {
            throw new BizException("设备未被隔离，无需解除");
        }
        device.setQuarantined(false);
        device.setQuarantineReason(null);
        device.setQuarantinedBy(null);
        device.setQuarantinedAt(null);
        deviceRepository.save(device);
        resolveDeviceAlerts(device.getId(), List.of("QUARANTINED"));
        return deviceView(device, consoleContext(device.getSessionId()));
    }

    // ============================================================ 设备绑定 / 注册（主管运维）

    /** 主管注册一台新计时设备（稳定身份码） */
    @Transactional
    public Views.TimingDeviceView registerDevice(String code, String name, String trackCode, String role) {
        OperatorContext.requireSupervisor();
        String c = code == null ? "" : code.trim();
        String n = name == null ? "" : name.trim();
        if (c.isEmpty() || n.isEmpty()) {
            throw new BizException("设备码与设备名称不能为空");
        }
        if (c.length() > 40) {
            throw new BizException("设备码不超过 40 字符");
        }
        if (!TrackDict.isValidTrackConfig(trackCode)) {
            throw new BizException("所属赛道只能是 半场/全场/夜间");
        }
        if (!TrackDict.isValidDeviceRole(role)) {
            throw new BizException("设备角色只能是 主设备/备用设备");
        }
        if (deviceRepository.existsByDeviceCode(c)) {
            throw new BizException("设备码 " + c + " 已存在，设备身份必须稳定唯一");
        }
        TimingDevice device = new TimingDevice();
        device.setDeviceCode(c);
        device.setName(n);
        device.setTrackCode(trackCode);
        device.setRole(role);
        device.setEnabled(true);
        device.setCreatedAt(LocalDateTime.now());
        device = deviceRepository.save(device);
        return deviceView(device, consoleContext(null));
    }

    /** 主管把设备关联到某场次（或传空解除关联），并可调整主备角色 */
    @Transactional
    public Views.TimingDeviceView bindDevice(Long deviceId, TimingDtos.BindReq req) {
        OperatorContext.requireSupervisor();
        TimingDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BizException("设备不存在"));
        if (!TrackDict.isValidDeviceRole(req.getRole())) {
            throw new BizException("设备角色只能是 主设备/备用设备");
        }
        Long targetSessionId = req.getSessionId();
        RaceSession target = null;
        if (targetSessionId != null) {
            target = sessionRepository.findById(targetSessionId)
                    .orElseThrow(() -> new BizException("场次不存在"));
            if (!target.getTrackConfig().equals(device.getTrackCode())) {
                throw new BizException("设备属于" + TrackDict.trackConfigName(device.getTrackCode())
                        + "赛道，不能关联到" + TrackDict.trackConfigName(target.getTrackConfig()) + "场次");
            }
            if ("FINISHED".equals(target.getStatus()) || "CANCELED".equals(target.getStatus())) {
                throw new BizException("该场次已结束/已取消，不能再关联计时设备");
            }
        }

        Long oldSessionId = device.getSessionId();
        if (oldSessionId != null && !oldSessionId.equals(targetSessionId)) {
            TimingSource src = sourceRepository.findBySessionId(oldSessionId).orElse(null);
            if (src != null && src.getDeviceId().equals(deviceId)
                    && ("RUNNING".equals(sessionStatus(oldSessionId)) || "RED_FLAG".equals(sessionStatus(oldSessionId)))) {
                throw new BizException("该设备是进行中场次的当前数据来源，必须先完成主备接管才能改绑");
            }
        }

        device.setSessionId(targetSessionId);
        device.setRole(req.getRole());
        deviceRepository.save(device);

        if (target != null && "PRIMARY".equals(req.getRole())) {
            TimingSource source = sourceRepository.findBySessionId(targetSessionId).orElse(null);
            if (source == null) {
                TimingSource created = new TimingSource();
                created.setSessionId(targetSessionId);
                created.setDeviceId(deviceId);
                created.setSwitchedAt(LocalDateTime.now());
                sourceRepository.save(created);
                watermarkOf(deviceId, targetSessionId, LocalDateTime.now());
            }
        }
        return deviceView(device, consoleContext(targetSessionId));
    }

    // ============================================================ 告警确认

    /** 值班人员/主管确认一条告警（幂等：重复确认返回既有状态，不复活） */
    @Transactional
    public Views.TimingAlertView acknowledgeAlert(Long alertId) {
        Operator operator = OperatorContext.require();
        TimingAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new BizException("告警不存在"));
        if ("ACTIVE".equals(alert.getStatus())) {
            alert.setStatus("ACKED");
            alert.setAckedBy(operator.id());
            alert.setAckedAt(LocalDateTime.now());
            alertRepository.save(alert);
        }
        return toAlertView(alert, names());
    }

    // ============================================================ 韧性台一屏

    @Transactional(readOnly = true)
    public Views.TimingConsoleView console(Long sessionId) {
        RaceSession session = resolveSession(sessionId);
        Ctx ctx = consoleContext(session.getId());

        List<Views.TimingDeviceView> devices = new ArrayList<>();
        for (TimingDevice device : deviceRepository.findAllBySessionIdOrderByDeviceCodeAsc(session.getId())) {
            devices.add(deviceView(device, ctx));
        }
        List<Views.TimingGapView> gaps = ctx.allGaps().stream()
                .map(g -> toGapView(g, ctx.names())).toList();
        List<Views.TimingLapEventView> pending = ctx.eventsByStage().getOrDefault("PENDING", List.of()).stream()
                .map(e -> toEventView(e, ctx.names())).toList();
        List<Views.TimingLapEventView> quarantined = ctx.eventsByStage().getOrDefault("QUARANTINED", List.of()).stream()
                .map(e -> toEventView(e, ctx.names())).toList();
        TimingSource currentSource = ctx.sources().get(session.getId());
        boolean sessionLive = "RUNNING".equals(session.getStatus()) || "RED_FLAG".equals(session.getStatus());
        List<Views.TimingTakeoverView> takeovers = takeoverRepository
                .findAllBySessionIdOrderByIdAsc(session.getId()).stream()
                .map(t -> toTakeoverView(t, takeoverExecutable(t, sessionLive, currentSource))).toList();
        Views.TimingTakeoverView pendingTakeover = takeoverRepository
                .findBySessionIdAndStatus(session.getId(), "REQUESTED")
                .map(t -> toTakeoverView(t, takeoverExecutable(t, sessionLive, currentSource))).orElse(null);
        List<Views.TimingAlertView> alerts = activeAlertsOfSession(session.getId(), ctx.names());

        long accepted = eventRepository.findAllBySessionIdOrderBySeqAscDeviceIdAsc(session.getId()).stream()
                .filter(e -> "ACCEPTED".equals(e.getStage())).count();
        long pendingCount = eventRepository.countBySessionIdAndStage(session.getId(), "PENDING");
        long quarantinedCount = eventRepository.countBySessionIdAndStage(session.getId(), "QUARANTINED");
        long openGapCount = gapRepository.countBySessionIdAndStatus(session.getId(), "OPEN");

        return new Views.TimingConsoleView(
                session.getId(), session.getSessionNo(), session.getStatus(),
                TrackDict.sessionStatusName(session.getStatus()),
                session.getTrackConfig(), TrackDict.trackConfigName(session.getTrackConfig()),
                sourceView(session.getId()), pendingTakeover, devices, gaps, pending, quarantined,
                takeovers, alerts, buildTimeline(session, ctx),
                accepted, pendingCount, quarantinedCount, openGapCount);
    }

    /** 设备管理列表（主管运维；可按赛道过滤），未关联本场的设备也能看到 */
    @Transactional(readOnly = true)
    public List<Views.TimingDeviceView> listDevices(String trackCode) {
        Ctx ctx = consoleContext(null);
        List<TimingDevice> devices = trackCode == null || trackCode.isBlank()
                ? deviceRepository.findAllByOrderByDeviceCodeAsc()
                : deviceRepository.findAllByTrackCodeOrderByDeviceCodeAsc(trackCode.trim());
        return devices.stream().map(d -> deviceView(d, ctx)).toList();
    }

    @Transactional(readOnly = true)
    public Views.TimingDeviceView deviceDetail(Long deviceId) {
        TimingDevice device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new BizException("设备不存在"));
        return deviceView(device, consoleContext(device.getSessionId()));
    }

    // ============================================================ 视图组装

    /** 一屏的共享上下文（名称/水位/缺口/事件缓存），避免逐设备全表扫描 */
    private record Ctx(Names names, Map<Long, TimingSource> sources,
                       Map<String, TimingDeviceSession> watermarks,
                       List<TimingGap> allGaps,
                       Map<String, List<TimingLapEvent>> eventsByStage,
                       Map<Long, List<TimingLapEvent>> eventsByDevice,
                       Map<Long, List<TimingAlert>> activeAlertsByDevice) {
    }

    private static String watermarkKey(Long deviceId, Long sessionId) {
        return deviceId + ":" + (sessionId == null ? 0 : sessionId);
    }

    private Ctx consoleContext(Long sessionId) {
        Names names = names();
        Map<Long, TimingSource> sources = new HashMap<>();
        for (TimingSource s : sourceRepository.findAll()) {
            sources.put(s.getSessionId(), s);
        }
        Map<String, TimingDeviceSession> watermarks = new HashMap<>();
        for (TimingDeviceSession ds : dsRepository.findAll()) {
            watermarks.put(watermarkKey(ds.getDeviceId(), ds.getSessionId()), ds);
        }
        List<TimingGap> allGaps = sessionId == null ? List.of()
                : gapRepository.findAllBySessionIdOrderByIdAsc(sessionId);
        Map<String, List<TimingLapEvent>> byStage = new HashMap<>();
        Map<Long, List<TimingLapEvent>> byDevice = new HashMap<>();
        if (sessionId != null) {
            for (TimingLapEvent e : eventRepository.findAllBySessionIdOrderByReceivedAtDesc(sessionId)) {
                byStage.computeIfAbsent(e.getStage(), k -> new ArrayList<>()).add(e);
                byDevice.computeIfAbsent(e.getDeviceId(), k -> new ArrayList<>()).add(e);
            }
        }
        Map<Long, List<TimingAlert>> alerts = new HashMap<>();
        for (TimingAlert a : alertRepository.findAllByStatusOrderByRaisedAtDesc("ACTIVE")) {
            alerts.computeIfAbsent(a.getDeviceId(), k -> new ArrayList<>()).add(a);
        }
        return new Ctx(names, sources, watermarks, allGaps, byStage, byDevice, alerts);
    }

    private Views.TimingDeviceView deviceView(TimingDevice device, Ctx ctx) {
        LocalDateTime now = LocalDateTime.now();
        Long sid = device.getSessionId();
        TimingDeviceSession ds = sid == null ? null
                : ctx.watermarks().get(watermarkKey(device.getId(), sid));
        TimingGap openGap = ctx.allGaps().stream()
                .filter(g -> g.getDeviceId().equals(device.getId()) && "OPEN".equals(g.getStatus()))
                .findFirst().orElse(null);
        long pendingCount = ctx.eventsByDevice().getOrDefault(device.getId(), List.of()).stream()
                .filter(e -> "PENDING".equals(e.getStage())).count();
        TimingSource source = sid == null ? null : ctx.sources().get(sid);
        boolean currentSource = source != null && source.getDeviceId().equals(device.getId());
        String status = currentStatus(device, now);
        long age = device.getLastHeartbeatAt() == null ? Long.MAX_VALUE
                : Math.max(0, Duration.between(device.getLastHeartbeatAt(), now).toMillis());

        List<Views.TimingHeartbeatView> hbViews = heartbeatRepository
                .findTop30ByDeviceIdOrderByOccurredAtDesc(device.getId()).stream()
                .map(h -> new Views.TimingHeartbeatView(
                        h.getId(), h.getDeviceId(), h.getHbSeq(), h.getOccurredAt(), h.getReceivedAt(),
                        h.getGapMs(),
                        Math.max(0, Duration.between(h.getOccurredAt(), now).toMillis()),
                        h.getGapMs() > TrackDict.HEARTBEAT_FLAP_GAP_MS))
                .toList();
        List<Views.TimingAlertView> alertViews = ctx.activeAlertsByDevice()
                .getOrDefault(device.getId(), List.of()).stream()
                .map(a -> toAlertView(a, ctx.names())).toList();

        return new Views.TimingDeviceView(
                device.getId(), device.getDeviceCode(), device.getName(),
                device.getTrackCode(), TrackDict.trackConfigName(device.getTrackCode()),
                device.getRole(), TrackDict.deviceRoleName(device.getRole()),
                sid, sid == null ? null : ctx.names().session().get(sid),
                sid != null, device.isQuarantined(), device.getQuarantineReason(),
                device.getQuarantinedBy(), device.getQuarantinedAt(), device.isEnabled(),
                status, TrackDict.deviceStatusName(status),
                device.getLastHeartbeatAt(), age, device.getLastHbSeq(), device.getHbGapMs(),
                ds == null ? 0 : ds.getConfirmedSeq(),
                ds == null ? null : ds.getBoundarySeq(),
                openGap == null ? null : openGap.getMissingFrom(),
                openGap == null ? null : openGap.getMissingTo(),
                pendingCount, currentSource,
                device.getVersion() == null ? 0 : device.getVersion(),
                hbViews, alertViews);
    }

    private Views.TimingGapView toGapView(TimingGap gap, Names names) {
        TimingDevice d = names.device().get(gap.getDeviceId());
        return new Views.TimingGapView(
                gap.getId(), gap.getDeviceId(),
                d == null ? String.valueOf(gap.getDeviceId()) : d.getDeviceCode(),
                d == null ? "—" : d.getName(),
                gap.getMissingFrom(), gap.getMissingTo(),
                gap.getStatus(), TrackDict.gapStatusName(gap.getStatus()),
                gap.getOpenedAt(), gap.getFilledAt(), gap.getPendingCount());
    }

    private Views.TimingLapEventView toEventView(TimingLapEvent e, Names names) {
        TimingDevice d = names.device().get(e.getDeviceId());
        boolean redFlagHeld = lapIdHeldByRedFlag(e.getLapId());
        return new Views.TimingLapEventView(
                e.getId(), e.getEventUid(), e.getDeviceId(),
                d == null ? String.valueOf(e.getDeviceId()) : d.getDeviceCode(),
                d == null ? "—" : d.getName(),
                e.getSourceRole(), TrackDict.deviceRoleName(e.getSourceRole()),
                e.getSessionId(),
                e.getDriverId(), names.driver().get(e.getDriverId()),
                e.getKartId(), names.kart().get(e.getKartId()),
                e.getSeq(), e.getLapTimeMs(), TrackDict.formatLap(e.getLapTimeMs()),
                e.getValid(), TrackDict.lapValidName(e.getValid()),
                e.getOccurredAt(), e.getReceivedAt(),
                e.getStage(), TrackDict.lapEventStageName(e.getStage()),
                e.getLapId(), e.getGapId(),
                e.getQuarantineReason(), TrackDict.quarantineReasonName(e.getQuarantineReason()),
                e.getQuarantineNote(), e.getDecidedBy(), e.getDecidedAt(), redFlagHeld);
    }

    private boolean lapIdHeldByRedFlag(Long lapId) {
        // PENDING 即仍被红旗规则挂起；已确认有效/作废后 ruling 不再是 PENDING
        return lapId != null && redFlagLapRepository.existsByLapIdAndRuling(lapId, "PENDING");
    }

    private Views.TimingTakeoverView toTakeoverView(TimingTakeover t) {
        boolean executable = "REQUESTED".equals(t.getStatus());
        return toTakeoverView(t, executable);
    }

    /** 接管请求只有在「场次在赛 + 当前来源仍是请求中的旧主」时才可执行，过期请求显示不可执行 */
    private boolean takeoverExecutable(TimingTakeover t, boolean sessionLive, TimingSource currentSource) {
        return "REQUESTED".equals(t.getStatus()) && sessionLive
                && currentSource != null && currentSource.getDeviceId().equals(t.getOldDeviceId());
    }

    private Views.TimingTakeoverView toTakeoverView(TimingTakeover t, boolean executable) {
        Names names = names();
        TimingDevice oldD = names.device().get(t.getOldDeviceId());
        TimingDevice newD = names.device().get(t.getNewDeviceId());
        return new Views.TimingTakeoverView(
                t.getId(), t.getTakeoverNo(), t.getSessionId(), names.session().get(t.getSessionId()),
                t.getOldDeviceId(),
                oldD == null ? String.valueOf(t.getOldDeviceId()) : oldD.getDeviceCode(),
                oldD == null ? "—" : oldD.getName(),
                t.getNewDeviceId(),
                newD == null ? String.valueOf(t.getNewDeviceId()) : newD.getDeviceCode(),
                newD == null ? "—" : newD.getName(),
                t.getBoundarySeq(), t.getBoundaryAt(),
                t.getStatus(), TrackDict.takeoverStatusName(t.getStatus()),
                t.getReason(), t.getRequestedBy(), t.getRequestedAt(),
                t.getExecutedBy(), t.getExecutedAt(), t.getExecNote(),
                executable && "REQUESTED".equals(t.getStatus()));
    }

    private Views.TimingAlertView toAlertView(TimingAlert a, Names names) {
        TimingDevice d = names.device().get(a.getDeviceId());
        return new Views.TimingAlertView(
                a.getId(), a.getDeviceId(),
                d == null ? String.valueOf(a.getDeviceId()) : d.getDeviceCode(),
                d == null ? "—" : d.getName(),
                a.getSessionId(), a.getType(), TrackDict.deviceAlertTypeName(a.getType()),
                a.getMessage(), a.getStatus(), TrackDict.deviceAlertStatusName(a.getStatus()),
                a.getRaisedAt(), a.getAckedBy(), a.getAckedAt());
    }

    private Views.TimingSourceView sourceView(Long sessionId) {
        TimingSource source = sourceRepository.findBySessionId(sessionId).orElse(null);
        if (source == null) {
            return null;
        }
        TimingDevice d = deviceRepository.findById(source.getDeviceId()).orElse(null);
        return new Views.TimingSourceView(
                source.getDeviceId(),
                d == null ? String.valueOf(source.getDeviceId()) : d.getDeviceCode(),
                d == null ? "—" : d.getName(),
                d == null ? null : d.getRole(),
                d == null ? null : TrackDict.deviceRoleName(d.getRole()),
                source.getTakeoverId(), source.getSwitchedAt());
    }

    private List<Views.TimingAlertView> activeAlertsOfSession(Long sessionId, Names names) {
        List<Views.TimingAlertView> list = new ArrayList<>();
        for (TimingDevice device : deviceRepository.findAllBySessionIdOrderByDeviceCodeAsc(sessionId)) {
            for (TimingAlert alert : alertRepository.findAllByDeviceIdOrderByRaisedAtDesc(device.getId())) {
                if ("ACTIVE".equals(alert.getStatus())) {
                    list.add(toAlertView(alert, names));
                }
            }
        }
        return list;
    }

    /** 时间线：心跳快照 + 接管边界 + 缺口开窗/关窗 + 最近圈速事件，按时刻倒序回看一场比赛 */
    private List<Views.TimingTimelineItem> buildTimeline(RaceSession session, Ctx ctx) {
        List<Views.TimingTimelineItem> items = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (TimingDevice device : deviceRepository.findAllBySessionIdOrderByDeviceCodeAsc(session.getId())) {
            if (device.getLastHeartbeatAt() != null) {
                String st = currentStatus(device, now);
                String tone = switch (st) {
                    case "ONLINE" -> "ok";
                    case "FLAPPING" -> "warn";
                    case "OFFLINE" -> "danger";
                    default -> "mute";
                };
                items.add(new Views.TimingTimelineItem("HEARTBEAT", "心跳", device.getLastHeartbeatAt(),
                        device.getId(), device.getDeviceCode(),
                        device.getName() + " · " + TrackDict.deviceStatusName(st),
                        "最近心跳序号 " + device.getLastHbSeq() + "，间隔 "
                                + (device.getHbGapMs() == null ? "—" : device.getHbGapMs() / 1000.0 + "s"),
                        tone));
            }
        }
        for (TimingTakeover t : takeoverRepository.findAllBySessionIdOrderByIdAsc(session.getId())) {
            if (t.getBoundaryAt() != null) {
                items.add(new Views.TimingTimelineItem("TAKEOVER", "接管边界", t.getBoundaryAt(),
                        t.getNewDeviceId(), t.getNewDeviceId() == null ? null : deviceCodeOf(t.getNewDeviceId()),
                        "数据来源切换：" + deviceNameOf(t.getOldDeviceId()) + " → " + deviceNameOf(t.getNewDeviceId()),
                        "边界序号 " + t.getBoundarySeq() + "，之前只认主设备、之后只认备用设备；"
                                + t.getExecutedBy() + " 执行",
                        "danger"));
            }
            items.add(new Views.TimingTimelineItem("TAKEOVER_REQ", "接管请求", t.getRequestedAt(),
                    t.getNewDeviceId(), deviceCodeOf(t.getNewDeviceId()),
                    TrackDict.takeoverStatusName(t.getStatus()) + "：" + deviceNameOf(t.getNewDeviceId())
                            + " 拟接管 " + deviceNameOf(t.getOldDeviceId()),
                    t.getReason(), "REQUESTED".equals(t.getStatus()) ? "warn" : "mute"));
        }
        for (TimingGap gap : ctx.allGaps()) {
            items.add(new Views.TimingTimelineItem("GAP_OPEN", "发现缺口", gap.getOpenedAt(),
                    gap.getDeviceId(), deviceCodeOf(gap.getDeviceId()),
                    deviceNameOf(gap.getDeviceId()) + " 序号缺失 "
                            + gap.getMissingFrom() + "~" + gap.getMissingTo(),
                    "缺口之后的圈速先保留为待对账，不进排行榜", "warn"));
            if (gap.getFilledAt() != null) {
                items.add(new Views.TimingTimelineItem("GAP_FILLED", "缺口补齐", gap.getFilledAt(),
                        gap.getDeviceId(), deviceCodeOf(gap.getDeviceId()),
                        deviceNameOf(gap.getDeviceId()) + " 缺失序号已按序补齐",
                        "待对账事件已按序补入正常计圈链", "ok"));
            }
        }
        List<TimingLapEvent> sessionEvents = eventRepository
                .findAllBySessionIdOrderByReceivedAtDesc(session.getId());
        for (TimingLapEvent e : sessionEvents.stream().limit(60).toList()) {
            String tone = switch (e.getStage()) {
                case "ACCEPTED" -> "ok";
                case "PENDING" -> "warn";
                case "QUARANTINED" -> "danger";
                default -> "mute";
            };
            String detail = "序号 #" + e.getSeq() + " · " + TrackDict.formatLap(e.getLapTimeMs())
                    + " · " + ctx.names().driver().get(e.getDriverId())
                    + " · " + TrackDict.lapEventStageName(e.getStage());
            if ("QUARANTINED".equals(e.getStage()) || "DISCARDED".equals(e.getStage())) {
                detail += " · " + TrackDict.quarantineReasonName(e.getQuarantineReason());
            }
            items.add(new Views.TimingTimelineItem("LAP_" + e.getStage(), "圈速事件", e.getOccurredAt(),
                    e.getDeviceId(), deviceCodeOf(e.getDeviceId()),
                    deviceNameOf(e.getDeviceId()) + " · " + e.getEventUid(), detail, tone));
        }
        items.sort(Comparator.comparing(Views.TimingTimelineItem::at,
                Comparator.nullsFirst(Comparator.reverseOrder())));
        return items;
    }

    // ============================================================ 辅助

    private RaceSession resolveSession(Long sessionId) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId)
                    .orElseThrow(() -> new BizException("场次不存在"));
        }
        return sessionRepository.findAllByStatusOrderByStartTimeAsc("RUNNING").stream().findFirst()
                .or(() -> sessionRepository.findAllByStatusOrderByStartTimeAsc("RED_FLAG").stream().findFirst())
                .or(() -> sessionRepository.findAllByOrderByStartTimeDesc().stream().findFirst())
                .orElseThrow(() -> new BizException("还没有任何场次"));
    }

    private RaceSession liveSession(Long sessionId) {
        RaceSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"RUNNING".equals(session.getStatus()) && !"RED_FLAG".equals(session.getStatus())) {
            throw new BizException("只有「进行中/红旗处理中」的场次可以操作计时接管，当前为"
                    + TrackDict.sessionStatusName(session.getStatus()));
        }
        return session;
    }

    private String sessionStatus(Long sessionId) {
        return sessionRepository.findById(sessionId).map(RaceSession::getStatus).orElse(null);
    }

    private TimingTakeover requireTakeover(Long id) {
        return takeoverRepository.findById(id).orElseThrow(() -> new BizException("接管记录不存在"));
    }

    /** 该场次最近一次已完成接管（决定事件来源边界） */
    private TimingTakeover latestDoneTakeover(Long sessionId) {
        return takeoverRepository.findAllBySessionIdOrderByIdAsc(sessionId).stream()
                .filter(t -> "DONE".equals(t.getStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private TimingDeviceSession watermarkOf(Long deviceId, Long sessionId, LocalDateTime now) {
        return dsRepository.findByDeviceIdAndSessionId(deviceId, sessionId).orElseGet(() -> {
            TimingDeviceSession ds = new TimingDeviceSession();
            ds.setDeviceId(deviceId);
            ds.setSessionId(sessionId);
            ds.setConfirmedSeq(0);
            ds.setBoundAt(now);
            return dsRepository.save(ds);
        });
    }

    private long confirmedSeqOf(Long deviceId, Long sessionId) {
        return dsRepository.findByDeviceIdAndSessionId(deviceId, sessionId)
                .map(TimingDeviceSession::getConfirmedSeq).orElse(0L);
    }

    private TimingGap openGapOf(Long deviceId, Long sessionId) {
        return gapRepository.findAllByDeviceIdAndSessionIdOrderByIdAsc(deviceId, sessionId).stream()
                .filter(g -> "OPEN".equals(g.getStatus()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    /** 心跳推导状态；隔离标记优先 */
    private String currentStatus(TimingDevice device, LocalDateTime now) {
        if (device.isQuarantined()) {
            return "ISOLATED";
        }
        if (device.getSessionId() == null) {
            return "UNBOUND";
        }
        long age = device.getLastHeartbeatAt() == null ? Long.MAX_VALUE
                : Math.max(0, Duration.between(device.getLastHeartbeatAt(), now).toMillis());
        return TrackDict.deriveDeviceStatus(age, device.getHbGapMs());
    }

    private void raiseAlert(TimingDevice device, String type, String message) {
        Long deviceId = device.getId();
        Long sessionId = device.getSessionId();
        String text = cut(message);
        try {
            requiresNew.executeWithoutResult(status -> {
                String dedupKey = "dev" + deviceId + ":" + type;
                if (alertRepository.findByDedupKeyAndStatus(dedupKey, "ACTIVE").isPresent()) {
                    return;
                }
                TimingAlert alert = new TimingAlert();
                alert.setDeviceId(deviceId);
                alert.setSessionId(sessionId);
                alert.setType(type);
                alert.setMessage(text);
                alert.setStatus("ACTIVE");
                alert.setRaisedAt(LocalDateTime.now());
                alert.setDedupKey(dedupKey);
                alertRepository.save(alert);
            });
        } catch (DataIntegrityViolationException ignore) {
            // 并发下唯一索引兜底：同设备同类型只保留一条 ACTIVE；不影响外层入账/心跳事务
        }
    }

    private void resolveDeviceAlerts(Long deviceId, List<String> types) {
        for (String type : types) {
            requiresNew.executeWithoutResult(status -> alertRepository
                    .findByDedupKeyAndStatus("dev" + deviceId + ":" + type, "ACTIVE")
                    .ifPresent(alert -> {
                        alert.setStatus("RESOLVED");
                        alertRepository.save(alert);
                    }));
        }
    }

    private String nextTakeoverNo(RaceSession session) {
        String base = "TO" + LocalDateTime.now().format(TAKEOVER_NO_FMT) + "S" + session.getId();
        String candidate = base;
        int n = 1;
        while (takeoverRepository.existsByTakeoverNo(candidate)) {
            n += 1;
            candidate = base + "-" + n;
        }
        return candidate;
    }

    private Views.LapIngestResult ingestResult(TimingLapEvent e, boolean duplicate, List<TimingLapEvent> advanced) {
        Names names = names();
        long confirmed = confirmedSeqOf(e.getDeviceId(), e.getSessionId());
        String resultText;
        switch (e.getStage()) {
            case "ACCEPTED" -> resultText = lapIdHeldByRedFlag(e.getLapId())
                    ? "序号连续、来源有效；该圈产生于红旗期间，已留底并挂待主管裁定，不进实时榜"
                    : "序号连续且来源有效，已进入正常计圈链";
            case "PENDING" -> resultText = "序号存在缺口，该事件已保留为待对账，缺口补齐前不进排行榜";
            case "QUARANTINED" -> resultText = "来源与接管边界不匹配（"
                    + TrackDict.quarantineReasonName(e.getQuarantineReason()) + "），已进入隔离区待主管处理";
            case "DISCARDED" -> resultText = "该事件此前已被主管丢弃，仅留底";
            default -> resultText = e.getStage();
        }
        if (duplicate) {
            resultText = "重复上报：返回首次处理结果（" + TrackDict.lapEventStageName(e.getStage()) + "），未重复入账";
        }
        List<Views.TimingLapEventView> advancedViews = advanced.stream()
                .map(a -> toEventView(a, names)).toList();
        return new Views.LapIngestResult(
                e.getEventUid(), e.getDeviceId(), deviceCodeOf(e.getDeviceId()), e.getSessionId(),
                e.getStage(), TrackDict.lapEventStageName(e.getStage()),
                e.getStage(), resultText, e.getSeq(), confirmed, e.getLapId(), duplicate,
                e.getGapId(), e.getQuarantineReason(),
                TrackDict.quarantineReasonName(e.getQuarantineReason()), advancedViews);
    }

    private record Names(Map<Long, String> driver, Map<Long, String> kart,
                         Map<Long, String> session, Map<Long, TimingDevice> device) {
    }

    private Names names() {
        Map<Long, String> driver = new HashMap<>();
        for (Driver d : driverRepository.findAll()) {
            driver.put(d.getId(), d.getName());
        }
        Map<Long, String> kart = new HashMap<>();
        for (Kart k : kartRepository.findAll()) {
            kart.put(k.getId(), k.getKartNo());
        }
        Map<Long, String> session = new HashMap<>();
        for (RaceSession s : sessionRepository.findAll()) {
            session.put(s.getId(), s.getSessionNo());
        }
        Map<Long, TimingDevice> device = new HashMap<>();
        for (TimingDevice d : deviceRepository.findAll()) {
            device.put(d.getId(), d);
        }
        return new Names(driver, kart, session, device);
    }

    private String deviceNameOf(Long deviceId) {
        return deviceRepository.findById(deviceId).map(TimingDevice::getName).orElse("—");
    }

    private String deviceCodeOf(Long deviceId) {
        return deviceRepository.findById(deviceId).map(TimingDevice::getDeviceCode).orElse(null);
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "—" : t.toString().replace('T', ' ').substring(0, 19);
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
