package com.kart.track.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 计时设备韧性台入参。
 *
 * <p>心跳与圈速上报是设备→服务端链路，不要求操作人身份；
 * 关联/接管/隔离/告警确认等人工操作仍走 X-Operator 请求头并在业务层收口权限。
 */
public final class TimingDtos {

    private TimingDtos() {
    }

    /** 设备注册（稳定身份入库） */
    @Data
    public static class DeviceRegisterReq {

        @NotBlank(message = "设备编号不能为空")
        private String deviceNo;

        @NotBlank(message = "设备名称不能为空")
        private String name;

        /** PRIMARY 主设备 / BACKUP 备用设备 */
        @NotBlank(message = "设备角色不能为空")
        private String role;

        @NotBlank(message = "归属赛道不能为空")
        private String trackConfig;
    }

    /** 设备心跳（设备侧序号必填，服务端记录收到时刻） */
    @Data
    public static class HeartbeatReq {

        @NotNull(message = "心跳序号不能为空")
        private Long seqNo;
    }

    /** 圈速事件：设备侧唯一标识 + 递增序号 + 实际产生时刻，三者缺一不可 */
    @Data
    public static class LapEventReq {

        /** 设备侧事件唯一标识（同一标识重放任意次只形成一条记录） */
        @NotBlank(message = "事件唯一标识不能为空")
        private String eventUid;

        @NotNull(message = "设备侧递增序号不能为空")
        private Long seqNo;

        /** 圈速实际产生时刻（设备时钟），主备接管边界按它切分 */
        @NotNull(message = "事件产生时刻不能为空")
        private LocalDateTime occurredAt;

        @NotNull(message = "车手不能为空")
        private Long driverId;

        @NotNull(message = "车辆不能为空")
        private Long kartId;

        @NotNull(message = "圈速不能为空")
        private Integer lapTimeMs;

        /** VALID / OUT / CRASH，缺省 VALID */
        private String valid;
    }

    /** 值班人员把设备关联到场次 / 解除关联（带设备版本号防过期页面） */
    @Data
    public static class BindReq {

        /** 关联时必填；解除关联传 null */
        private Long sessionId;

        @NotNull(message = "设备版本号不能为空")
        private Long version;
    }

    /** 值班人员发起主备接管请求 */
    @Data
    public static class TakeoverRequestReq {

        @NotNull(message = "备用设备不能为空")
        private Long backupDeviceId;

        @NotNull(message = "页面已过期，请刷新后重试（缺少主设备版本号）")
        private Long primaryVersion;

        /** 可选：故障说明，带给主管审批 */
        private String reason;
    }

    /** 主管驳回接管请求 */
    @Data
    public static class TakeoverRejectReq {

        @NotBlank(message = "驳回原因不能为空")
        private String reason;
    }

    /** 主管隔离设备 */
    @Data
    public static class QuarantineReq {

        @NotBlank(message = "隔离原因不能为空")
        private String reason;

        @NotNull(message = "页面已过期，请刷新后重试（缺少设备版本号）")
        private Long version;
    }

    /** 主管解除隔离 / 人工状态变更通用版本号 */
    @Data
    public static class VersionReq {

        @NotNull(message = "页面已过期，请刷新后重试（缺少设备版本号）")
        private Long version;
    }

    /** 主管处理隔离区事件：丢弃 / 重新归段 */
    @Data
    public static class QuarantineRuleReq {

        /** DISCARD 丢弃 / RESEGMENT 重新归段（按接管边界重新判定） */
        @NotBlank(message = "处理方式不能为空")
        private String action;
    }
}
