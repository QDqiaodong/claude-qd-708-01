package com.kart.track.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块四（圈速记录）入参——写入走实体上的 {@code @SQLInsert} 手写 SQL。 */
public final class LapRecordDtos {

    private LapRecordDtos() {
    }

    @Data
    public static class LapSaveReq {

        @NotNull(message = "场次不能为空")
        private Long sessionId;

        @NotNull(message = "车手不能为空")
        private Long driverId;

        @NotNull(message = "车辆不能为空")
        private Long kartId;

        @NotNull(message = "圈速不能为空")
        private Integer lapTimeMs;

        /** 可空：默认 VALID（有效），也可直接录 OUT / CRASH */
        private String valid;
    }

    @Data
    public static class LapValidReq {

        @NotNull(message = "判定结果不能为空")
        private String valid;
    }
}
