package com.kart.track.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.Views;
import com.kart.track.service.TrackService;

/** 赛道实况：顶栏计数器 + 一屏的实时数据（计时器 / 赛道点位 / 排行榜）。 */
@RestController
@RequestMapping("/api/track")
public class TrackController {

    private final TrackService trackService;

    public TrackController(TrackService trackService) {
        this.trackService = trackService;
    }

    @GetMapping("/overview")
    public ApiResponse<Views.Overview> overview() {
        return ApiResponse.success(trackService.overview());
    }

    /** sessionId 为空时自动取进行中的场次 */
    @GetMapping("/live")
    public ApiResponse<Views.LiveView> live(@RequestParam(value = "sessionId", required = false) Long sessionId) {
        return ApiResponse.success(trackService.live(sessionId));
    }
}
