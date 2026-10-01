package com.kart.track.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** 模块二（车手）入参。 */
public final class DriverDtos {

    private DriverDtos() {
    }

    @Data
    public static class DriverSaveReq {

        @NotBlank(message = "会员号不能为空")
        private String memberNo;

        @NotBlank(message = "姓名不能为空")
        private String name;

        @Pattern(regexp = "1\\d{10}", message = "手机号应为 11 位且以 1 开头")
        private String phone;

        /** KID / TEEN / ADULT */
        @NotBlank(message = "年龄分组不能为空")
        private String ageGroup;

        /** NONE / BASIC / MID / ADVANCED */
        @NotBlank(message = "驾照等级不能为空")
        private String licenseLevel;

        /** 可空：新会员默认 0 */
        private BigDecimal totalSpend;

        /** 可空：默认今天 */
        private LocalDate registerDate;
    }

    @Data
    public static class EnrollReq {

        private Long sessionId;
    }
}
