package com.kart.track.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 模块八：红旗期间圈速隔离裁定（与 {@link LapRecord} 1:1）。
 *
 * <p>红旗期间计时设备上报的圈速不丢弃、也不进实时排行榜：
 * lap_record 照常留底，这里挂一条 {@code PENDING}，标明产生于哪次红旗事件；
 * 主管恢复比赛前逐条 {@code CONFIRMED}（确认有效）或 {@code VOID}（作废）。
 */
@Data
@Entity
@Table(name = "red_flag_lap")
public class RedFlagLap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 圈速记录 id（lap_record.id），1:1 */
    @Column(name = "lap_id", nullable = false)
    private Long lapId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** PENDING 待裁定 / CONFIRMED 已确认有效 / VOID 已作废 */
    @Column(name = "ruling", nullable = false, length = 16)
    private String ruling;

    @Column(name = "ruled_by", length = 60)
    private String ruledBy;

    @Column(name = "ruled_at")
    private LocalDateTime ruledAt;
}
