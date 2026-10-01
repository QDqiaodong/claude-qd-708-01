package com.kart.track.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 计时设备韧性台入参。
 *
 * <p>设备侧上报（心跳 / 圈速事件）模拟真实计时门：身份用 deviceCode，
 * 圈速必须带设备侧唯一标识 eventUid、本场递增序号 seq、实际产生时刻 occurredAt。
 */
public final class TimingDtos {

    private TimingDtos() {
    }

    /** 设备心跳上报（设备周期调用；hbSeq 设备侧自增） */
    @Data
    public static class HeartbeatReq {

        @NotBlank(message = "设备码不能为空")
        private String deviceCode;

        /** 心跳序号；为空时由服务端按最近序号 +1 */
        private Long hbSeq;

        /** 心跳实际产生时刻；为空取服务端当前时间 */
        private LocalDateTime occurredAt;
    }

    /** 圈速事件上报（支持断线后批量重放：每条都带全字段，重复上报幂等） */
    @Data
    public static class LapEventReq {

        @NotBlank(message = "设备码不能为空")
        private String deviceCode;

        /** 设备侧事件唯一标识（重放多少次都只形成一条圈速） */
        @NotBlank(message = "事件唯一标识 eventUid 不能为空")
        private String eventUid;

        @NotNull(message = "圈速序号不能为空")
        private Long seq;

        @NotNull(message = "车手不能为空")
        private Long driverId;

        @NotNull(message = "车辆不能为空")
        private Long kartId;

        @NotNull(message = "圈速毫秒数不能为空")
        private Integer lapTimeMs;

        /** VALID/OUT/CRASH，为空按 VALID */
        private String valid;

        /** 圈速实际产生时刻；为空取服务端当前时间（实时上报） */
        private LocalDateTime occurredAt;
    }

    /** 值班人员发起主备接管请求 */
    @Data
    public static class TakeoverReqReq {

        @NotNull(message = "备用设备不能为空")
        private Long backupDeviceId;

        @NotBlank(message = "接管原因不能为空")
        private String reason;
    }

    /** 主管驳回接管请求 */
    @Data
    public static class RejectReq {

        @NotBlank(message = "驳回原因不能为空")
        private String note;
    }

    /** 主管隔离 / 解除隔离设备 */
    @Data
    public static class QuarantineReq {

        /** 隔离时必填原因；解除隔离可空 */
        private String reason;
    }

    /** 主管把设备关联到本场 / 调整主备角色（运维操作） */
    @Data
    public static class BindReq {

        private Long sessionId;

        @NotBlank(message = "设备角色不能为空")
        private String role;
    }

    /** 主管处理隔离区一条圈速事件：DISCARD 丢弃 / RESEGMENT 重新归段 */
    @Data
    public static class QuarantineDecisionReq {

        /** DISCARD / RESEGMENT */
        @NotBlank(message = "处理决定不能为空")
        private String decision;

        /** 重新归段时可选备注；丢弃时可选说明 */
        private String note;
    }
}
