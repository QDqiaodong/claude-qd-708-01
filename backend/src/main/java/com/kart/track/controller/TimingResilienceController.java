package com.kart.track.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kart.track.common.ApiResponse;
import com.kart.track.dto.TimingDtos;
import com.kart.track.dto.Views;
import com.kart.track.service.TimingResilienceService;

import jakarta.validation.Valid;

/**
 * 计时设备韧性台。
 *
 * <p>设备侧接口（/devices/.../heartbeat、/lap-events）模拟真实计时门直连上报，不要求操作人身份；
 * 管理动作按角色收口（身份由 X-Operator-Id / X-Operator-Role 请求头带入）：
 * <ul>
 *   <li>值班人员：查看状态、确认告警、发起/撤销接管请求；</li>
 *   <li>主管：执行/驳回接管、隔离/解除隔离设备、处理隔离区事件（丢弃/重新归段）、设备注册与绑定。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/resilience")
public class TimingResilienceController {

    private final TimingResilienceService service;

    public TimingResilienceController(TimingResilienceService service) {
        this.service = service;
    }

    // ---------------- 韧性台一屏 / 设备管理 ----------------

    /** 按场次展示：设备时间线、心跳状态、缺口、待对账、当前来源、接管记录、隔离事件 */
    @GetMapping("/console")
    public ApiResponse<Views.TimingConsoleView> console(
            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return ApiResponse.success(service.console(sessionId));
    }

    /** 设备台账（未关联本场的设备也可查；主管注册/绑定时用） */
    @GetMapping("/devices")
    public ApiResponse<List<Views.TimingDeviceView>> devices(
            @RequestParam(value = "trackCode", required = false) String trackCode) {
        return ApiResponse.success(service.listDevices(trackCode));
    }

    @GetMapping("/devices/{id}")
    public ApiResponse<Views.TimingDeviceView> device(@PathVariable("id") Long id) {
        return ApiResponse.success(service.deviceDetail(id));
    }

    /** 主管注册新设备（稳定身份码 + 所属赛道 + 主备角色） */
    @PostMapping("/devices")
    public ApiResponse<Views.TimingDeviceView> register(@RequestParam("deviceCode") String deviceCode,
                                                        @RequestParam("name") String name,
                                                        @RequestParam("trackCode") String trackCode,
                                                        @RequestParam(value = "role", defaultValue = "PRIMARY") String role) {
        return ApiResponse.success("计时设备已登记", service.registerDevice(deviceCode, name, trackCode, role));
    }

    /** 主管把设备关联到本场（sessionId 传空 = 解除关联）并调整主备角色 */
    @PostMapping("/devices/{id}/bind")
    public ApiResponse<Views.TimingDeviceView> bind(@PathVariable("id") Long id,
                                                    @Valid @RequestBody TimingDtos.BindReq req) {
        return ApiResponse.success("设备关联已更新", service.bindDevice(id, req));
    }

    /** 主管隔离设备（隔离后其圈速上报一律拒绝） */
    @PostMapping("/devices/{id}/quarantine")
    public ApiResponse<Views.TimingDeviceView> quarantine(@PathVariable("id") Long id,
                                                          @Valid @RequestBody TimingDtos.QuarantineReq req) {
        return ApiResponse.success("设备已隔离，其圈速上报将被拒绝", service.quarantineDevice(id, req));
    }

    /** 主管解除设备隔离 */
    @PostMapping("/devices/{id}/release")
    public ApiResponse<Views.TimingDeviceView> release(@PathVariable("id") Long id) {
        return ApiResponse.success("设备已解除隔离", service.releaseDevice(id));
    }

    // ---------------- 设备侧上报 ----------------

    /** 计时设备心跳（设备周期调用；重复心跳序号幂等） */
    @PostMapping("/devices/heartbeat")
    public ApiResponse<Views.HeartbeatResult> heartbeat(@Valid @RequestBody TimingDtos.HeartbeatReq req) {
        Views.HeartbeatResult result = service.heartbeat(req);
        return ApiResponse.success(result.message(), result);
    }

    /** 圈速事件上报（实时与断线重放同入口；eventUid 去重、seq 顺序校验） */
    @PostMapping("/lap-events")
    public ApiResponse<Views.LapIngestResult> ingestLap(@Valid @RequestBody TimingDtos.LapEventReq req) {
        Views.LapIngestResult result = service.ingestLap(req);
        return ApiResponse.success(result.resultText(), result);
    }

    // ---------------- 接管 ----------------

    /** 值班人员发起主备接管请求 */
    @PostMapping("/sessions/{sessionId}/takeover-requests")
    public ApiResponse<Views.TimingTakeoverView> requestTakeover(
            @PathVariable("sessionId") Long sessionId,
            @Valid @RequestBody TimingDtos.TakeoverReqReq req) {
        return ApiResponse.success("接管请求已提交，等待主管执行", service.requestTakeover(sessionId, req));
    }

    /** 主管执行接管（同事务切换数据来源并落定边界） */
    @PostMapping("/takeovers/{id}/execute")
    public ApiResponse<Views.TimingTakeoverView> executeTakeover(@PathVariable("id") Long id) {
        return ApiResponse.success("接管完成：边界之后只认备用设备数据", service.executeTakeover(id));
    }

    /** 主管驳回接管请求 */
    @PostMapping("/takeovers/{id}/reject")
    public ApiResponse<Views.TimingTakeoverView> rejectTakeover(
            @PathVariable("id") Long id,
            @Valid @RequestBody TimingDtos.RejectReq req) {
        return ApiResponse.success("接管请求已驳回", service.rejectTakeover(id, req));
    }

    /** 值班人员撤销尚未执行的接管请求 */
    @PostMapping("/takeovers/{id}/cancel")
    public ApiResponse<Views.TimingTakeoverView> cancelTakeover(@PathVariable("id") Long id) {
        return ApiResponse.success("接管请求已撤销", service.cancelTakeover(id));
    }

    // ---------------- 告警 / 隔离区 ----------------

    /** 值班人员确认告警（幂等，不复活） */
    @PostMapping("/alerts/{id}/ack")
    public ApiResponse<Views.TimingAlertView> ackAlert(@PathVariable("id") Long id) {
        return ApiResponse.success("告警已确认", service.acknowledgeAlert(id));
    }

    /** 主管丢弃隔离区事件 */
    @PostMapping("/quarantine-events/{id}/discard")
    public ApiResponse<Views.TimingLapEventView> discardQuarantined(
            @PathVariable("id") Long id,
            @RequestBody(required = false) TimingDtos.QuarantineDecisionReq req) {
        TimingDtos.QuarantineDecisionReq body = req == null ? new TimingDtos.QuarantineDecisionReq() : req;
        body.setDecision("DISCARD");
        return ApiResponse.success("事件已丢弃，仅保留留底", service.discardQuarantined(id, body));
    }

    /** 主管把隔离区事件重新归段（重走序号链） */
    @PostMapping("/quarantine-events/{id}/resegment")
    public ApiResponse<Views.TimingLapEventView> resegmentQuarantined(
            @PathVariable("id") Long id,
            @RequestBody(required = false) TimingDtos.QuarantineDecisionReq req) {
        TimingDtos.QuarantineDecisionReq body = req == null ? new TimingDtos.QuarantineDecisionReq() : req;
        body.setDecision("RESEGMENT");
        return ApiResponse.success("事件已按主管认定重新归段", service.resegmentQuarantined(id, body));
    }
}
