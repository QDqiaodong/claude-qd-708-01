package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.DriverDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.DriverService;

import jakarta.validation.Valid;

/** 模块二：车手。 */
@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping
    public ApiResponse<List<Views.DriverView>> list() {
        return ApiResponse.success(driverService.list());
    }

    /** 历史最快圈速榜 */
    @GetMapping("/leaderboard")
    public ApiResponse<List<Views.LeaderRow>> leaderboard() {
        return ApiResponse.success(driverService.leaderboard());
    }

    @PostMapping
    public ApiResponse<Views.DriverView> create(@Valid @RequestBody DriverDtos.DriverSaveReq req) {
        return ApiResponse.success("车手已注册", driverService.create(req));
    }

    /** 报名本场 */
    @PostMapping("/{id}/enroll")
    public ApiResponse<Views.DriverView> enroll(@PathVariable("id") Long id,
                                                @RequestBody DriverDtos.EnrollReq req) {
        return ApiResponse.success("报名成功", driverService.enroll(id, req));
    }

    /** 退赛 */
    @PostMapping("/{id}/quit")
    public ApiResponse<Views.DriverView> quit(@PathVariable("id") Long id) {
        return ApiResponse.success("已退赛", driverService.quit(id));
    }
}
