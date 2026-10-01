package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.MaintenanceDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.MaintenanceService;

import jakarta.validation.Valid;

/** 模块五：维修保养。 */
@RestController
@RequestMapping("/api/maintenance")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    public MaintenanceController(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    @GetMapping
    public ApiResponse<List<Views.MaintenanceView>> list() {
        return ApiResponse.success(maintenanceService.list());
    }

    /** 费用统计：已完工单的配件费 / 工时费 / 总费用 */
    @GetMapping("/summary")
    public ApiResponse<Views.MaintSummary> summary() {
        return ApiResponse.success(maintenanceService.summary());
    }

    @PostMapping
    public ApiResponse<Views.MaintenanceView> create(@Valid @RequestBody MaintenanceDtos.MaintSaveReq req) {
        return ApiResponse.success("维修单已开立", maintenanceService.create(req));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<Views.MaintenanceView> start(@PathVariable("id") Long id,
                                                    @RequestBody MaintenanceDtos.WorkerReq req) {
        return ApiResponse.success("已开工", maintenanceService.start(id, req));
    }

    /** 完工：车辆回到可用，保养类项目重置上次保养日期 */
    @PostMapping("/{id}/complete")
    public ApiResponse<Views.MaintenanceView> complete(@PathVariable("id") Long id) {
        return ApiResponse.success("已完工，车辆恢复可用", maintenanceService.complete(id));
    }
}
