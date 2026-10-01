package com.kart.track.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kart.track.common.BizException;
import com.kart.track.domain.Kart;
import com.kart.track.domain.Maintenance;
import com.kart.track.domain.TrackDict;
import com.kart.track.dto.MaintenanceDtos;
import com.kart.track.dto.Views;
import com.kart.track.repository.KartRepository;
import com.kart.track.repository.MaintenanceRepository;

/** 模块五：维修保养（待修 → 维修中 → 已完工，总费用 = 工时 × 工时费 + 配件费）。 */
@Service
public class MaintenanceService {

    private final MaintenanceRepository maintenanceRepository;
    private final KartRepository kartRepository;

    public MaintenanceService(MaintenanceRepository maintenanceRepository, KartRepository kartRepository) {
        this.maintenanceRepository = maintenanceRepository;
        this.kartRepository = kartRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.MaintenanceView> list() {
        Map<Long, String> kartNo = new HashMap<>();
        for (Kart kart : kartRepository.findAll()) {
            kartNo.put(kart.getId(), kart.getKartNo());
        }
        List<Views.MaintenanceView> list = new ArrayList<>();
        for (Maintenance item : maintenanceRepository.findAllByOrderByStartTimeDesc()) {
            list.add(toView(item, kartNo.get(item.getKartId())));
        }
        return list;
    }

    /** 费用统计：已完工单的配件费 / 工时费 / 总费用 */
    @Transactional(readOnly = true)
    public Views.MaintSummary summary() {
        List<Maintenance> all = maintenanceRepository.findAll();
        BigDecimal partsFee = BigDecimal.ZERO;
        BigDecimal totalFee = BigDecimal.ZERO;
        long waiting = 0;
        long repairing = 0;
        long done = 0;
        for (Maintenance item : all) {
            if ("WAITING".equals(item.getStatus())) {
                waiting += 1;
            } else if ("REPAIRING".equals(item.getStatus())) {
                repairing += 1;
            } else if ("DONE".equals(item.getStatus())) {
                done += 1;
                partsFee = partsFee.add(nz(item.getPartsFee()));
                totalFee = totalFee.add(nz(item.getTotalFee()));
            }
        }
        BigDecimal labor = totalFee.subtract(partsFee);
        return new Views.MaintSummary(all.size(), waiting, repairing, done, partsFee, labor, totalFee);
    }

    @Transactional
    public Views.MaintenanceView create(MaintenanceDtos.MaintSaveReq req) {
        if (!TrackDict.isValidMaintType(req.getMaintType())) {
            throw new BizException("维修类型不合法");
        }
        if (maintenanceRepository.existsByOrderNo(req.getOrderNo())) {
            throw new BizException("维修单号 " + req.getOrderNo() + " 已存在");
        }
        Kart kart = kartRepository.findById(req.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));
        BigDecimal hours = req.getWorkHours() == null ? BigDecimal.ONE : req.getWorkHours();
        BigDecimal parts = req.getPartsFee() == null ? BigDecimal.ZERO : req.getPartsFee();
        BigDecimal total = hours.multiply(TrackDict.LABOR_RATE).add(parts);

        Maintenance item = new Maintenance();
        item.setOrderNo(req.getOrderNo());
        item.setKartId(kart.getId());
        item.setMaintType(req.getMaintType());
        item.setFaultDesc(req.getFaultDesc());
        item.setWorkHours(hours);
        item.setPartsFee(parts);
        item.setTotalFee(total);
        item.setWorker(req.getWorker());
        item.setStatus("WAITING");
        item.setStartTime(LocalDateTime.now());
        item.setEndTime(null);
        return toView(maintenanceRepository.save(item), kart.getKartNo());
    }

    /** 开工：待修 → 维修中，车辆置为维修 */
    @Transactional
    public Views.MaintenanceView start(Long id, MaintenanceDtos.WorkerReq req) {
        Maintenance item = maintenanceRepository.findById(id).orElseThrow(() -> new BizException("维修单不存在"));
        if (!"WAITING".equals(item.getStatus())) {
            throw new BizException("只有「待修」的维修单可以开工");
        }
        Kart kart = kartRepository.findById(item.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));
        if ("ON_TRACK".equals(kart.getStatus())) {
            throw new BizException(kart.getKartNo() + " 正在赛道上，不能开工维修");
        }
        if (req.getWorker() != null && !req.getWorker().isBlank()) {
            item.setWorker(req.getWorker());
        }
        if (item.getWorker() == null || item.getWorker().isBlank()) {
            throw new BizException("请填写维修人");
        }
        item.setStatus("REPAIRING");
        item.setStartTime(LocalDateTime.now());
        maintenanceRepository.save(item);

        kart.setStatus("MAINTENANCE");
        kart.setUpdatedAt(LocalDateTime.now());
        kartRepository.save(kart);
        return toView(item, kart.getKartNo());
    }

    /** 完工：维修中 → 已完工，车辆回到可用；保养类项目重置「上次保养日期」 */
    @Transactional
    public Views.MaintenanceView complete(Long id) {
        Maintenance item = maintenanceRepository.findById(id).orElseThrow(() -> new BizException("维修单不存在"));
        if (!"REPAIRING".equals(item.getStatus())) {
            throw new BizException("只有「维修中」的维修单可以完工");
        }
        item.setStatus("DONE");
        item.setEndTime(LocalDateTime.now());
        maintenanceRepository.save(item);

        Kart kart = kartRepository.findById(item.getKartId()).orElseThrow(() -> new BizException("车辆不存在"));
        kart.setStatus("AVAILABLE");
        kart.setUpdatedAt(LocalDateTime.now());
        if (TrackDict.isServiceType(item.getMaintType())) {
            kart.setLastServiceDate(LocalDate.now());
        }
        kartRepository.save(kart);
        return toView(item, kart.getKartNo());
    }

    Views.MaintenanceView toView(Maintenance item, String kartNo) {
        long minutes = 0;
        if (item.getStartTime() != null && item.getEndTime() != null) {
            minutes = ChronoUnit.MINUTES.between(item.getStartTime(), item.getEndTime());
        }
        return new Views.MaintenanceView(
                item.getId(),
                item.getOrderNo(),
                item.getKartId(),
                kartNo,
                item.getMaintType(),
                TrackDict.maintTypeName(item.getMaintType()),
                item.getFaultDesc(),
                item.getWorkHours(),
                item.getPartsFee(),
                item.getTotalFee(),
                item.getWorker(),
                item.getStartTime(),
                item.getEndTime(),
                item.getStatus(),
                TrackDict.maintStatusName(item.getStatus()),
                minutes);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
