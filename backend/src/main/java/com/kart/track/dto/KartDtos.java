package com.kart.track.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块一（卡丁车）入参。 */
public final class KartDtos {

    private KartDtos() {
    }

    @Data
    public static class KartSaveReq {

        @NotBlank(message = "车号不能为空")
        private String kartNo;

        /** FUN / RACE / KID / DOUBLE */
        @NotBlank(message = "车型不能为空")
        private String model;

        @Min(value = 50, message = "发动机排量不得小于 50cc")
        private Integer engineCc;

        private BigDecimal maxPower;

        /** 可空：默认 AVAILABLE */
        private String status;

        /** 可空：新车默认 0 */
        private BigDecimal totalKm;

        /** 可空：新车默认 0 */
        private BigDecimal totalHours;

        private LocalDate lastServiceDate;

        /** 可空：默认 50 小时 */
        private Integer serviceIntervalH;
    }

    @Data
    public static class StatusReq {

        @NotBlank(message = "目标状态不能为空")
        private String status;
    }

    @Data
    public static class DispatchReq {

        private Long sessionId;
    }
}
