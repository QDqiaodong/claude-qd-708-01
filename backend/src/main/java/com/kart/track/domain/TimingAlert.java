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
 * 模块十一：计时设备告警。
 *
 * <p>同一设备同一类型同时只允许一条 ACTIVE（{@code dedup_key + status} 唯一索引兜底）。
 * 值班人员可以确认（ACKED，确认即冻结）；设备恢复时系统可置 RESOLVED。
 */
@Data
@Entity
@Table(name = "timing_alert")
public class TimingAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "session_id")
    private Long sessionId;

    /** OFFLINE 离线 / FLAPPING 心跳波动 / GAP 序号缺口 / TAKEOVER_REQ 接管请求 / QUARANTINED 隔离 */
    @Column(name = "type", nullable = false, length = 24)
    private String type;

    @Column(name = "message", nullable = false, length = 200)
    private String message;

    /** ACTIVE 未确认 / ACKED 已确认 / RESOLVED 已自动消除 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "raised_at", nullable = false)
    private LocalDateTime raisedAt;

    @Column(name = "acked_by", length = 60)
    private String ackedBy;

    @Column(name = "acked_at")
    private LocalDateTime ackedAt;

    @Column(name = "dedup_key", nullable = false, length = 120)
    private String dedupKey;
}
