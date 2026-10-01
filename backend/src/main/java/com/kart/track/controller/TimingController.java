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
import com.kart.track.service.TimingService;

import jakarta.validation.Valid;

/**
 * 计时设备韧性台。
 *
 * <p>权限收口（身份由 X-Operator-Id / X-Operator-Role 请求头带入）：
 * <ul>
 *   <li>设备→服务端链路（心跳 / 圈速事件 / 批量重放）不要求操作人身份，但每种情况返回明确受理结果；</li>
 *   <li>值班人员：查看状态、登记/关联设备、确认告警、发起或撤回接管请求；</li>
 *   <li>主管：批准/驳回接管、隔离/解除隔离设备、处理隔离区数据。越权调用返回 403。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/timing")
public class TimingController {

    private final TimingService timingService;

    public TimingController(TimingService timingService) {
        this.timingService = timingService;
    }

    /** 韧性台一屏：设备时间线、心跳状态、缺口、待对账、当前来源、接管记录、隔离事件 */
    @GetMapping("/console")
    public ApiResponse<Views.TimingConsoleView> console(@RequestParam(value = "sessionId", required = false) Long sessionId) {
        return ApiResponse.success(timingService.console(sessionId));
    }

    /** 值班人员登记设备稳定身份（设备号唯一、角色与归属赛道安装时确定） */
    @PostMapping("/devices/register")
    public ApiResponse<Views.TimingDeviceView> register(@Valid @RequestBody TimingDtos.DeviceRegisterReq req) {
        return ApiResponse.success("设备已登记", timingService.register(req));
    }

    /** 值班人员把设备关联到当前场次（带版本号，过期页面拒绝） */
    @PostMapping("/devices/{id}/bind")
    public ApiResponse<Views.TimingDeviceView> bind(@PathVariable("id") Long id,
                                                    @Valid @RequestBody TimingDtos.BindReq req) {
        return ApiResponse.success("设备已关联到场次", timingService.bind(id, req));
    }

    /** 值班人员解除设备关联（当前来源设备在场次进行中不允许解绑） */
    @PostMapping("/devices/{id}/unbind")
    public ApiResponse<Views.TimingDeviceView> unbind(@PathVariable("id") Long id,
                                                      @Valid @RequestBody TimingDtos.VersionReq req) {
        return ApiResponse.success("设备已解除关联", timingService.unbind(id, req));
    }

    /** 设备心跳：未关联/场次已结束/已隔离均返回明确受理结果 */
    @PostMapping("/devices/{deviceNo}/heartbeat")
    public ApiResponse<Views.HeartbeatResult> heartbeat(@PathVariable("deviceNo") String deviceNo,
                                                       @Valid @RequestBody TimingDtos.HeartbeatReq req) {
        Views.HeartbeatResult result = timingService.heartbeat(deviceNo, req);
        String message = result.accepted() ? "心跳已受理" : result.rejectReasonName();
        return ApiResponse.success(message, result);
    }

    /** 单条圈速事件：必须携带设备侧唯一标识、递增序号、实际产生时刻 */
    @PostMapping("/devices/{deviceNo}/events")
    public ApiResponse<Views.IngestResult> ingest(@PathVariable("deviceNo") String deviceNo,
                                                  @Valid @RequestBody TimingDtos.LapEventReq req) {
        Views.IngestResult result = timingService.ingest(deviceNo, req);
        return ApiResponse.success(result.message(), result);
    }

    /** 断线恢复批量重放：逐条返回结果；同一事件重放任意次只形成一条圈速记录，补偿中断从断点续传 */
    @PostMapping("/devices/{deviceNo}/events/replay")
    public ApiResponse<List<Views.IngestResult>> replay(@PathVariable("deviceNo") String deviceNo,
                                                       @Valid @RequestBody List<TimingDtos.LapEventReq> events) {
        return ApiResponse.success("批量重放处理完成（逐条结果见下）", timingService.replay(deviceNo, events));
    }

    /** 值班人员发起主备接管请求（仅记录请求，主管批准后才切换来源） */
    @PostMapping("/sessions/{sessionId}/takeovers/request")
    public ApiResponse<Views.TakeoverView> requestTakeover(@PathVariable("sessionId") Long sessionId,
                                                          @Valid @RequestBody TimingDtos.TakeoverRequestReq req) {
        return ApiResponse.success("接管请求已提交，等待主管审批", timingService.requestTakeover(sessionId, req));
    }

    /** 主管批准接管：边界时刻+边界序号原子生效，当前来源切换到备用设备 */
    @PostMapping("/takeovers/{id}/approve")
    public ApiResponse<Views.TakeoverView> approve(@PathVariable("id") Long id) {
        return ApiResponse.success("接管已生效，当前数据来源已切换，越界事件已进入隔离区",
                timingService.approveTakeover(id));
    }

    /** 主管驳回接管请求（数据来源维持主设备） */
    @PostMapping("/takeovers/{id}/reject")
    public ApiResponse<Views.TakeoverView> reject(@PathVariable("id") Long id,
                                                  @Valid @RequestBody TimingDtos.TakeoverRejectReq req) {
        return ApiResponse.success("接管请求已驳回，数据来源保持不变", timingService.rejectTakeover(id, req));
    }

    /** 值班人员在主管审批前撤回接管请求 */
    @PostMapping("/takeovers/{id}/cancel")
    public ApiResponse<Views.TakeoverView> cancel(@PathVariable("id") Long id) {
        return ApiResponse.success("接管请求已撤回", timingService.cancelTakeover(id));
    }

    /** 主管隔离设备：隔离期间一切上报明确拒绝、不混入本场成绩 */
    @PostMapping("/devices/{id}/quarantine")
    public ApiResponse<Views.TimingDeviceView> quarantine(@PathVariable("id") Long id,
                                                          @Valid @RequestBody TimingDtos.QuarantineReq req) {
        return ApiResponse.success("设备已隔离，其上报将被拒绝并留痕", timingService.quarantine(id, req));
    }

    /** 主管解除隔离 */
    @PostMapping("/devices/{id}/release-quarantine")
    public ApiResponse<Views.TimingDeviceView> releaseQuarantine(@PathVariable("id") Long id,
                                                                 @Valid @RequestBody TimingDtos.VersionReq req) {
        return ApiResponse.success("设备已解除隔离，恢复受理上报", timingService.releaseQuarantine(id, req));
    }

    /** 主管处理隔离区事件：丢弃 / 重新归段 */
    @PostMapping("/events/{id}/rule")
    public ApiResponse<Views.LapEventView> ruleEvent(@PathVariable("id") Long id,
                                                     @Valid @RequestBody TimingDtos.QuarantineRuleReq req) {
        String message = "DISCARD".equals(req.getAction()) ? "事件已丢弃并留底，永不入榜" : "事件已重新归段处理";
        return ApiResponse.success(message, timingService.ruleQuarantineEvent(id, req));
    }

    /** 值班人员确认告警（确认即冻结，重复确认不覆盖新状态） */
    @PostMapping("/alerts/{id}/ack")
    public ApiResponse<Views.DeviceAlertView> ackAlert(@PathVariable("id") Long id) {
        return ApiResponse.success("告警已确认", timingService.acknowledgeAlert(id));
    }
}
