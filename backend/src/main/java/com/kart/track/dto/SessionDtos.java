package com.kart.track.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块三（场次）入参。 */
public final class SessionDtos {

    private SessionDtos() {
    }

    @Data
    public static class SessionSaveReq {

        @NotBlank(message = "场次号不能为空")
        private String sessionNo;

        /** HALF / FULL / NIGHT */
        @NotBlank(message = "赛道配置不能为空")
        private String trackConfig;

        /** A / B / C */
        @NotBlank(message = "分组不能为空")
        private String groupCode;

        @Min(value = 1, message = "最大车辆数至少为 1")
        private Integer maxKarts;
    }
}
