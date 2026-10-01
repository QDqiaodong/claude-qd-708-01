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
 * 模块九-1：计时设备（稳定身份）。
 *
 * <p>{@code deviceNo} 是设备的稳定身份（不是 IP，断线重连不变）；{@code trackConfig} 是安装归属赛道，
 * 只有赛道匹配的场次才能关联；{@code sessionId} 是当前关联场次，NULL = 未关联。
 *
 * <p>{@code quarantined} 是主管隔离标记：隔离期间一切上报明确拒绝、不混入本场成绩，由主管解除。
 * {@code version} 是乐观锁版本号：任何人工状态变更自增，前端每次操作必须回传当前版本，
 * 过期页面 / 重复点击拿到旧版本直接拒绝，不能覆盖新的设备状态。
 */
@Data
@Entity
@Table(name = "timing_device")
public class TimingDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备稳定编号，如 TD-M1 */
    @Column(name = "device_no", nullable = false, length = 24)
    private String deviceNo;

    /** 设备名称/安装位置 */
    @Column(name = "name", nullable = false, length = 40)
    private String name;

    /** PRIMARY 主设备 / BACKUP 备用设备（设备本身固有属性，不是每场可变） */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    /** 归属赛道：HALF / FULL / NIGHT */
    @Column(name = "track_config", nullable = false, length = 16)
    private String trackConfig;

    /** 当前关联赛次；NULL = 未关联（自由设备，上报一律拒绝） */
    @Column(name = "session_id")
    private Long sessionId;

    /** 是否被主管隔离 */
    @Column(name = "quarantined", nullable = false)
    private boolean quarantined;

    @Column(name = "quarantine_reason", length = 200)
    private String quarantineReason;

    /** 最近是否出现心跳抖动（连续两次心跳序号不增），收到一条正常心跳后清除 */
    @Column(name = "heartbeat_jitter", nullable = false)
    private boolean heartbeatJitter;

    @Column(name = "last_heartbeat_at")
    private LocalDateTime lastHeartbeatAt;

    @Column(name = "last_heartbeat_seq")
    private Long lastHeartbeatSeq;

    /** 乐观锁版本号：人工状态变更（关联/接管/隔离/解除）自增 */
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
