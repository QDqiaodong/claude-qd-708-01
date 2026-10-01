package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.SessionDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.SessionService;

import jakarta.validation.Valid;

/** 模块三：场次。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<Views.SessionView>> list() {
        return ApiResponse.success(sessionService.list());
    }

    @PostMapping
    public ApiResponse<Views.SessionView> create(@Valid @RequestBody SessionDtos.SessionSaveReq req) {
        return ApiResponse.success("场次已建立", sessionService.create(req));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<Views.SessionView> start(@PathVariable("id") Long id) {
        return ApiResponse.success("比赛开始", sessionService.start(id));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<Views.SessionView> finish(@PathVariable("id") Long id) {
        return ApiResponse.success("本场已结束，车辆已收车", sessionService.finish(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Views.SessionView> cancel(@PathVariable("id") Long id) {
        return ApiResponse.success("本场已取消", sessionService.cancel(id));
    }
}
