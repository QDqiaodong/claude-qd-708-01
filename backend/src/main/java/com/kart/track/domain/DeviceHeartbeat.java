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
 * 模块九-2：设备心跳流水。
 *
 * <p>每次心跳一行，受理与否都有明确结果：未关联场次（NOT_BOUND）/ 场次已结束（SESSION_ENDED）/
 * 已隔离（QUARANTINED）的心跳 accepted=0 且不更新设备在线状态，但留痕供值班人员排查。
 */
@Data
@Entity
@Table(name = "device_heartbeat")
public class DeviceHeartbeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 心跳时设备关联的场次（快照，未关联为 NULL） */
    @Column(name = "session_id")
    private Long sessionId;

    /** 设备侧单调递增序号 */
    @Column(name = "seq_no", nullable = false)
    private long seqNo;

    /** 服务端收到时刻 */
    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    /** 是否受理（不受理则不更新最后心跳与在线状态） */
    @Column(name = "accepted", nullable = false)
    private boolean accepted;

    /** NOT_BOUND / SESSION_ENDED / QUARANTINED */
    @Column(name = "reject_reason", length = 60)
    private String rejectReason;
}
