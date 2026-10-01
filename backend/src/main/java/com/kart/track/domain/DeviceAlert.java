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
 * 模块九-7：韧性台告警。
 *
 * <p>类型：GAP 序号缺口 / JUMP 序号倒退 / FLAPPING 心跳波动 / OFFLINE 心跳超时离线 /
 * TAKEOVER_REQ 接管请求待批 / QUARANTINED 设备被隔离。
 * 值班人员可确认（ACK 即冻结）；{@code dedupKey} 唯一索引保证同一异常不产生新的 ACTIVE 告警。
 */
@Data
@Entity
@Table(name = "device_alert")
public class DeviceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** GAP / JUMP / FLAPPING / OFFLINE / TAKEOVER_REQ / QUARANTINED */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "content", nullable = false, length = 200)
    private String content;

    /** ACTIVE 待确认 / ACK 已确认 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "ack_by", length = 60)
    private String ackBy;

    @Column(name = "ack_at")
    private LocalDateTime ackAt;

    /** 活跃告警去重键：同键只保留一条 ACTIVE（人工类告警为 NULL，允许多条） */
    @Column(name = "dedup_key", length = 120)
    private String dedupKey;
}
