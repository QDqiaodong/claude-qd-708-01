package com.kart.track.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 红旗事件处置台入参。 */
public final class RedFlagDtos {

    private RedFlagDtos() {
    }

    /** 值班人员发起红旗（原因必填，发生时刻由服务端记录） */
    @Data
    public static class RaiseReq {

        @NotBlank(message = "红旗原因不能为空")
        private String reason;
    }

    /** 值班人员提交一辆车的核查结论 */
    @Data
    public static class CheckReq {

        /** CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修 */
        @NotBlank(message = "核查结论不能为空")
        private String conclusion;

        /** 故障/备注，选 REPAIR 时建议填写，会带入维修单故障描述 */
        private String remark;
    }

    /** 主管对红旗期间一条圈速的裁定 */
    @Data
    public static class RulingReq {

        /** CONFIRMED 确认有效 / VOID 作废 */
        @NotBlank(message = "裁定结果不能为空")
        private String ruling;
    }

    /** 处置台默认按场次取事件（与顶栏「本场」上下文一致），也可显式指定 */
    @Data
    public static class SessionRef {

        @NotNull(message = "场次不能为空")
        private Long sessionId;
    }
}
