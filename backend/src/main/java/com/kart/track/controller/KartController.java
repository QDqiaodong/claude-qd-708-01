package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.KartDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.KartService;

import jakarta.validation.Valid;

/** 模块一：卡丁车。 */
@RestController
@RequestMapping("/api/karts")
public class KartController {

    private final KartService kartService;

    public KartController(KartService kartService) {
        this.kartService = kartService;
    }

    @GetMapping
    public ApiResponse<List<Views.KartView>> list() {
        return ApiResponse.success(kartService.list());
    }

    @PostMapping
    public ApiResponse<Views.KartView> create(@Valid @RequestBody KartDtos.KartSaveReq req) {
        return ApiResponse.success("车辆已建档", kartService.create(req));
    }

    /** 状态切换：可用 ↔ 维修 ↔ 停用 */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.KartView> changeStatus(@PathVariable("id") Long id,
                                                    @Valid @RequestBody KartDtos.StatusReq req) {
        return ApiResponse.success("状态已更新", kartService.changeStatus(id, req));
    }

    /** 派车到本场 */
    @PostMapping("/{id}/dispatch")
    public ApiResponse<Views.KartView> dispatch(@PathVariable("id") Long id,
                                                @RequestBody KartDtos.DispatchReq req) {
        return ApiResponse.success("已派车上赛道", kartService.dispatch(id, req));
    }

    /** 收车 */
    @PostMapping("/{id}/recall")
    public ApiResponse<Views.KartView> recall(@PathVariable("id") Long id) {
        return ApiResponse.success("已收车", kartService.recall(id));
    }
}
