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
 * 模块十一：计时设备心跳流水。
 *
 * <p>心跳序号 {@code hbSeq} 设备侧自增；{@code gapMs} 是与上一条心跳的间隔。
 * 在线/波动/离线状态不入库，在读取时按最后心跳年龄与最近间隔推导。
 */
@Data
@Entity
@Table(name = "timing_heartbeat")
public class TimingHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 上报时设备关联的场次（快照） */
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "hb_seq", nullable = false)
    private long hbSeq;

    /** 心跳实际产生时刻（设备侧） */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 服务端收到时刻 */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** 与上一心跳的间隔（毫秒），首条为 0 */
    @Column(name = "gap_ms", nullable = false)
    private long gapMs;
}
