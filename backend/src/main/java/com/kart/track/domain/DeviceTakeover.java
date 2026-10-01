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
 * 模块九-6：主备接管记录。
 *
 * <p>只有主管可以指定备用设备接管，并记录接管边界：{@code boundaryAt}（边界时刻）与
 * {@code boundarySeq}（主设备边界序号）。边界之前只认主设备数据，边界之后只认备用设备数据；
 * 旧主设备晚到的边界后事件、备用设备提前产生的边界前事件一律进隔离区。
 *
 * <p>{@code activeKey} 生成列 + {@code uk_active_takeover} 唯一索引兜底：同场次同时只能有一个
 * APPROVED 接管。接管动作在单事务内与场次状态/设备状态一起校验并切换当前来源，
 * 任何关联变化失败整体回滚——不会出现两台设备都被当作当前来源、或接管记录存在但排行榜仍沿用旧来源。
 */
@Data
@Entity
@Table(name = "device_takeover")
public class DeviceTakeover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "primary_device_id", nullable = false)
    private Long primaryDeviceId;

    @Column(name = "backup_device_id", nullable = false)
    private Long backupDeviceId;

    /** 边界序号：主设备该序号（含）之前认主，之后认备 */
    @Column(name = "boundary_seq", nullable = false)
    private long boundarySeq;

    /** 边界时刻：事件实际产生时刻 occurredAt 按它切分来源 */
    @Column(name = "boundary_at", nullable = false)
    private LocalDateTime boundaryAt;

    /** REQUESTED 值班人员已发起 / APPROVED 主管已批准 / REJECTED 主管驳回 / CANCELED 发起人撤回 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "requested_by", nullable = false, length = 60)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    /** 发起时主设备版本（过期页面保护） */
    @Column(name = "request_version")
    private Long requestVersion;

    @Column(name = "decided_by", length = 60)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "reject_reason", length = 200)
    private String rejectReason;
}
