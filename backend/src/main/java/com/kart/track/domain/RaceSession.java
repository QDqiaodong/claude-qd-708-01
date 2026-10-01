package com.kart.track.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块三：场次。 */
@Data
@Entity
@Table(name = "race_session")
public class RaceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 场次号，如 S20260924A */
    @Column(name = "session_no", nullable = false, length = 24)
    private String sessionNo;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 赛道配置：HALF 半场 / FULL 全场 / NIGHT 夜间 */
    @Column(name = "track_config", nullable = false, length = 16)
    private String trackConfig;

    /** 分组：A / B / C */
    @Column(name = "group_code", nullable = false, length = 4)
    private String groupCode;

    /** 最大车辆数 */
    @Column(name = "max_karts", nullable = false)
    private Integer maxKarts;

    /** 已报名人数 */
    @Column(name = "signed_count", nullable = false)
    private Integer signedCount;

    /** PENDING 待开赛 / RUNNING 进行中 / FINISHED 已结束 / CANCELED 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
