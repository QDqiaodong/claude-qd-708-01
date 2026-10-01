package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Driver;
import com.kart.track.domain.RaceSession;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.DriverDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.DriverRepository;
import com.kart.track.repository.RaceSessionRepository;
import com.kart.track.repository.RedFlagEventRepository;

/** 模块二：车手（表格 + 最快圈速榜 + 报名本场）。 */
@Service
public class DriverService {

    private final DriverRepository driverRepository;
    private final RaceSessionRepository sessionRepository;
    private final RedFlagEventRepository redFlagEventRepository;

    public DriverService(DriverRepository driverRepository,
                         RaceSessionRepository sessionRepository,
                         RedFlagEventRepository redFlagEventRepository) {
        this.driverRepository = driverRepository;
        this.sessionRepository = sessionRepository;
        this.redFlagEventRepository = redFlagEventRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.DriverView> list() {
        Map<Long, String> sessionName = new HashMap<>();
        for (RaceSession session : sessionRepository.findAll()) {
            sessionName.put(session.getId(), session.getSessionNo());
        }
        List<Views.DriverView> list = new ArrayList<>();
        for (Driver driver : driverRepository.findAllByOrderByMemberNoAsc()) {
            list.add(toView(driver, sessionName.get(driver.getSessionId())));
        }
        return list;
    }

    /** 历史最快圈速榜：只含有成绩的车手，按最快圈速升序 */
    @Transactional(readOnly = true)
    public List<Views.LeaderRow> leaderboard() {
        List<Views.LeaderRow> rows = new ArrayList<>();
        int rank = 0;
        Integer best = null;
        for (Driver driver : driverRepository.findAllByOrderByBestLapMsAsc()) {
            if (driver.getBestLapMs() == null || driver.getBestLapMs() <= 0) {
                continue;
            }
            if (best == null) {
                best = driver.getBestLapMs();
            }
            rank += 1;
            rows.add(new Views.LeaderRow(
                    rank,
                    driver.getName(),
                    "历史最佳",
                    driver.getBestLapMs(),
                    TrackDict.formatLap(driver.getBestLapMs()),
                    TrackDict.formatDelta(driver.getBestLapMs(), best),
                    driver.getTotalSessions(),
                    rank == 1));
        }
        return rows;
    }

    @Transactional
    public Views.DriverView create(DriverDtos.DriverSaveReq req) {
        if (!TrackDict.isValidAgeGroup(req.getAgeGroup())) {
            throw new BizException("年龄分组只能是 儿童/青少年/成人");
        }
        if (!TrackDict.isValidLicense(req.getLicenseLevel())) {
            throw new BizException("驾照等级只能是 无证/初级/中级/高级");
        }
        if (driverRepository.existsByMemberNo(req.getMemberNo())) {
            throw new BizException("会员号 " + req.getMemberNo() + " 已存在");
        }
        Driver driver = new Driver();
        driver.setMemberNo(req.getMemberNo());
        driver.setName(req.getName());
        driver.setPhone(req.getPhone());
        driver.setAgeGroup(req.getAgeGroup());
        driver.setLicenseLevel(req.getLicenseLevel());
        driver.setTotalSessions(0);
        driver.setBestLapMs(0);
        driver.setTotalSpend(req.getTotalSpend() == null ? BigDecimal.ZERO : req.getTotalSpend());
        driver.setRegisterDate(req.getRegisterDate() == null ? LocalDate.now() : req.getRegisterDate());
        driver.setSessionId(null);
        return toView(driverRepository.save(driver), null);
    }

    /** 报名本场：写入车手当前场次，并占用一个名额 */
    @Transactional
    public Views.DriverView enroll(Long id, DriverDtos.EnrollReq req) {
        Driver driver = driverRepository.findById(id).orElseThrow(() -> new BizException("车手不存在"));
        RaceSession session = sessionRepository.findById(req.getSessionId())
                .orElseThrow(() -> new BizException("场次不存在"));
        if (!"PENDING".equals(session.getStatus()) && !"RUNNING".equals(session.getStatus())) {
            throw new BizException("场次 " + session.getSessionNo() + " 不接受报名");
        }
        if (redFlagEventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE").isPresent()) {
            throw new BizException("场次 " + session.getSessionNo() + " 红旗处理中，暂停报名");
        }
        if (driver.getSessionId() != null && !driver.getSessionId().equals(session.getId())) {
            throw new BizException(driver.getName() + " 已报名其它场次，请先退赛");
        }
        if (driver.getSessionId() != null && driver.getSessionId().equals(session.getId())) {
            throw new BizException(driver.getName() + " 已在本场名单中");
        }
        if ("NIGHT".equals(session.getTrackConfig()) && "KID".equals(driver.getAgeGroup())) {
            throw new BizException("夜间场不接待儿童组车手");
        }
        if (session.getSignedCount() >= session.getMaxKarts()) {
            throw new BizException("本场报名人数已达上限 " + session.getMaxKarts() + " 人");
        }
        driver.setSessionId(session.getId());
        session.setSignedCount(session.getSignedCount() + 1);
        sessionRepository.save(session);
        return toView(driverRepository.save(driver), session.getSessionNo());
    }

    /** 退赛：清空当前场次并释放名额 */
    @Transactional
    public Views.DriverView quit(Long id) {
        Driver driver = driverRepository.findById(id).orElseThrow(() -> new BizException("车手不存在"));
        if (driver.getSessionId() == null) {
            throw new BizException(driver.getName() + " 当前没有报名任何场次");
        }
        RaceSession session = sessionRepository.findById(driver.getSessionId()).orElse(null);
        if (session != null && redFlagEventRepository.findBySessionIdAndStatus(session.getId(), "ACTIVE").isPresent()) {
            throw new BizException("场次 " + session.getSessionNo() + " 红旗处理中，车手离赛请由处置台核查/收口处理");
        }
        driver.setSessionId(null);
        driverRepository.save(driver);
        if (session != null && session.getSignedCount() > 0) {
            session.setSignedCount(session.getSignedCount() - 1);
            sessionRepository.save(session);
        }
        return toView(driver, null);
    }

    Views.DriverView toView(Driver driver, String sessionNo) {
        return new Views.DriverView(
                driver.getId(),
                driver.getMemberNo(),
                driver.getName(),
                driver.getPhone(),
                driver.getAgeGroup(),
                TrackDict.ageGroupName(driver.getAgeGroup()),
                driver.getLicenseLevel(),
                TrackDict.licenseName(driver.getLicenseLevel()),
                driver.getTotalSessions(),
                driver.getBestLapMs(),
                TrackDict.formatLap(driver.getBestLapMs()),
                driver.getTotalSpend(),
                driver.getRegisterDate(),
                driver.getSessionId(),
                sessionNo);
    }
}
