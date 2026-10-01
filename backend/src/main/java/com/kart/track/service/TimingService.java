package com.kart.track.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

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
import com.kart.track.domain.DeviceAlert;
import com.kart.track.domain.DeviceGap;
import com.kart.track.domain.DeviceHeartbeat;
import com.kart.track.domain.DeviceStream;
import com.kart.track.domain.DeviceTakeover;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapEvent;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.TimingDevice;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.TimingDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DeviceAlertRepository;
import com.kart.track.repository.DeviceGapRepository;
import com.kart.track.repository.DeviceHeartbeatRepository;
import com.kart.track.repository.DeviceStreamRepository;
import com.kart.track.repository.DeviceTakeoverRepository;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapEventRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagEventRepository;
import com.kart.track.repository.TimingDeviceRepository;

/**
 * 计时设备韧性台核心服务。
 *
 * <p>把「设备稳定身份 → 心跳受理 → 圈速事件顺序校验 → 断线缺口补偿（批量重放/幂等去重/断点续传）
 * → 主备接管边界 → 隔离区人工裁定 → 红旗窗口兼容」整条链路收在一处：
 * <ul>
 *   <li><b>明确受理结果</b>：设备未关联本场、场次已结束、设备被隔离时，心跳/事件都返回明确拒绝原因，数据绝不混入本场成绩；</li>
 *   <li><b>顺序闸门</b>：只有序号连续且来源有效的事件才进正常计圈链；缺口之后的事件全部保留为待对账，
 *       不进实时排行榜、不刷车手历史最好；批量重放按 (设备,事件标识) 幂等去重，按序补齐缺口，
 *       补偿中断只重做未确认部分（进度在 device_stream 落盘）；</li>
 *   <li><b>接管边界</b>：主管单事务内校验场次+设备状态并原子切换当前来源，边界前只认主、边界后只认备，
 *       晚到/提前事件进隔离区；任何关联失败整体回滚，不存在双来源或记录与排行榜不一致的半完成状态；</li>
 *   <li><b>红旗兼容</b>：事件确认时按其实际产生时刻判红旗窗口，产生/恢复于红旗期间的圈速挂红旗待裁定，
 *       已裁定/已入榜数据不因设备重连再次生效（幂等键保证同一事件只落一条记录）；</li>
 *   <li><b>权限与并发</b>：值班人员可看状态、确认告警、发起接管请求；主管才能接管、解除隔离、处理隔离区；
 *       人工状态变更带设备版本号乐观锁，过期页面与重复操作不能覆盖新状态。</li>
 * </ul>
 */
@Service
public class TimingService {

    private final TimingDeviceRepository deviceRepository;
    private final DeviceHeartbeatRepository heartbeatRepository;
    private final DeviceStreamRepository streamRepository;
    private final LapEventRepository eventRepository;
    private final DeviceGapRepository gapRepository;
    private final DeviceTakeoverRepository takeoverRepository;
    private final DeviceAlertRepository alertRepository;
    private final RaceSessionRepository sessionRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final RedFlagEventRepository redFlagEventRepository;
    private final LapRecordService lapRecordService;
    private final TransactionTemplate transactionTemplate;

    public TimingService(TimingDeviceRepository deviceRepository,
                       DeviceHeartbeatRepository heartbeatRepository,
                       DeviceStreamRepository streamRepository,
                       LapEventRepository eventRepository,
                       DeviceGapRepository gapRepository,
                       DeviceTakeoverRepository takeoverRepository,
                       DeviceAlertRepository alertRepository,
                       RaceSessionRepository sessionRepository,
                       DriverRepository driverRepository,
                       KartRepository kartRepository,
                       RedFlagEventRepository redFlagEventRepository,
                       LapRecordService lapRecordService,
                       PlatformTransactionManager transactionManager) {
        this.deviceRepository = deviceRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.streamRepository = streamRepository;
        this.eventRepository = eventRepository;
        this.gapRepository = gapRepository;
        this.takeoverRepository = takeoverRepository;
        this.alertRepository = alertRepository;
        this.sessionRepository = sessionRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.redFlagEventRepository = redFlagEventRepository;
        this.lapRecordService = lapRecordService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================================================ 韧性台一屏

    @Transactional(readOnly = true)
    public Views.TimingConsoleView console(Long sessionId) {
        RaceSession session = resolveSession(sessionId);
        LocalDateTime now = LocalDateTime.now();

        Map<Long, String> driverNames = new HashMap<>();
        for (Driver d : driverRepository.findAll()) {
            driverNames.put(d.getId(), d.getName());
        }
        Map<Long, String> kartNos = new HashMap<>();
        for (Kart k : kartRepository.findAll()) {
            kartNos.put(k.getId(), k.getKartNo());
        }

        List<TimingDevice> bound = deviceRepository.findAllBySessionId(session.getId());
        List<Views.TimingDeviceView> deviceViews = new ArrayList<>();
        for (TimingDevice device : bound) {
            deviceViews.add(toDeviceView(device, session, now, driverNames, kartNos));
        }
        deviceViews.sort(Comparator.comparing(Views.TimingDeviceView::deviceNo));

        // 同赛道但未关联本场的设备（未关联设备上报会被明确拒绝），单独展示便于值班人员关联
        List<Views.TimingDeviceView> unbound = new ArrayList<>();
        for (TimingDevice device : deviceRepository.findAllByOrderByDeviceNoAsc()) {
            if (device.getSessionId() == null && device.getTrackConfig().equals(session.getTrackConfig())) {
                unbound.add(toDeviceView(device, session, now, driverNames, kartNos));
            }
        }

        List<LapEvent> pending = eventRepository.findAllBySessionIdAndStatusOrderByIdAsc(session.getId(), "PENDING");
        List<LapEvent> quarantined = eventRepository.findAllBySessionIdAndStatusOrderByIdAsc(session.getId(), "QUARANTINED");
        List<DeviceGap> gaps = gapRepository.findAllBySessionIdOrderByOpenedAtDesc(session.getId());
        List<DeviceTakeover> takeovers = takeoverRepository.findAllBySessionIdOrderByRequestedAtDesc(session.getId());
        List<DeviceAlert> alerts = new ArrayList<>();
        for (DeviceAlert alert : alertRepository.findAllBySessionIdOrderByCreatedAtDesc(session.getId())) {
            alerts.add(alert);
        }

        RedFlagEvent activeFlag = redFlagEventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE").orElse(null);
        TimingDevice sourceDevice = session.getCurrentSourceDeviceId() == null ? null
                : deviceRepository.findById(session.getCurrentSourceDeviceId()).orElse(null);

        return new Views.TimingConsoleView(
                session.getId(),
                session.getSessionNo(),
                session.getStatus(),
                TrackDict.sessionStatusName(session.getStatus()),
                isSessionActive(session),
                activeFlag != null,
                session.getCurrentSourceDeviceId(),
                sourceDevice == null ? null : toDeviceView(sourceDevice, session, now, driverNames, kartNos),
                summarize(deviceViews, unbound, alerts, pending, gaps, quarantined, takeovers),
                deviceViews,
                unbound,
                pending.stream().map(e -> toEventView(e, driverNames, kartNos)).toList(),
                quarantined.stream().map(e -> toEventView(e, driverNames, kartNos)).toList(),
                gaps.stream().map(g -> toGapView(g, deviceNo(g.getDeviceId()))).toList(),
                takeovers.stream().map(this::toTakeoverView).toList(),
                alerts.stream().map(a -> toAlertView(a, deviceNo(a.getDeviceId()))).toList(),
                buildTimeline(session, bound, gaps, takeovers, alerts, now));
    }

    private Views.TimingSummary summarize(List<Views.TimingDeviceView> devices,
                                       List<Views.TimingDeviceView> unbound,
                                       List<DeviceAlert> alerts,
                                       List<LapEvent> pending,
                                       List<DeviceGap> gaps,
                                       List<LapEvent> quarantined,
                                       List<DeviceTakeover> takeovers) {
        long online = devices.stream().filter(d -> "ONLINE".equals(d.heartbeatStatus())).count();
        long flapping = devices.stream().filter(d -> "FLAPPING".equals(d.heartbeatStatus())).count();
        long offline = devices.stream().filter(d -> "OFFLINE".equals(d.heartbeatStatus())).count();
        long isolated = devices.stream().filter(d -> "ISOLATED".equals(d.heartbeatStatus())).count();
        long activeAlerts = alerts.stream().filter(a -> "ACTIVE".equals(a.getStatus())).count();
        long requests = takeovers.stream().filter(t -> "REQUESTED".equals(t.getStatus())).count();
        return new Views.TimingSummary(
                devices.size() + unbound.size(), online, flapping, offline, isolated, unbound.size(),
                activeAlerts, pending.size(),
                gaps.stream().filter(g -> "OPEN".equals(g.getStatus())).count(),
                quarantined.size(), requests);
    }

    private RaceSession resolveSession(Long sessionId) {
        if (sessionId != null) {
            return sessionRepository.findById(sessionId).orElseThrow(() -> new BizException("场次不存在"));
        }
        return sessionRepository.findAllByStatusOrderByStartTimeAsc("RUNNING").stream().findFirst()
                .or(() -> sessionRepository.findAllByStatusOrderByStartTimeAsc("RED_FLAG").stream().findFirst())
                .or(() -> sessionRepository.findAllByOrderByStartTimeDesc().stream().findFirst())
                .orElseThrow(() -> new BizException("还没有任何场次"));
    }

    private boolean isSessionActive(RaceSession session) {
        return "RUNNING".equals(session.getStatus()) || "RED_FLAG".equals(session.getStatus());
    }

    // ============================================================ 设备注册 / 关联

    /** 设备入库：稳定身份（设备号唯一），安装时确定角色与归属赛道 */
    @Transactional
    public Views.TimingDeviceView register(TimingDtos.DeviceRegisterReq req) {
        OperatorContext.require();
        String deviceNo = req.getDeviceNo().trim();
        if (deviceNo.length() > 24) {
            throw new BizException("设备编号不超过 24 字符");
        }
        if (deviceRepository.existsByDeviceNo(deviceNo)) {
            throw new BizException("设备编号 " + deviceNo + " 已存在，设备身份必须稳定不能重复登记");
        }
        if (!TrackDict.isValidDeviceRole(req.getRole())) {
            throw new BizException("设备角色只能是 主设备/备用设备");
        }
        if (!TrackDict.isValidTrackConfig(req.getTrackConfig())) {
            throw new BizException("归属赛道只能是 半场/全场/夜间");
        }
        LocalDateTime now = LocalDateTime.now();
        TimingDevice device = new TimingDevice();
        device.setDeviceNo(deviceNo);
        device.setName(req.getName().trim());
        device.setRole(req.getRole());
        device.setTrackConfig(req.getTrackConfig());
        device.setSessionId(null);
        device.setQuarantined(false);
        device.setHeartbeatJitter(false);
        device.setVersion(0);
        device.setCreatedAt(now);
        device.setUpdatedAt(now);
        TimingDevice saved = deviceRepository.saveAndFlush(device);
        return toDeviceView(saved, null, now, Map.of(), Map.of());
    }

    /**
     * 值班人员把设备关联到当前场次（或换场）。
     * 校验：赛道配置一致、一场同一角色只能一台（uk_device_session_role 唯一索引兜底）、设备未被隔离；
     * 运行中场首次关联主设备时同步该场数据来源。过期版本拒绝。
     */
    @Transactional
    public Views.TimingDeviceView bind(Long deviceId, TimingDtos.BindReq req) {
        OperatorContext.require();
        TimingDevice device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new BizException("计时设备不存在"));
        checkVersion(device.getVersion(), req.getVersion());
        if (device.isQuarantined()) {
            throw new BizException(device.getDeviceNo() + " 处于隔离状态，解除隔离前不能关联场次");
        }
        RaceSession target = sessionRepository.findByIdForUpdate(req.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!device.getTrackConfig().equals(target.getTrackConfig())) {
            throw new BizException(device.getDeviceNo() + " 归属" + TrackDict.trackConfigName(device.getTrackConfig())
                    + "赛道，不能关联到" + TrackDict.trackConfigName(target.getTrackConfig()) + "场次");
        }
        if ("FINISHED".equals(target.getStatus()) || "CANCELED".equals(target.getStatus())) {
            throw new BizException("场次已" + TrackDict.sessionStatusName(target.getStatus()) + "，不能再关联计时设备");
        }
        deviceRepository.findBySessionIdAndRole(target.getId(), device.getRole()).ifPresent(other -> {
            if (!other.getId().equals(device.getId())) {
                throw new BizException("该场次已有" + TrackDict.deviceRoleName(device.getRole()) + " "
                        + other.getDeviceNo() + "，同一角色一场只能关联一台");
            }
        });
        // 设备还挂在已结束/已取消的旧场次上时，绑定新场次自动迁出（旧场时间线由心跳/事件流水留痕保留）
        Long oldSessionId = device.getSessionId();
        if (oldSessionId != null && !oldSessionId.equals(target.getId())) {
            RaceSession old = sessionRepository.findById(oldSessionId).orElse(null);
            if (old != null && isSessionActive(old)) {
                throw new BizException(device.getDeviceNo() + " 仍关联在进行中的场次 " + old.getSessionNo()
                        + "，请先在该场解除关联或完成接管/结束后再换场");
            }
        }
        try {
            device.setSessionId(target.getId());
            device.setVersion(device.getVersion() + 1);
            device.setUpdatedAt(LocalDateTime.now());
            deviceRepository.saveAndFlush(device);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("该场次已有同角色设备（" + TrackDict.deviceRoleName(device.getRole()) + "），关联失败");
        }
        streamRepository.findBySessionIdAndDeviceId(target.getId(), device.getId()).orElseGet(() -> {
            DeviceStream stream = new DeviceStream();
            stream.setSessionId(target.getId());
            stream.setDeviceId(device.getId());
            stream.setDeviceRole(device.getRole());
            stream.setConfirmedSeq(0);
            stream.setObservedSeq(0);
            stream.setLapCount(0);
            stream.setCreatedAt(LocalDateTime.now());
            stream.setUpdatedAt(LocalDateTime.now());
            return streamRepository.saveAndFlush(stream);
        });
        if (target.getCurrentSourceDeviceId() == null && "PRIMARY".equals(device.getRole())) {
            target.setCurrentSourceDeviceId(device.getId());
            sessionRepository.save(target);
        }
        return toDeviceView(device, target, LocalDateTime.now(), Map.of(), Map.of());
    }

    /** 值班人员解除关联：当前数据来源设备在场次进行中不允许解绑（避免比赛中途丢来源） */
    @Transactional
    public Views.TimingDeviceView unbind(Long deviceId, TimingDtos.VersionReq req) {
        OperatorContext.require();
        TimingDevice device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new BizException("计时设备不存在"));
        checkVersion(device.getVersion(), req.getVersion());
        Long sessionId = device.getSessionId();
        if (sessionId != null) {
            RaceSession session = sessionRepository.findById(sessionId).orElseThrow(() -> new BizException("场次不存在"));
            if (isSessionActive(session) && device.getId().equals(session.getCurrentSourceDeviceId())) {
                throw new BizException(device.getDeviceNo() + " 是本场当前数据来源，进行中的场次不能直接解除关联；请先由主管安排接管");
            }
        }
        device.setSessionId(null);
        device.setVersion(device.getVersion() + 1);
        device.setUpdatedAt(LocalDateTime.now());
        deviceRepository.save(device);
        return toDeviceView(device, null, LocalDateTime.now(), Map.of(), Map.of());
    }

    // ============================================================ 心跳

    /**
     * 设备心跳。设备→服务端链路，不要求操作人身份。
     *
     * <p>每种情况都有明确结果：未关联（NOT_BOUND）/ 场次已结束（SESSION_ENDED）/ 已隔离（QUARANTINED）
     * 的心跳落流水但 accepted=false，不刷新最后心跳、不改变在线状态；受理的心跳更新最后心跳并检测序号抖动。
     */
    public Views.HeartbeatResult heartbeat(String deviceNo, TimingDtos.HeartbeatReq req) {
        return transactionTemplate.execute(status -> doHeartbeat(deviceNo, req));
    }

    private Views.HeartbeatResult doHeartbeat(String deviceNo, TimingDtos.HeartbeatReq req) {
        TimingDevice device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new BizException("设备未登记：" + deviceNo + "，请先在韧性台登记设备身份"));
        if (req.getSeqNo() == null || req.getSeqNo() < 0) {
            throw new BizException("心跳序号必须为不小于 0 的递增数");
        }
        LocalDateTime now = LocalDateTime.now();
        DeviceHeartbeat beat = new DeviceHeartbeat();
        beat.setDeviceId(device.getId());
        beat.setSeqNo(req.getSeqNo());
        beat.setReceivedAt(now);
        beat.setSessionId(device.getSessionId());
        beat.setAccepted(true);

        String reject = null;
        if (device.isQuarantined()) {
            reject = "QUARANTINED";
        } else if (device.getSessionId() == null) {
            reject = "NOT_BOUND";
        } else {
            RaceSession session = sessionRepository.findById(device.getSessionId()).orElse(null);
            if (session == null || "FINISHED".equals(session.getStatus()) || "CANCELED".equals(session.getStatus())) {
                reject = "SESSION_ENDED";
            }
        }
        if (reject != null) {
            beat.setAccepted(false);
            beat.setRejectReason(reject);
            heartbeatRepository.save(beat);
            deviceRepository.save(device);
            String status = TrackDict.heartbeatStatus(device.isQuarantined(), device.getSessionId(),
                    device.isHeartbeatJitter(), device.getLastHeartbeatAt(), now);
            return new Views.HeartbeatResult(false, reject, heartbeatRejectName(reject), status,
                    TrackDict.heartbeatStatusName(status), now);
        }

        // 连续两次心跳序号不前进 = 抖动（告警去重，恢复正常后自动清除抖动标记）
        boolean jitter = device.getLastHeartbeatSeq() != null && req.getSeqNo() <= device.getLastHeartbeatSeq();
        boolean wasJitter = device.isHeartbeatJitter();
        device.setHeartbeatJitter(jitter);
        device.setLastHeartbeatAt(now);
        device.setLastHeartbeatSeq(req.getSeqNo());
        device.setUpdatedAt(now);
        deviceRepository.save(device);
        heartbeatRepository.save(beat);

        if (jitter && !wasJitter) {
            raiseAlert(device, "FLAPPING",
                    device.getDeviceNo() + " 连续两次心跳序号未前进（" + req.getSeqNo() + "），链路波动，请关注缺口与待对账事件",
                    "FLAPPING:" + device.getId());
        }
        String status = TrackDict.heartbeatStatus(false, device.getSessionId(), jitter, now, now);
        return new Views.HeartbeatResult(true, null, null, status, TrackDict.heartbeatStatusName(status), now);
    }

    private String heartbeatRejectName(String reason) {
        return switch (reason) {
            case "NOT_BOUND" -> "设备未关联本场，心跳已记录但不计入在线状态";
            case "SESSION_ENDED" -> "关联场次已结束，心跳已记录但不刷新状态";
            case "QUARANTINED" -> "设备处于隔离状态，心跳已留痕但不受理";
            default -> reason;
        };
    }

    // ============================================================ 圈速事件上报

    /**
     * 单条圈速事件上报（设备→服务端，不要求操作人身份）。
     * 无论接收、去重、待对账还是隔离，都返回明确结果。
     */
    public Views.IngestResult ingest(String deviceNo, TimingDtos.LapEventReq req) {
        return transactionTemplate.execute(status -> doIngest(deviceNo, req, false));
    }

    /**
     * 断线恢复后的批量重放：每条事件独立事务处理，一条失败/中途再次中断不影响前面已确认的进度；
     * 返回逐条结果，让设备/补偿程序明确每条的去向。
     */
    public List<Views.IngestResult> replay(String deviceNo, List<TimingDtos.LapEventReq> events) {
        TimingDevice check = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new BizException("设备未登记：" + deviceNo));
        List<Views.IngestResult> results = new ArrayList<>();
        for (TimingDtos.LapEventReq req : events) {
            try {
                results.add(transactionTemplate.execute(status -> doIngest(check.getDeviceNo(), req, true)));
            } catch (RuntimeException e) {
                results.add(new Views.IngestResult(false, req.getEventUid(),
                        req.getSeqNo() == null ? -1 : req.getSeqNo(),
                        "REJECTED", "已拒绝", null, null, null, 0, false,
                        e.getMessage() == null ? "事件校验失败" : e.getMessage()));
            }
        }
        return results;
    }

    private Views.IngestResult doIngest(String deviceNo, TimingDtos.LapEventReq req, boolean replay) {
        TimingDevice device = deviceRepository.findByDeviceNo(deviceNo)
                .orElseThrow(() -> new BizException("设备未登记：" + deviceNo));
        String uid = req.getEventUid() == null ? null : req.getEventUid().trim();
        if (uid == null || uid.isBlank()) {
            throw new BizException("设备侧事件唯一标识不能为空");
        }
        if (uid.length() > 48) {
            throw new BizException("事件唯一标识不超过 48 字符");
        }
        if (req.getSeqNo() == null || req.getSeqNo() <= 0) {
            throw new BizException("事件序号必须为从 1 开始的递增数");
        }
        if (req.getOccurredAt() == null) {
            throw new BizException("事件实际产生时刻不能为空");
        }
        if (req.getLapTimeMs() == null
                || req.getLapTimeMs() < TrackDict.MIN_LAP_MS || req.getLapTimeMs() > TrackDict.MAX_LAP_MS) {
            throw new BizException("圈速应在 " + TrackDict.MIN_LAP_MS / 1000 + " 秒 ~ "
                    + TrackDict.MAX_LAP_MS / 1000 + " 秒之间");
        }
        String valid = req.getValid() == null || req.getValid().isBlank() ? "VALID" : req.getValid();
        if (!TrackDict.isValidLapValid(valid)) {
            throw new BizException("圈速判定只能是 有效/无效-出界/无效-碰撞");
        }
        driverRepository.findById(req.getDriverId()).orElseThrow(() -> new BizException("上报的车手不存在"));
        kartRepository.findById(req.getKartId()).orElseThrow(() -> new BizException("上报的车辆不存在"));

        // —— 幂等：同一事件无论重放多少次只落一行、只形成一条圈速记录 ——
        Optional<LapEvent> duplicate = eventRepository.findByDeviceIdAndEventUid(device.getId(), uid);
        if (duplicate.isPresent()) {
            LapEvent old = duplicate.get();
            old.setReplayCount(old.getReplayCount() + 1);
            eventRepository.save(old);
            return ingestView(old, "重复事件已幂等忽略：该事件此前已处理为「"
                    + TrackDict.lapEventStatusName(old.getStatus()) + "」，不重复入榜");
        }

        // —— 上报资格：未关联本场 / 场次已结束 / 隔离设备，明确拒绝，绝不混入本场成绩 ——
        if (device.isQuarantined()) {
            throw new BizException(device.getDeviceNo() + " 已被隔离，圈速事件不予接收，请联系主管查明原因");
        }
        Long sessionId = device.getSessionId();
        if (sessionId == null) {
            throw new BizException(device.getDeviceNo() + " 未关联任何场次，事件不予接收（请先在韧性台关联本场）");
        }
        RaceSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BizException("场次不存在"));
        if ("FINISHED".equals(session.getStatus()) || "CANCELED".equals(session.getStatus())) {
            throw new BizException("场次已" + TrackDict.sessionStatusName(session.getStatus()) + "，事件不予接收，不能混入本场成绩");
        }

        LocalDateTime now = LocalDateTime.now();
        DeviceStream stream = streamRepository.findBySessionIdAndDeviceId(session.getId(), device.getId())
                .orElseGet(() -> {
                    DeviceStream s = new DeviceStream();
                    s.setSessionId(session.getId());
                    s.setDeviceId(device.getId());
                    s.setDeviceRole(device.getRole());
                    s.setConfirmedSeq(0);
                    s.setObservedSeq(0);
                    s.setLapCount(0);
                    s.setCreatedAt(now);
                    return streamRepository.save(s);
                });

        // —— 主备接管边界校验：边界前只认主设备，边界后只认备用设备 ——
        DeviceTakeover takeover = takeoverRepository.findBySessionIdAndStatus(session.getId(), "APPROVED").orElse(null);
        String boundaryReason = boundaryReject(takeover, device, req);

        LapEvent event = new LapEvent();
        event.setSessionId(session.getId());
        event.setDeviceId(device.getId());
        event.setEventUid(uid);
        event.setSeqNo(req.getSeqNo());
        event.setOccurredAt(req.getOccurredAt());
        event.setDriverId(req.getDriverId());
        event.setKartId(req.getKartId());
        event.setLapTimeMs(req.getLapTimeMs());
        event.setValid(valid);
        event.setReplayCount(replay ? 1 : 0);
        event.setCreatedAt(now);

        if (boundaryReason != null) {
            event.setStatus("QUARANTINED");
            event.setQuarantineReason(boundaryReason);
            eventRepository.saveAndFlush(event);
            alertTakeoverBoundary(device, takeover, boundaryReason, event);
            return ingestView(event, "事件越过主备接管边界，已进入隔离区等待主管处理");
        }

        // —— 序号倒退：设备侧序号必须单调；倒退数据进隔离区由主管裁定 ——
        if (req.getSeqNo() <= stream.getConfirmedSeq()) {
            event.setStatus("QUARANTINED");
            event.setQuarantineReason("SEQ_REGRESSION");
            eventRepository.saveAndFlush(event);
            raiseAlert(device, "JUMP",
                    device.getDeviceNo() + " 上报序号 " + req.getSeqNo() + " 已小于连续确认序号 "
                            + stream.getConfirmedSeq() + "，事件已进隔离区",
                    "JUMP:" + session.getId() + ":" + device.getId());
            return ingestView(event, "序号倒退，事件已进入隔离区");
        }

        long expected = stream.getConfirmedSeq() + 1;
        stream.setObservedSeq(Math.max(stream.getObservedSeq(), req.getSeqNo()));
        stream.setUpdatedAt(now);
        streamRepository.save(stream);

        if (req.getSeqNo() == expected) {
            // 序号连续：直接走确认，随后尝试顺带推进所有已到齐的待对账事件
            event.setStatus("CONFIRMED");
            eventRepository.saveAndFlush(event);
            confirmEvent(session, device, stream, event, now);
            flushPending(session, device, now);
            event = eventRepository.findById(event.getId()).orElseThrow();
            return ingestView(event, "序号连续、来源有效，已进入正常计圈链");
        }

        if (req.getSeqNo() < expected) {
            // 理论上前面已挡（<=confirmedSeq）；落在缺口区间内的重复到达不会发生（幂等已挡）
            event.setStatus("QUARANTINED");
            event.setQuarantineReason("SEQ_REGRESSION");
            eventRepository.saveAndFlush(event);
            return ingestView(event, "序号异常，事件已进入隔离区");
        }

        // seqNo > expected：发现缺口。缺口之后的数据先保留为待对账，绝不提前入榜/刷最佳
        event.setStatus("PENDING");
        eventRepository.saveAndFlush(event);
        openGap(session, device, expected, req.getSeqNo() - 1, now);
        return ingestView(event, "检测到序号缺口 " + expected + ".." + (req.getSeqNo() - 1)
                + "，该事件已挂起为待对账，补齐缺口前不进入实时排行榜");
    }

    /**
     * 主备接管边界判定：返回隔离原因（null = 来源有效允许进入顺序链）。
     *
     * <p>切分以事件<b>实际产生时刻</b>为权威：主设备边界时刻之后产生的事件 = 晚到事件；
     * 备用设备边界时刻之前产生的事件 = 提前事件。边界序号是主设备侧的留痕，用于审计和时间线展示，
     * 不用于卡备用设备——备用设备有自己从 1 开始的独立序号。
     */
    private String boundaryReject(DeviceTakeover takeover, TimingDevice device, TimingDtos.LapEventReq req) {
        if (takeover == null) {
            return null;
        }
        boolean isBackup = device.getId().equals(takeover.getBackupDeviceId());
        boolean isPrimary = device.getId().equals(takeover.getPrimaryDeviceId());
        if (isPrimary) {
            // 边界时刻之前（含边界时刻）认主；之后产生的主设备晚到事件进隔离区
            if (req.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                return "LATE_PRIMARY";
            }
            return null;
        }
        if (isBackup) {
            // 备用设备只认边界时刻之后；提前产生的边界前事件进隔离区
            if (!req.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                return "EARLY_BACKUP";
            }
            return null;
        }
        // 既不是主也不是备的第三方设备：接管之后其数据不再被承认
        return "LATE_PRIMARY";
    }

    /** 确认一个序号连续的事件：落 lap_record（红旗窗口自动挂待裁定），推进已确认进度 */
    private void confirmEvent(RaceSession session, TimingDevice device, DeviceStream stream, LapEvent event, LocalDateTime now) {
        LapRecordService.AcceptedLap accepted = lapRecordService.acceptTimingLap(
                session.getId(), event.getDriverId(), event.getKartId(), event.getLapTimeMs(),
                event.getValid(), event.getOccurredAt());
        event.setStatus("CONFIRMED");
        event.setLapId(accepted.lapId());
        event.setRedFlag(accepted.redFlag());
        event.setRedFlagEventId(accepted.redFlagEventId());
        eventRepository.save(event);

        stream.setConfirmedSeq(event.getSeqNo());
        stream.setLapCount(stream.getLapCount() + 1);
        stream.setUpdatedAt(now);
        streamRepository.save(stream);
    }

    /**
     * 按序补齐：从 confirmedSeq+1 开始，只要下一个序号的待对账事件已到齐就连续确认推进；
     * 遇到仍然缺失的序号立即停下——后面的事件继续保留为待对账。
     * 补偿过程中再次中断：进度已落库，下次重放只从断点继续，绝不从头重复处理。
     */
    private void flushPending(RaceSession session, TimingDevice device, LocalDateTime now) {
        DeviceStream stream = streamRepository.findBySessionIdAndDeviceId(session.getId(), device.getId())
                .orElseThrow();
        List<LapEvent> pending = eventRepository
                .findAllBySessionIdAndDeviceIdOrderBySeqNoAsc(session.getId(), device.getId()).stream()
                .filter(e -> "PENDING".equals(e.getStatus()))
                .sorted(Comparator.comparingLong(LapEvent::getSeqNo))
                .toList();
        Map<Long, LapEvent> bySeq = new HashMap<>();
        for (LapEvent e : pending) {
            bySeq.putIfAbsent(e.getSeqNo(), e);
        }
        long cursor = stream.getConfirmedSeq() + 1;
        while (bySeq.containsKey(cursor)) {
            LapEvent next = bySeq.get(cursor);
            // 接管边界保护：原主设备待对账事件即使按序补齐，边界时刻之后产生的仍不得确认
            DeviceTakeover takeover = takeoverRepository.findBySessionIdAndStatus(session.getId(), "APPROVED").orElse(null);
            if (takeover != null && device.getId().equals(takeover.getPrimaryDeviceId())
                    && next.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                next.setStatus("QUARANTINED");
                next.setQuarantineReason("LATE_PRIMARY");
                eventRepository.save(next);
                break;
            }
            confirmEvent(session, device, stream, next, now);
            bySeq.remove(cursor);
            cursor += 1;
        }
        updateGapsAfterFlush(session, device, now);
    }

    /** 发现缺口：登记/扩展 OPEN 缺口区间并告警（同起点唯一，去重） */
    private void openGap(RaceSession session, TimingDevice device, long from, long to, LocalDateTime now) {
        List<DeviceGap> open = gapRepository.findAllByDeviceIdAndStatusOrderByFromSeqAsc(device.getId(), "OPEN");
        DeviceGap gap = open.stream().filter(g -> g.getFromSeq() == from).findFirst().orElse(null);
        if (gap == null) {
            // 与更早的开放缺口相邻则扩展为一段区间
            gap = open.stream().filter(g -> g.getToSeq() + 1 == from).findFirst().orElse(null);
            if (gap != null) {
                gap.setToSeq(to);
            } else {
                DeviceGap created = new DeviceGap();
                created.setSessionId(session.getId());
                created.setDeviceId(device.getId());
                created.setFromSeq(from);
                created.setToSeq(to);
                created.setStatus("OPEN");
                created.setOpenedAt(now);
                gap = gapRepository.saveAndFlush(created);
            }
            raiseAlert(device, "GAP",
                    device.getDeviceNo() + " 序号 " + from + (to > from ? ".." + to : String.valueOf(to))
                            + " 缺失，之后的圈速已挂起待对账，实时排行榜暂停采用该段数据",
                    "GAP:" + session.getId() + ":" + device.getId() + ":" + from);
        }
    }

    /** 补偿推进后刷新缺口区间：全部补齐则关闭；仅补齐一段的前部则收缩区间 */
    private void updateGapsAfterFlush(RaceSession session, TimingDevice device, LocalDateTime now) {
        DeviceStream stream = streamRepository.findBySessionIdAndDeviceId(session.getId(), device.getId()).orElseThrow();
        List<LapEvent> arrived = eventRepository
                .findAllBySessionIdAndDeviceIdOrderBySeqNoAsc(session.getId(), device.getId());
        java.util.Set<Long> present = new java.util.TreeSet();
        for (LapEvent e : arrived) {
            if ("CONFIRMED".equals(e.getStatus()) || "PENDING".equals(e.getStatus())) {
                present.add(e.getSeqNo());
            }
        }
        for (DeviceGap gap : gapRepository.findAllByDeviceIdAndStatusOrderByFromSeqAsc(device.getId(), "OPEN")) {
            long firstMissing = -1;
            long lastMissing = -1;
            for (long seq = gap.getFromSeq(); seq <= gap.getToSeq(); seq++) {
                if (!present.contains(seq)) {
                    if (firstMissing < 0) {
                        firstMissing = seq;
                    }
                    lastMissing = seq;
                }
            }
            if (firstMissing < 0) {
                gap.setStatus("FILLED");
                gap.setFilledAt(now);
                gapRepository.save(gap);
                resolveAlert("GAP:" + session.getId() + ":" + device.getId() + ":" + gap.getFromSeq(),
                        device.getDeviceNo() + " 序号缺口 " + gap.getFromSeq()
                                + (gap.getToSeq() > gap.getFromSeq() ? ".." + gap.getToSeq() : "") + " 已按序补齐");
            } else if (firstMissing > gap.getFromSeq() || lastMissing < gap.getToSeq()) {
                gap.setFromSeq(firstMissing);
                gap.setToSeq(lastMissing);
                gapRepository.save(gap);
            }
        }
        stream.setObservedSeq(Math.max(stream.getObservedSeq(), stream.getConfirmedSeq()));
        streamRepository.save(stream);
    }

    // ============================================================ 主备接管

    /**
     * 值班人员发起接管请求（不能自行接管）。校验当前场次进行中/红旗处理中、主设备即当前来源、
     * 备用设备同场且未隔离；记录发起时的边界建议——最终边界以主管批准时刻为准，防止过期页面。
     */
    @Transactional
    public Views.TakeoverView requestTakeover(Long sessionId, TimingDtos.TakeoverRequestReq req) {
        Operator operator = OperatorContext.require();
        RaceSession session = sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!isSessionActive(session)) {
            throw new BizException("场次当前为「" + TrackDict.sessionStatusName(session.getStatus()) + "」，不能发起设备接管");
        }
        if (takeoverRepository.findBySessionIdAndStatus(sessionId, "REQUESTED").isPresent()) {
            throw new BizException("本场已有待主管审批的接管请求，请勿重复发起");
        }
        if (takeoverRepository.findBySessionIdAndStatus(sessionId, "APPROVED").isPresent()) {
            throw new BizException("本场已完成主备接管，如需再次切换请由主管处理");
        }
        TimingDevice backup = deviceRepository.findByIdForUpdate(req.getBackupDeviceId())
                .orElseThrow(() -> new BizException("备用设备不存在"));
        if (!"BACKUP".equals(backup.getRole())) {
            throw new BizException(backup.getDeviceNo() + " 不是备用设备，不能接管");
        }
        if (!sessionId.equals(backup.getSessionId())) {
            throw new BizException("备用设备 " + backup.getDeviceNo() + " 未关联本场，不能接管本场");
        }
        if (backup.isQuarantined()) {
            throw new BizException("备用设备 " + backup.getDeviceNo() + " 处于隔离状态，不能接管");
        }
        Long primaryId = session.getCurrentSourceDeviceId();
        if (primaryId == null) {
            throw new BizException("本场尚未确定当前计时来源设备，无法发起接管");
        }
        TimingDevice primary = deviceRepository.findByIdForUpdate(primaryId)
                .orElseThrow(() -> new BizException("当前来源设备不存在"));
        checkVersion(primary.getVersion(), req.getPrimaryVersion());

        DeviceStream primaryStream = streamRepository.findBySessionIdAndDeviceId(sessionId, primaryId).orElse(null);
        long boundarySeq = primaryStream == null ? 0 : primaryStream.getConfirmedSeq();
        LocalDateTime now = LocalDateTime.now();
        DeviceTakeover takeover = new DeviceTakeover();
        takeover.setSessionId(sessionId);
        takeover.setPrimaryDeviceId(primaryId);
        takeover.setBackupDeviceId(backup.getId());
        takeover.setBoundarySeq(boundarySeq);
        takeover.setBoundaryAt(now);
        takeover.setStatus("REQUESTED");
        takeover.setRequestedBy(operator.id());
        takeover.setRequestedAt(now);
        takeover.setRequestVersion(req.getPrimaryVersion());
        takeover.setRejectReason(trim(req.getReason()));
        takeover = takeoverRepository.saveAndFlush(takeover);

        raiseAlertRaw(sessionId, primaryId, "TAKEOVER_REQ",
                "值班人员 " + operator.id() + " 请求由 " + backup.getDeviceNo() + " 接管 "
                        + primary.getDeviceNo() + "（边界序号 " + boundarySeq + "），等待主管审批",
                "TAKEOVER_REQ:" + sessionId + ":" + takeover.getId());
        return toTakeoverView(takeover);
    }

    /** 值班人员在主管审批前撤回自己发起的接管请求 */
    @Transactional
    public Views.TakeoverView cancelTakeover(Long takeoverId) {
        Operator operator = OperatorContext.require();
        DeviceTakeover takeover = takeoverRepository.findById(takeoverId)
                .orElseThrow(() -> new BizException("接管记录不存在"));
        if (!"REQUESTED".equals(takeover.getStatus())) {
            throw new BizException("接管请求已" + TrackDict.takeoverStatusName(takeover.getStatus())
                    + "，不能撤回（当前状态以系统为准）");
        }
        if (!operator.id().equals(takeover.getRequestedBy()) && !operator.isSupervisor()) {
            throw new BizException("只能撤回自己发起的接管请求（或由主管处理）");
        }
        takeover.setStatus("CANCELED");
        takeover.setDecidedBy(operator.id());
        takeover.setDecidedAt(LocalDateTime.now());
        takeoverRepository.save(takeover);
        resolveAlert("TAKEOVER_REQ:" + takeover.getSessionId() + ":" + takeover.getId(), "接管请求已撤回");
        return toTakeoverView(takeover);
    }

    /**
     * 主管批准接管：本系统最关键的事务收口。
     *
     * <p>单事务内完成全部校验与切换，任何一步失败整体回滚：
     * <ol>
     *   <li>请求仍处于待审批、场次仍活跃、主设备仍是当前来源、备用设备仍关联本场且未隔离；</li>
     *   <li>边界取批准时刻：主设备 confirmedSeq 为边界序号、此刻为边界时刻；</li>
     *   <li>冻结主设备流边界、场次当前来源原子切换到备用设备、两台设备版本号推进；</li>
     *   <li>把备用设备此前已到的「边界前事件」和主设备流中越过边界的待对账事件扫入隔离区。</li>
     * </ol>
     * uk_active_takeover 唯一索引再兜底：同场次不可能同时存在两个生效接管。
     */
    public Views.TakeoverView approveTakeover(Long takeoverId) {
        Operator operator = OperatorContext.requireSupervisor();
        AtomicReference<DeviceTakeover> saved = new AtomicReference<>();
        try {
            transactionTemplate.executeWithoutResult(status -> {
            DeviceTakeover takeover = takeoverRepository.findById(takeoverId)
                    .orElseThrow(() -> new BizException("接管记录不存在"));
            if (!"REQUESTED".equals(takeover.getStatus())) {
                throw new BizException("接管请求已" + TrackDict.takeoverStatusName(takeover.getStatus())
                        + "，不能重复审批");
            }
            Long sessionId = takeover.getSessionId();
            RaceSession session = sessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new BizException("场次不存在"));
            if (!isSessionActive(session)) {
                throw new BizException("场次当前为「" + TrackDict.sessionStatusName(session.getStatus())
                        + "」，接管请求与当前场次状态不一致，已拒绝（数据来源保持不变）");
            }
            TimingDevice primary = deviceRepository.findByIdForUpdate(takeover.getPrimaryDeviceId())
                    .orElseThrow(() -> new BizException("原主设备不存在"));
            TimingDevice backup = deviceRepository.findByIdForUpdate(takeover.getBackupDeviceId())
                    .orElseThrow(() -> new BizException("备用设备不存在"));
            if (!sessionId.equals(primary.getSessionId()) || !sessionId.equals(backup.getSessionId())) {
                throw new BizException("主/备设备的关联赛次已变化，接管请求已过期，已恢复保持原数据来源");
            }
            if (backup.isQuarantined()) {
                throw new BizException("备用设备 " + backup.getDeviceNo() + " 已被隔离，不能接管");
            }
            // 主设备被隔离通常正是接管原因，允许接管；但当前来源必须确实是该主设备，防止过期页面基于旧状态切换
            if (!primary.getId().equals(session.getCurrentSourceDeviceId())) {
                throw new BizException("本场当前来源已不是 " + primary.getDeviceNo()
                        + "，接管请求已过期（不能两台设备同时作为来源）");
            }
            if (takeoverRepository.findBySessionIdAndStatus(sessionId, "APPROVED").isPresent()) {
                throw new BizException("本场已有生效中的接管，不能重复接管");
            }

            LocalDateTime now = LocalDateTime.now();
            DeviceStream primaryStream = streamRepository
                    .findBySessionIdAndDeviceId(sessionId, primary.getId())
                    .orElseGet(() -> newStream(sessionId, primary, now));
            DeviceStream backupStream = streamRepository
                    .findBySessionIdAndDeviceId(sessionId, backup.getId())
                    .orElseGet(() -> newStream(sessionId, backup, now));

            // 边界以批准时刻为准重算，避免值班发起后到主管批准之间主设备又确认了新圈
            long boundarySeq = primaryStream.getConfirmedSeq();

            // —— 原子切换：接管记录生效 + 冻结主设备流边界 + 当前来源切到备用 ——
            takeover.setStatus("APPROVED");
            takeover.setBoundarySeq(boundarySeq);
            takeover.setBoundaryAt(now);
            takeover.setDecidedBy(operator.id());
            takeover.setDecidedAt(now);
            takeoverRepository.saveAndFlush(takeover);

            primaryStream.setBoundaryAfterSeq(boundarySeq);
            primaryStream.setUpdatedAt(now);
            streamRepository.save(primaryStream);
            backupStream.setUpdatedAt(now);
            streamRepository.save(backupStream);

            session.setCurrentSourceDeviceId(backup.getId());
            sessionRepository.saveAndFlush(session);

            primary.setVersion(primary.getVersion() + 1);
            primary.setUpdatedAt(now);
            backup.setVersion(backup.getVersion() + 1);
            backup.setUpdatedAt(now);
            deviceRepository.save(primary);
            deviceRepository.saveAndFlush(backup);

            // —— 扫隔离区：备用设备提前产生的边界前事件；主设备越过边界的待对账/晚到事件 ——
            sweepBoundaryToQuarantine(session, takeover, now);

            resolveAlert("TAKEOVER_REQ:" + sessionId + ":" + takeover.getId(), "主管已批准接管");
            saved.set(takeover);
            });
            return toTakeoverView(saved.get());
        } catch (DataIntegrityViolationException e) {
            // uk_active_takeover 兜底：并发双批准只有一个提交，本次切换整体回滚，数据来源保持原样
            throw new BizException("本场已有生效中的接管，本次切换未生效，数据来源保持原样");
        }
    }

    /** 主管驳回接管请求（必须写明原因），数据来源维持主设备不变 */
    @Transactional
    public Views.TakeoverView rejectTakeover(Long takeoverId, TimingDtos.TakeoverRejectReq req) {
        Operator operator = OperatorContext.requireSupervisor();
        DeviceTakeover takeover = takeoverRepository.findById(takeoverId)
                .orElseThrow(() -> new BizException("接管记录不存在"));
        if (!"REQUESTED".equals(takeover.getStatus())) {
            throw new BizException("接管请求已" + TrackDict.takeoverStatusName(takeover.getStatus()) + "，不能重复处理");
        }
        String reason = trim(req.getReason());
        if (reason == null) {
            throw new BizException("驳回原因不能为空");
        }
        takeover.setStatus("REJECTED");
        takeover.setDecidedBy(operator.id());
        takeover.setDecidedAt(LocalDateTime.now());
        takeover.setRejectReason(reason);
        takeoverRepository.save(takeover);
        resolveAlert("TAKEOVER_REQ:" + takeover.getSessionId() + ":" + takeover.getId(),
                "主管已驳回接管：" + reason);
        return toTakeoverView(takeover);
    }

    private DeviceStream newStream(Long sessionId, TimingDevice device, LocalDateTime now) {
        DeviceStream s = new DeviceStream();
        s.setSessionId(sessionId);
        s.setDeviceId(device.getId());
        s.setDeviceRole(device.getRole());
        s.setConfirmedSeq(0);
        s.setObservedSeq(0);
        s.setLapCount(0);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return streamRepository.save(s);
    }

    /** 接管生效时把越界事件统一扫入隔离区（此前上报的也要追溯，不能漏在边界外侧） */
    private void sweepBoundaryToQuarantine(RaceSession session, DeviceTakeover takeover, LocalDateTime now) {
        for (LapEvent e : eventRepository.findAllBySessionIdOrderBySeqNoAsc(session.getId())) {
            if ("CONFIRMED".equals(e.getStatus()) || "VOID".equals(e.getStatus())) {
                continue; // 已经确认/已丢弃的不动：边界前主设备数据保持有效
            }
            String reason = null;
            if (e.getDeviceId().equals(takeover.getBackupDeviceId())
                    && !e.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                reason = "EARLY_BACKUP";
            } else if (e.getDeviceId().equals(takeover.getPrimaryDeviceId())
                    && e.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                reason = "LATE_PRIMARY";
            }
            if (reason != null && !"QUARANTINED".equals(e.getStatus())) {
                e.setStatus("QUARANTINED");
                e.setQuarantineReason(reason);
                eventRepository.save(e);
            }
        }
    }

    // ============================================================ 隔离 / 解除隔离 / 隔离区裁定

    /** 主管隔离设备：之后一切上报明确拒绝、不混入本场成绩；版本号防过期页面 */
    @Transactional
    public Views.TimingDeviceView quarantine(Long deviceId, TimingDtos.QuarantineReq req) {
        Operator operator = OperatorContext.requireSupervisor();
        TimingDevice device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new BizException("计时设备不存在"));
        checkVersion(device.getVersion(), req.getVersion());
        String reason = trim(req.getReason());
        if (reason == null) {
            throw new BizException("隔离原因不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        device.setQuarantined(true);
        device.setQuarantineReason(reason);
        device.setVersion(device.getVersion() + 1);
        device.setUpdatedAt(now);
        deviceRepository.save(device);
        raiseAlertRaw(device.getSessionId(), device.getId(), "QUARANTINED",
                device.getDeviceNo() + " 已被主管 " + operator.id() + " 隔离：" + reason, null);
        return toDeviceView(device, device.getSessionId() == null ? null
                : sessionRepository.findById(device.getSessionId()).orElse(null), now, Map.of(), Map.of());
    }

    /** 主管解除隔离：恢复受理；设备此前隔离期间没有任何数据混入本场（上报本就被拒绝），无需回滚成绩 */
    @Transactional
    public Views.TimingDeviceView releaseQuarantine(Long deviceId, TimingDtos.VersionReq req) {
        OperatorContext.requireSupervisor();
        TimingDevice device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new BizException("计时设备不存在"));
        checkVersion(device.getVersion(), req.getVersion());
        if (!device.isQuarantined()) {
            throw new BizException(device.getDeviceNo() + " 当前并未隔离，操作已过期，请刷新状态");
        }
        LocalDateTime now = LocalDateTime.now();
        device.setQuarantined(false);
        device.setQuarantineReason(null);
        device.setVersion(device.getVersion() + 1);
        device.setUpdatedAt(now);
        deviceRepository.save(device);
        return toDeviceView(device, device.getSessionId() == null ? null
                : sessionRepository.findById(device.getSessionId()).orElse(null), now, Map.of(), Map.of());
    }

    /**
     * 主管处理隔离区一条事件：
     * <ul>
     *   <li>DISCARD 丢弃：置 VOID 留底，永不入榜；</li>
     *   <li>RESEGMENT 重新归段：主管确认该事件来源归属后，按当前顺序状态重新进入链路——
     *       若正好是下一个连续序号则确认落账，否则回到待对账参与按序补齐；已确认过的（理论上不会）幂等拒绝。</li>
     * </ul>
     * 处理即冻结，重复操作不重复生效。
     */
    @Transactional
    public Views.LapEventView ruleQuarantineEvent(Long eventId, TimingDtos.QuarantineRuleReq req) {
        Operator operator = OperatorContext.requireSupervisor();
        String action = req.getAction();
        if (!"DISCARD".equals(action) && !"RESEGMENT".equals(action)) {
            throw new BizException("处理方式只能是 丢弃/重新归段");
        }
        LapEvent event = eventRepository.findById(eventId).orElseThrow(() -> new BizException("隔离事件不存在"));
        if (!"QUARANTINED".equals(event.getStatus())) {
            throw new BizException("该事件已处理为「" + TrackDict.lapEventStatusName(event.getStatus())
                    + "」，不能重复处理");
        }
        LocalDateTime now = LocalDateTime.now();
        event.setRuledBy(operator.id());
        event.setRuledAt(now);

        if ("DISCARD".equals(action)) {
            event.setStatus("VOID");
            eventRepository.save(event);
            return toEventView(event, driverNames(), kartNos());
        }

        // 重新归段：清掉隔离原因，重新走顺序闸门（复用 doIngest 的幂等链路前先把本行准备好）
        RaceSession session = sessionRepository.findById(event.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        TimingDevice device = deviceRepository.findById(event.getDeviceId())
                .orElseThrow(() -> new BizException("设备不存在"));
        DeviceStream stream = streamRepository
                .findBySessionIdAndDeviceId(session.getId(), device.getId())
                .orElseGet(() -> newStream(session.getId(), device, now));
        DeviceTakeover takeover = takeoverRepository
                .findBySessionIdAndStatus(session.getId(), "APPROVED").orElse(null);
        if (takeover != null) {
            boolean fromPrimary = device.getId().equals(takeover.getPrimaryDeviceId());
            boolean fromBackup = device.getId().equals(takeover.getBackupDeviceId());
            if (fromPrimary && event.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                throw new BizException("该事件产生于主设备接管边界时刻之后，当前生效边界不允许归段；如确认采用请保留主管裁定依据");
            }
            if (fromBackup && !event.getOccurredAt().isAfter(takeover.getBoundaryAt())) {
                throw new BizException("该事件产生于备用设备接管边界时刻之前，不能归为备用段数据");
            }
        }
        long expected = stream.getConfirmedSeq() + 1;
        if (event.getSeqNo() == expected) {
            event.setQuarantineReason(null);
            event.setStatus("CONFIRMED");
            eventRepository.save(event);
            confirmEvent(session, device, stream, event, now);
            flushPending(session, device, now);
        } else if (event.getSeqNo() > expected) {
            // 缺口仍在：回到待对账，不提前入榜
            event.setStatus("PENDING");
            event.setQuarantineReason(null);
            eventRepository.save(event);
            openGap(session, device, expected, event.getSeqNo() - 1, now);
        } else {
            throw new BizException("事件序号 " + event.getSeqNo() + " 已被连续确认进度越过，不能重新归段（请选择丢弃）");
        }
        event = eventRepository.findById(eventId).orElseThrow();
        return toEventView(event, driverNames(), kartNos());
    }

    // ============================================================ 告警

    /** 值班人员确认一条告警（确认即冻结；重复确认明确返回，不覆盖新状态） */
    @Transactional
    public Views.DeviceAlertView acknowledgeAlert(Long alertId) {
        Operator operator = OperatorContext.require();
        DeviceAlert alert = alertRepository.findById(alertId).orElseThrow(() -> new BizException("告警不存在"));
        if ("ACK".equals(alert.getStatus())) {
            throw new BizException("该告警已于 " + alert.getAckAt() + " 由 " + alert.getAckBy() + " 确认，不能重复确认");
        }
        alert.setStatus("ACK");
        alert.setAckBy(operator.id());
        alert.setAckAt(LocalDateTime.now());
        alertRepository.save(alert);
        return toAlertView(alert, deviceNo(alert.getDeviceId()));
    }

    /** 活跃告警去重落库：同 dedupKey 的 ACTIVE 已存在则不重复产生（uk_alert_dedup 兜底） */
    private void raiseAlert(TimingDevice device, String type, String content, String dedupKey) {
        raiseAlertRaw(device.getSessionId(), device.getId(), type, content, dedupKey);
    }

    private void raiseAlertRaw(Long sessionId, Long deviceId, String type, String content, String dedupKey) {
        if (dedupKey != null && alertRepository.findByDedupKeyAndStatus(dedupKey, "ACTIVE").isPresent()) {
            return;
        }
        DeviceAlert alert = new DeviceAlert();
        alert.setSessionId(sessionId);
        alert.setDeviceId(deviceId);
        alert.setType(type);
        alert.setContent(trim(content));
        alert.setStatus("ACTIVE");
        alert.setCreatedAt(LocalDateTime.now());
        alert.setDedupKey(dedupKey);
        try {
            alertRepository.saveAndFlush(alert);
        } catch (DataIntegrityViolationException ignore) {
            // 并发下唯一索引兜底：同一活跃告警只留一条
        }
    }

    /** 异常恢复后把对应 ACTIVE 告警自动核销（设备补齐缺口/接管审批等） */
    private void resolveAlert(String dedupKey, String note) {
        if (dedupKey == null) {
            return;
        }
        alertRepository.findByDedupKeyAndStatus(dedupKey, "ACTIVE").ifPresent(alert -> {
            alert.setStatus("ACK");
            alert.setAckBy("系统");
            alert.setAckAt(LocalDateTime.now());
            alertRepository.save(alert);
        });
    }

    /** 越界事件隔离时给主管留一条可追踪告警（每个原因键去重） */
    private void alertTakeoverBoundary(TimingDevice device, DeviceTakeover takeover, String reason, LapEvent event) {
        String key = "BOUNDARY:" + takeover.getSessionId() + ":" + device.getId() + ":" + reason;
        raiseAlert(device, "QUARANTINED",
                device.getDeviceNo() + " 序号 " + event.getSeqNo() + " 越过接管边界（"
                        + TrackDict.quarantineReasonName(reason) + "），已进入隔离区等待主管处理", key);
    }

    /** 定时巡检：心跳超时的关联设备转离线并告警（每 10 秒）；恢复心跳收到时自然回到在线 */
    @Scheduled(fixedDelay = 10_000)
    public void scanOffline() {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now();
            for (TimingDevice device : deviceRepository.findAllByOrderByDeviceNoAsc()) {
                if (device.isQuarantined() || device.getSessionId() == null || device.getLastHeartbeatAt() == null) {
                    continue;
                }
                long age = Duration.between(device.getLastHeartbeatAt(), now).getSeconds();
                if (age > TrackDict.HEARTBEAT_FLAPPING_SECONDS) {
                    raiseAlert(device, "OFFLINE",
                            device.getDeviceNo() + " 已 " + age + " 秒无心跳（最后序号 "
                                    + device.getLastHeartbeatSeq() + "），判定离线",
                            "OFFLINE:" + device.getId());
                }
            }
        });
    }

    // ============================================================ 视图组装 / 通用

    /** 乐观锁版本校验：前端必须回传当前版本，过期页面/重复点击一律拒绝，不得覆盖新状态 */
    private void checkVersion(long current, Long requestVersion) {
        if (requestVersion == null || requestVersion != current) {
            throw new BizException("设备状态已被其他人更新（当前版本 v" + current
                    + "），请刷新韧性台后再操作，不能用过期页面覆盖新状态");
        }
    }

    private String deviceNo(Long deviceId) {
        if (deviceId == null) {
            return "—";
        }
        return deviceRepository.findById(deviceId).map(TimingDevice::getDeviceNo).orElse(String.valueOf(deviceId));
    }

    private Map<Long, String> driverNames() {
        Map<Long, String> names = new HashMap<>();
        for (Driver d : driverRepository.findAll()) {
            names.put(d.getId(), d.getName());
        }
        return names;
    }

    private Map<Long, String> kartNos() {
        Map<Long, String> nos = new HashMap<>();
        for (Kart k : kartRepository.findAll()) {
            nos.put(k.getId(), k.getKartNo());
        }
        return nos;
    }

    private Views.TimingDeviceView toDeviceView(TimingDevice device, RaceSession session, LocalDateTime now,
                                              Map<Long, String> driverNames, Map<Long, String> kartNos) {
        String hbStatus = TrackDict.heartbeatStatus(device.isQuarantined(), device.getSessionId(),
                device.isHeartbeatJitter(), device.getLastHeartbeatAt(), now);
        long ageSec = device.getLastHeartbeatAt() == null ? -1
                : Math.max(0, Duration.between(device.getLastHeartbeatAt(), now).getSeconds());
        DeviceStream stream = device.getSessionId() == null ? null
                : streamRepository.findBySessionIdAndDeviceId(device.getSessionId(), device.getId()).orElse(null);
        List<DeviceGap> openGaps = device.getSessionId() == null ? List.of()
                : gapRepository.findAllByDeviceIdAndStatusOrderByFromSeqAsc(device.getId(), "OPEN");
        List<LapEvent> pendings = device.getSessionId() == null ? List.of()
                : eventRepository.findAllBySessionIdAndDeviceIdOrderBySeqNoAsc(device.getSessionId(), device.getId())
                        .stream().filter(e -> "PENDING".equals(e.getStatus())).toList();
        long quarantinedCount = device.getSessionId() == null ? 0
                : eventRepository.findAllBySessionIdAndDeviceIdOrderBySeqNoAsc(device.getSessionId(), device.getId())
                        .stream().filter(e -> "QUARANTINED".equals(e.getStatus())).count();
        boolean currentSource = session != null && device.getId().equals(session.getCurrentSourceDeviceId());
        String sessionNo = device.getSessionId() == null ? null
                : sessionRepository.findById(device.getSessionId()).map(RaceSession::getSessionNo).orElse(null);
        return new Views.TimingDeviceView(
                device.getId(),
                device.getDeviceNo(),
                device.getName(),
                device.getRole(),
                TrackDict.deviceRoleName(device.getRole()),
                device.getTrackConfig(),
                TrackDict.trackConfigName(device.getTrackConfig()),
                device.getSessionId(),
                sessionNo,
                device.isQuarantined(),
                device.getQuarantineReason(),
                device.isHeartbeatJitter(),
                hbStatus,
                TrackDict.heartbeatStatusName(hbStatus),
                device.getLastHeartbeatAt(),
                device.getLastHeartbeatSeq(),
                ageSec,
                ageText(ageSec),
                currentSource,
                device.getVersion(),
                stream == null ? 0 : stream.getConfirmedSeq(),
                stream == null ? 0 : stream.getObservedSeq(),
                stream == null ? null : stream.getBoundaryAfterSeq(),
                stream == null ? 0 : stream.getLapCount(),
                openGaps.size(),
                pendings.size(),
                quarantinedCount,
                openGaps.stream().map(g -> toGapView(g, device.getDeviceNo())).toList(),
                pendings.stream().map(e -> toEventView(e, driverNames, kartNos)).toList());
    }

    private String ageText(long ageSec) {
        if (ageSec < 0) {
            return "从无心跳";
        }
        if (ageSec < 60) {
            return ageSec + " 秒前";
        }
        if (ageSec < 3600) {
            return (ageSec / 60) + " 分钟前";
        }
        return (ageSec / 3600) + " 小时前";
    }

    private Views.LapEventView toEventView(LapEvent e, Map<Long, String> driverNames, Map<Long, String> kartNos) {
        return new Views.LapEventView(
                e.getId(),
                e.getSessionId(),
                e.getDeviceId(),
                deviceNo(e.getDeviceId()),
                e.getEventUid(),
                e.getSeqNo(),
                e.getOccurredAt(),
                e.getDriverId(),
                driverNames.getOrDefault(e.getDriverId(), "—"),
                e.getKartId(),
                kartNos.getOrDefault(e.getKartId(), "—"),
                e.getLapTimeMs(),
                TrackDict.formatLap(e.getLapTimeMs()),
                e.getValid(),
                TrackDict.lapValidName(e.getValid()),
                e.getStatus(),
                TrackDict.lapEventStatusName(e.getStatus()),
                e.getQuarantineReason(),
                e.getQuarantineReason() == null ? null : TrackDict.quarantineReasonName(e.getQuarantineReason()),
                e.getLapId(),
                e.isRedFlag(),
                e.getRedFlagEventId(),
                e.getReplayCount(),
                e.getRuledBy(),
                e.getRuledAt(),
                e.getCreatedAt());
    }

    private Views.DeviceGapView toGapView(DeviceGap g, String deviceNo) {
        return new Views.DeviceGapView(g.getId(), g.getDeviceId(), deviceNo,
                g.getFromSeq(), g.getToSeq(), g.getStatus(), TrackDict.gapStatusName(g.getStatus()),
                g.getOpenedAt(), g.getFilledAt());
    }

    private Views.TakeoverView toTakeoverView(DeviceTakeover t) {
        TimingDevice primary = deviceRepository.findById(t.getPrimaryDeviceId()).orElse(null);
        TimingDevice backup = deviceRepository.findById(t.getBackupDeviceId()).orElse(null);
        return new Views.TakeoverView(
                t.getId(),
                t.getSessionId(),
                t.getPrimaryDeviceId(),
                primary == null ? String.valueOf(t.getPrimaryDeviceId()) : primary.getDeviceNo(),
                t.getBackupDeviceId(),
                backup == null ? String.valueOf(t.getBackupDeviceId()) : backup.getDeviceNo(),
                t.getBoundarySeq(),
                t.getBoundaryAt(),
                t.getStatus(),
                TrackDict.takeoverStatusName(t.getStatus()),
                t.getRequestedBy(),
                t.getRequestedAt(),
                t.getRejectReason(),
                t.getRequestVersion(),
                t.getDecidedBy(),
                t.getDecidedAt(),
                "REJECTED".equals(t.getStatus()) ? t.getRejectReason() : null,
                "APPROVED".equals(t.getStatus()));
    }

    private Views.DeviceAlertView toAlertView(DeviceAlert a, String deviceNo) {
        return new Views.DeviceAlertView(a.getId(), a.getSessionId(), a.getDeviceId(), deviceNo,
                a.getType(), TrackDict.alertTypeName(a.getType()), a.getContent(), a.getStatus(),
                a.getCreatedAt(), a.getAckBy(), a.getAckAt());
    }

    private Views.IngestResult ingestView(LapEvent e, String message) {
        return new Views.IngestResult(true, e.getEventUid(), e.getSeqNo(), e.getStatus(),
                TrackDict.lapEventStatusName(e.getStatus()), e.getQuarantineReason(),
                e.getQuarantineReason() == null ? null : TrackDict.quarantineReasonName(e.getQuarantineReason()),
                e.getLapId(), e.getReplayCount(), e.isRedFlag(), message);
    }

    /**
     * 场次设备时间线：告警/接管/缺口/最近心跳合并按时间倒序，
     * 让值班人员能回看一次比赛内完整的计时来源变化与断线补偿过程。
     */
    private List<Views.DeviceTimelineView> buildTimeline(RaceSession session, List<TimingDevice> bound,
                                                        List<DeviceGap> gaps, List<DeviceTakeover> takeovers,
                                                        List<DeviceAlert> alerts, LocalDateTime now) {
        List<Views.DeviceTimelineView> items = new ArrayList<>();
        for (DeviceAlert a : alerts) {
            String tone = switch (a.getType()) {
                case "OFFLINE", "GAP", "JUMP", "QUARANTINED" -> "danger";
                case "TAKEOVER_REQ", "FLAPPING" -> "warn";
                default -> "info";
            };
            items.add(new Views.DeviceTimelineView(a.getCreatedAt(), "ALERT",
                    TrackDict.alertTypeName(a.getType()), a.getDeviceId(), deviceNo(a.getDeviceId()),
                    a.getContent() + ("ACK".equals(a.getStatus()) ? "（已确认）" : ""), tone));
        }
        for (DeviceTakeover t : takeovers) {
            String verb = switch (t.getStatus()) {
                case "APPROVED" -> "主管批准";
                case "REJECTED" -> "主管驳回";
                case "CANCELED" -> "已撤回";
                default -> "值班人员发起";
            };
            items.add(new Views.DeviceTimelineView(
                    "APPROVED".equals(t.getStatus()) && t.getDecidedAt() != null ? t.getDecidedAt() : t.getRequestedAt(),
                    "TAKEOVER", "主备接管", t.getBackupDeviceId(), deviceNo(t.getBackupDeviceId()),
                    verb + "：" + deviceNo(t.getPrimaryDeviceId()) + " → " + deviceNo(t.getBackupDeviceId())
                            + "，边界序号 " + t.getBoundarySeq() + "，边界时刻 " + t.getBoundaryAt(),
                    "APPROVED".equals(t.getStatus()) ? "ok" : "info"));
        }
        for (DeviceGap g : gaps) {
            String range = g.getFromSeq() == g.getToSeq() ? String.valueOf(g.getFromSeq())
                    : g.getFromSeq() + ".." + g.getToSeq();
            items.add(new Views.DeviceTimelineView(
                    "FILLED".equals(g.getStatus()) && g.getFilledAt() != null ? g.getFilledAt() : g.getOpenedAt(),
                    "GAP", "序号缺口", g.getDeviceId(), deviceNo(g.getDeviceId()),
                    "FILLED".equals(g.getStatus()) ? "缺口 " + range + " 已按序补齐" : "发现缺口 " + range + "，后续事件待对账",
                    "FILLED".equals(g.getStatus()) ? "ok" : "warn"));
        }
        Set<Long> boundIds = new HashSet<>();
        for (TimingDevice d : bound) {
            boundIds.add(d.getId());
        }
        for (DeviceHeartbeat hb : heartbeatRepository.findAllBySessionIdOrderByReceivedAtDesc(session.getId())) {
            if (!boundIds.contains(hb.getDeviceId())) {
                continue;
            }
            items.add(new Views.DeviceTimelineView(hb.getReceivedAt(), "HEARTBEAT",
                    hb.isAccepted() ? "心跳" : "心跳被拒", hb.getDeviceId(), deviceNo(hb.getDeviceId()),
                    hb.isAccepted() ? "心跳序号 " + hb.getSeqNo()
                            : "心跳未受理：" + heartbeatRejectName(hb.getRejectReason()),
                    hb.isAccepted() ? "mute" : "danger"));
        }
        items.sort(Comparator.comparing(Views.DeviceTimelineView::at).reversed());
        return items.size() <= 80 ? items : items.subList(0, 80);
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
