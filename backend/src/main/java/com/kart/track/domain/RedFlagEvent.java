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
 * 模块六：红旗事件。
 *
 * <p>场次状态：进行中 →（发起红旗）→ 红旗处理中 →（恢复）→ 进行中 /（强制终止）→ 已结束。
 *
 * <p>「同一场次同时只允许一个生效中」有两层兜底：
 * <ol>
 *   <li>应用层：发起前先查 ACTIVE，命中则直接返回同一事件（并发请求再接近也只产生一套清单）；</li>
 *   <li>数据库层：{@code active_key} 生成列 + {@code uk_active_session} 唯一索引，
 *       只有 ACTIVE 行在该列存场次 id，已恢复/已终止行为 NULL（MySQL 唯一索引允许多个 NULL）。</li>
 * </ol>
 */
@Data
@Entity
@Table(name = "red_flag_event")
public class RedFlagEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事件编号，如 RF20260930001 */
    @Column(name = "event_no", nullable = false, length = 24)
    private String eventNo;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 红旗原因（发起时必填） */
    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    /** 红旗发生时刻（服务端落库时间，页面上的发生时刻以此为准） */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** ACTIVE 生效中 / RESUMED 已恢复 / TERMINATED 已强制终止 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "raised_by", nullable = false, length = 60)
    private String raisedBy;

    @Column(name = "resumed_by", length = 60)
    private String resumedBy;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(name = "terminated_by", length = 60)
    private String terminatedBy;

    @Column(name = "terminated_at")
    private LocalDateTime terminatedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
