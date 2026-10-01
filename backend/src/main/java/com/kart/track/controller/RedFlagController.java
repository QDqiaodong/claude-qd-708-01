package com.kart.track.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.RedFlagDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.RedFlagService;

import jakarta.validation.Valid;

/**
 * 红旗事件处置台。
 *
 * <p>权限收口（身份由 X-Operator-Id / X-Operator-Role 请求头带入）：
 * <ul>
 *   <li>值班人员：发起红旗 {@code POST /raise}、填写车辆核查 {@code POST /checks/{checkId}}；</li>
 *   <li>主管：裁定圈速 {@code POST /laps/{id}/ruling}、恢复 {@code POST /resume}、
 *       强制终止 {@code POST /terminate}。越权调用返回 403。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/red-flags")
public class RedFlagController {

    private final RedFlagService redFlagService;

    public RedFlagController(RedFlagService redFlagService) {
        this.redFlagService = redFlagService;
    }

    /** 处置台一屏：当前生效事件（可空）+ 本场历史红旗事件 */
    @GetMapping("/console")
    public ApiResponse<Views.RedFlagConsoleView> console(@RequestParam(value = "sessionId", required = false) Long sessionId) {
        return ApiResponse.success(redFlagService.console(sessionId));
    }

    /** 单次事件详情（场次详情回看历史红旗时用） */
    @GetMapping("/events/{id}")
    public ApiResponse<Views.RedFlagEventView> event(@PathVariable("id") Long id) {
        return ApiResponse.success(redFlagService.eventDetail(id));
    }

    /** 值班人员发起红旗；同场次重复/并发发起返回同一事件，不生成第二套清单 */
    @PostMapping("/sessions/{sessionId}/raise")
    public ApiResponse<Views.RedFlagEventView> raise(@PathVariable("sessionId") Long sessionId,
                                                     @Valid @RequestBody RedFlagDtos.RaiseReq req) {
        RedFlagService.RaiseOutcome outcome = redFlagService.raise(sessionId, req);
        String message = outcome.created() ? "红旗已发起，场次进入红旗处理中，正常圈速已暂停入榜"
                : "本场已有生效中的红旗事件，返回同一事件";
        return ApiResponse.success(message, outcome.eventView());
    }

    /** 值班人员提交一辆车的核查结论（可继续 / 退出比赛 / 需要维修） */
    @PostMapping("/events/{eventId}/checks/{checkId}")
    public ApiResponse<Views.RedFlagCheckView> submitCheck(@PathVariable("eventId") Long eventId,
                                                           @PathVariable("checkId") Long checkId,
                                                           @Valid @RequestBody RedFlagDtos.CheckReq req) {
        return ApiResponse.success("核查结论已提交", redFlagService.submitCheck(eventId, checkId, req));
    }

    /** 主管裁定一条红旗期间圈速（确认有效 / 作废） */
    @PostMapping("/events/{eventId}/laps/{lapId}/ruling")
    public ApiResponse<Views.RedFlagLapView> ruleLap(@PathVariable("eventId") Long eventId,
                                                     @PathVariable("lapId") Long lapId,
                                                     @Valid @RequestBody RedFlagDtos.RulingReq req) {
        return ApiResponse.success("圈速已裁定", redFlagService.ruleLap(eventId, lapId, req));
    }

    /** 主管恢复比赛（全部核查 + 全部裁定齐备才可恢复） */
    @PostMapping("/events/{eventId}/resume")
    public ApiResponse<Views.RedFlagEventView> resume(@PathVariable("eventId") Long eventId) {
        return ApiResponse.success("比赛已恢复，可继续记圈", redFlagService.resume(eventId));
    }

    /** 主管强制终止：一次性结束场次、释放车手和可用车辆，退出/送修车保留状态 */
    @PostMapping("/events/{eventId}/terminate")
    public ApiResponse<Views.RedFlagEventView> terminate(@PathVariable("eventId") Long eventId) {
        return ApiResponse.success("场次已强制终止，车手与可用车辆已释放", redFlagService.terminate(eventId));
    }
}
