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
 * 模块十二：计时设备圈速事件总账。
 *
 * <p>设备每上报一条圈速这里就有一行，是断线补偿与去重的事实账本：
 * <ul>
 *   <li>{@code eventUid} 设备侧唯一标识 + {@code uk_tle_device_uid}：同一事件重放多少次
 *       都只返回第一次的处理结果，只形成至多一条 {@code lap_record}（{@code lapId} 1:1）；</li>
 *   <li>{@code seq} 本场内设备侧递增序号 + {@code uk_tle_device_session_seq}：
 *       序号连续且来源有效才 ACCEPTED 进正常计圈链；发现缺口后，其后事件先 PENDING 待对账；</li>
 *   <li>{@code stage} = QUARANTINED 的是隔离区数据（如越过接管边界的事件），
 *       由主管 DISCARD 丢弃或 RESEGMENT 重新归段。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "timing_lap_event")
public class TimingLapEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 设备侧事件唯一标识（重放幂等键，设备内全局唯一） */
    @Column(name = "event_uid", nullable = false, length = 60)
    private String eventUid;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "kart_id", nullable = false)
    private Long kartId;

    /** 本场内设备侧递增圈速序号 */
    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "lap_time_ms", nullable = false)
    private int lapTimeMs;

    /** VALID / OUT / CRASH（设备判定） */
    @Column(name = "valid", nullable = false, length = 16)
    private String valid;

    /** 圈速实际产生时刻（设备侧） */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    /** 服务端收到时刻（重放时可能远晚于产生时刻） */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** 上报时设备角色 PRIMARY / STANDBY */
    @Column(name = "source_role", nullable = false, length = 16)
    private String sourceRole;

    /** ACCEPTED / PENDING / QUARANTINED / DISCARDED */
    @Column(name = "stage", nullable = false, length = 16)
    private String stage;

    /** 入账后回链 lap_record.id（1:1，未入账为 NULL） */
    @Column(name = "lap_id")
    private Long lapId;

    /** 待对账期间归属的序号缺口 */
    @Column(name = "gap_id")
    private Long gapId;

    /** OLD_AFTER_BOUNDARY 旧主边界后 / BACKUP_BEFORE_BOUNDARY 备用边界前 / BAD_LAP_TIME 圈速越界 */
    @Column(name = "quarantine_reason", length = 32)
    private String quarantineReason;

    @Column(name = "quarantine_note", length = 200)
    private String quarantineNote;

    @Column(name = "decided_by", length = 60)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
}
