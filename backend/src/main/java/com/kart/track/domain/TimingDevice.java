package com.kart.track.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 模块九：计时设备。
 *
 * <p>{@code deviceCode} 是设备侧烧录的稳定身份：断线重放、主备接管都按它识别，
 * 不按自增 id、也不随场次变化。每台设备明确属于一条物理赛道（{@code trackCode}），
 * 并可关联到「当前场次」{@code sessionId}（NULL = 未关联本场）。
 *
 * <p>{@code quarantined} 隔离标记由主管设置/解除。{@code version} 是 JPA 乐观锁：
 * 过期页面、重复点击拿到旧版本再写会被拒绝，不能覆盖设备的新状态。
 *
 * <p>在线/波动/离线不持久化在设备上，读取时按最后心跳年龄 + 最近间隔推导；
 * 隔离状态优先于心跳状态直接显示「已隔离」。
 */
@Data
@Entity
@Table(name = "timing_device")
public class TimingDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备侧稳定身份码（烧录，全局唯一） */
    @Column(name = "device_code", nullable = false, length = 40)
    private String deviceCode;

    @Column(name = "name", nullable = false, length = 40)
    private String name;

    /** 所属物理赛道：HALF 半场 / FULL 全场 / NIGHT 夜间 */
    @Column(name = "track_code", nullable = false, length = 16)
    private String trackCode;

    /** PRIMARY 主设备 / STANDBY 备用设备 */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** 当前关联场次（NULL = 未关联本场） */
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    /** 最近一次心跳序号 */
    @Column(name = "last_hb_seq")
    private Long lastHbSeq;

    /** 最近两次心跳间隔（毫秒） */
    @Column(name = "hb_gap_ms")
    private Long hbGapMs;

    @Column(name = "quarantined", nullable = false)
    private boolean quarantined;

    @Column(name = "quarantine_reason", length = 200)
    private String quarantineReason;

    @Column(name = "quarantined_by", length = 60)
    private String quarantinedBy;

    @Column(name = "quarantined_at")
    private LocalDateTime quarantinedAt;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 乐观锁版本：过期页面/重复操作无法覆盖设备新状态 */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
