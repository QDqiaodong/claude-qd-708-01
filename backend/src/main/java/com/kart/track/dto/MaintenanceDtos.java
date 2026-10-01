package com.kart.track.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块五（维修保养）入参。 */
public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    @Data
    public static class MaintSaveReq {

        @NotBlank(message = "维修单号不能为空")
        private String orderNo;

        @NotNull(message = "车辆不能为空")
        private Long kartId;

        /** OIL / TIRE / BRAKE / CHAIN / SPARK / ENGINE / BODY */
        @NotBlank(message = "维修类型不能为空")
        private String maintType;

        @NotBlank(message = "故障描述不能为空")
        private String faultDesc;

        /** 工时（小时），可空则按 1 小时计 */
        private BigDecimal workHours;

        /** 配件费，可空则记 0 */
        private BigDecimal partsFee;

        private String worker;
    }

    @Data
    public static class WorkerReq {

        private String worker;
    }
}
