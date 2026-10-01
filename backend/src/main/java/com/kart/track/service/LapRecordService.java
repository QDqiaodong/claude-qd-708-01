package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Driver;
import com.kart.track.domain.Kart;
import com.kart.track.domain.LapRecord;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.RedFlagCheck;
import com.kart.track.domain.RedFlagEvent;
import com.kart.track.domain.RedFlagLap;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.LapRecordDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.LapRecordRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagCheckRepository;
import com.kart.track.repository.RedFlagEventRepository;
import com.kart.track.repository.RedFlagLapRepository;

/**
 * 模块四：圈速记录。
 *
 * <p>本模块的写链路全部走实体上的 Hibernate 自定义 SQL：
 * 新增走 {@code @SQLInsert}、改判走 {@code @SQLUpdate}、删除走 {@code @SQLDelete}，
 * Hibernate 不会为这三件事生成默认语句。
 */
@Service
public class LapRecordService {

    private final LapRecordRepository lapRecordRepository;
    private final RaceSessionRepository sessionRepository;
    private final DriverRepository driverRepository;
    private final KartRepository kartRepository;
    private final RedFlagEventRepository redFlagEventRepository;
    private final RedFlagLapRepository redFlagLapRepository;
    private final RedFlagCheckRepository redFlagCheckRepository;

    public LapRecordService(LapRecordRepository lapRecordRepository,
                            RaceSessionRepository sessionRepository,
                            DriverRepository driverRepository,
                            KartRepository kartRepository,
                            RedFlagEventRepository redFlagEventRepository,
                            RedFlagLapRepository redFlagLapRepository,
                            RedFlagCheckRepository redFlagCheckRepository) {
        this.lapRecordRepository = lapRecordRepository;
        this.sessionRepository = sessionRepository;
        this.driverRepository = driverRepository;
        this.kartRepository = kartRepository;
        this.redFlagEventRepository = redFlagEventRepository;
        this.redFlagLapRepository = redFlagLapRepository;
        this.redFlagCheckRepository = redFlagCheckRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.LapRecordView> list(Long sessionId) {
        List<LapRecord> laps = sessionId == null
                ? lapRecordRepository.findAll()
                : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        Map<Long, String> driverName = new HashMap<>();
        for (Driver driver : driverRepository.findAll()) {
            driverName.put(driver.getId(), driver.getName());
        }
        Map<Long, String> kartNo = new HashMap<>();
        for (Kart kart : kartRepository.findAll()) {
            kartNo.put(kart.getId(), kart.getKartNo());
        }
        Map<Long, String> sessionNo = new HashMap<>();
        for (RaceSession session : sessionRepository.findAll()) {
            sessionNo.put(session.getId(), session.getSessionNo());
        }
        Map<Long, RedFlagLap> rulings = new HashMap<>();
        for (RedFlagLap row : redFlagLapRepository.findAll()) {
            rulings.put(row.getLapId(), row);
        }
        List<Views.LapRecordView> list = new ArrayList<>();
        for (LapRecord lap : laps) {
            RedFlagLap ruling = rulings.get(lap.getId());
            list.add(new Views.LapRecordView(
                    lap.getId(),
                    lap.getSessionId(),
                    sessionNo.get(lap.getSessionId()),
                    lap.getDriverId(),
                    driverName.get(lap.getDriverId()),
                    lap.getKartId(),
                    kartNo.get(lap.getKartId()),
                    lap.getLapIndex(),
                    lap.getLapTimeMs(),
                    TrackDict.formatLap(lap.getLapTimeMs()),
                    lap.isFastest(),
                    lap.getValid(),
                    TrackDict.lapValidName(lap.getValid()),
                    lap.getCreatedAt(),
                    ruling != null,
                    ruling == null ? null : ruling.getEventId(),
                    ruling == null ? null : ruling.getRuling(),
                    ruling == null ? null : TrackDict.lapRulingName(ruling.getRuling())));
        }
        return list;
    }

    /**
     * 登记一个圈速：insert 语句来自 {@code LapRecord} 上的 {@code @SQLInsert}。
     *
     * <p>【红旗计时隔离】场次处于「红旗处理中」时，圈速不丢弃：
     * 落 lap_record 留底但不写最快圈、不刷车手历史最好成绩，同时挂一条待裁定记录，
     * 明确产生于当前生效中的红旗事件；是否进榜由主管恢复前逐条裁定决定。
     * 正常进行中场次则按原规则：新纪录会把手写 update 里的旧最快圈复位。
     */
    @Transactional
    public Views.LapRecordView create(LapRecordDtos.LapSaveReq req) {
        RaceSession session = sessionRepository.findById(req.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        RedFlagEvent activeRedFlag = redFlagEventRepository
                .findBySessionIdAndStatus(session.getId(), "ACTIVE").orElse(null);
        boolean underRedFlag = "RED_FLAG".equals(session.getStatus()) || activeRedFlag != null;
        if (!"RUNNING".equals(session.getStatus()) && !underRedFlag) {
            throw new BizException("只有「进行中」的场次才能登记圈速，当前为" + TrackDict.sessionStatusName(session.getStatus()));
        }
        Driver driver = driverRepository.findById(req.getDriverId()).orElseThrow(() -> new BizException("车手不存在"));
        Kart kart = kartRepository.findById(req.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));

        // 车型门槛：竞技车要中级以上驾照，双人车要初级以上，儿童车只给儿童组
        String needLicense = TrackDict.modelMinLicense(kart.getModel());
        if (TrackDict.licenseRank(driver.getLicenseLevel()) < TrackDict.licenseRank(needLicense)) {
            throw new BizException(TrackDict.kartModelName(kart.getModel()) + " 需要 "
                    + TrackDict.licenseName(needLicense) + "以上驾照，" + driver.getName() + " 为"
                    + TrackDict.licenseName(driver.getLicenseLevel()));
        }
        if ("KID".equals(kart.getModel()) && !"KID".equals(driver.getAgeGroup())) {
            throw new BizException("儿童车只允许儿童组车手驾驶");
        }
        // 红旗期间以「本次事件核查清单里的车」为准：送修车已转 MAINTENANCE，其迟到的圈速仍要留底待裁定。
        // 清单外但仍 ON_TRACK 的车（理论上不会出现）必须确有本场圈速，防止别场在赛车串入。
        if (underRedFlag) {
            boolean inEvent = isKartUnderEvent(activeRedFlag, kart.getId());
            boolean ranInSession = lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId()).stream()
                    .anyMatch(lap -> lap.getKartId().equals(kart.getId()));
            if (!inEvent && !(ranInSession && "ON_TRACK".equals(kart.getStatus()))) {
                throw new BizException(kart.getKartNo() + " 不在本次红旗涉及车辆范围内，圈速不予接收");
            }
        } else if (!"ON_TRACK".equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 未在赛道上（当前" + TrackDict.kartStatusName(kart.getStatus()) + "），请先派车");
        }
        Integer ms = req.getLapTimeMs();
        if (ms == null || ms < TrackDict.MIN_LAP_MS || ms > TrackDict.MAX_LAP_MS) {
            throw new BizException("圈速应在 " + TrackDict.MIN_LAP_MS / 1000 + " 秒 ~ "
                    + TrackDict.MAX_LAP_MS / 1000 + " 秒之间");
        }
        String valid = req.getValid() == null || req.getValid().isBlank() ? "VALID" : req.getValid();
        if (!TrackDict.isValidLapValid(valid)) {
            throw new BizException("圈速判定只能是 有效/无效-出界/无效-碰撞");
        }

        int lapIndex = (int) lapRecordRepository.findByDriverIdOrderByLapTimeMsAsc(driver.getId()).stream()
                .filter(lap -> lap.getSessionId().equals(session.getId()))
                .count() + 1;

        LapRecord lap = new LapRecord();
        lap.setSessionId(session.getId());
        lap.setDriverId(driver.getId());
        lap.setKartId(kart.getId());
        lap.setLapIndex(lapIndex);
        lap.setLapTimeMs(ms);
        lap.setValid(valid);
        lap.setCreatedAt(LocalDateTime.now());

        boolean fastest = false;
        if (!underRedFlag && "VALID".equals(valid)) {
            Integer currentBest = null;
            for (LapRecord old : lapRecordRepository.findBySessionIdAndValidOrderByLapTimeMsAsc(session.getId(), "VALID")) {
                currentBest = old.getLapTimeMs();
                break;
            }
            fastest = currentBest == null || ms < currentBest;
            if (fastest && currentBest != null) {
                // 旧最快圈复位：走 @SQLUpdate 手写 update
                for (LapRecord old : lapRecordRepository.findBySessionIdOrderByCreatedAtDesc(session.getId())) {
                    if (old.isFastest()) {
                        old.setFastest(false);
                        lapRecordRepository.save(old);
                    }
                }
            }
        }
        lap.setFastest(fastest);

        // ↓↓↓ 真正落库的一句：Hibernate 用 @SQLInsert 里手写好的 insert，不再自己生成
        LapRecord saved = lapRecordRepository.save(lap);

        if (underRedFlag) {
            // 红旗期间：隔离为待裁定，不刷最快圈、不刷车手历史最好、不计里程
            RedFlagLap pending = new RedFlagLap();
            pending.setLapId(saved.getId());
            pending.setEventId(activeRedFlag.getId());
            pending.setSessionId(session.getId());
            pending.setRuling("PENDING");
            redFlagLapRepository.save(pending);
        } else if ("VALID".equals(valid)) {
            if (driver.getBestLapMs() == null || driver.getBestLapMs() <= 0 || ms < driver.getBestLapMs()) {
                driver.setBestLapMs(ms);
                driverRepository.save(driver);
            }
            BigDecimal km = TrackDict.configLapKm(session.getTrackConfig());
            kart.setTotalKm(kart.getTotalKm().add(km));
            kart.setTotalHours(kart.getTotalHours().add(BigDecimal.valueOf(ms / 3600000.0)));
            kart.setUpdatedAt(LocalDateTime.now());
            kartRepository.save(kart);
        }
        return list(session.getId()).stream()
                .filter(v -> v.id().equals(saved.getId()))
                .findFirst()
                .orElseThrow(() -> new BizException("圈速写入失败"));
    }

    private boolean isKartUnderEvent(RedFlagEvent event, Long kartId) {
        if (event == null) {
            return false;
        }
        for (RedFlagCheck check : redFlagCheckRepository.findAllByEventIdOrderByKartIdAsc(event.getId())) {
            if (check.getKartId().equals(kartId)) {
                return true;
            }
        }
        return false;
    }

    /** 改判：有效 ↔ 无效-出界 / 无效-碰撞（走 @SQLUpdate）。红旗期间记录受隔离，只能由主管在处置台裁定 */
    @Transactional
    public Views.LapRecordView setValid(Long id, LapRecordDtos.LapValidReq req) {
        LapRecord lap = lapRecordRepository.findById(id).orElseThrow(() -> new BizException("圈速记录不存在"));
        if (redFlagLapRepository.findByLapId(id).isPresent()) {
            throw new BizException("该圈产生于红旗期间，已隔离待主管在处置台裁定，不能在此直接改判");
        }
        if (!TrackDict.isValidLapValid(req.getValid())) {
            throw new BizException("圈速判定只能是 有效/无效-出界/无效-碰撞");
        }
        lap.setValid(req.getValid());
        if (!"VALID".equals(req.getValid())) {
            lap.setFastest(false);
        }
        lapRecordRepository.save(lap);
        return list(lap.getSessionId()).stream()
                .filter(v -> v.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException("圈速记录不存在"));
    }

    /** 删除一条误录（走 @SQLDelete）。红旗隔离记录保留为处置凭证，禁止删除 */
    @Transactional
    public void delete(Long id) {
        LapRecord lap = lapRecordRepository.findById(id).orElseThrow(() -> new BizException("圈速记录不存在"));
        if (redFlagLapRepository.findByLapId(id).isPresent()) {
            throw new BizException("该圈属于红旗期间记录，必须保留并由主管在处置台裁定，不能删除");
        }
        lapRecordRepository.delete(lap);
    }
}
