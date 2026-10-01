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
 * 模块十三：主备接管记录。
 *
 * <p>边界 = {@code boundaryAt}（接管生效时刻）+ {@code boundarySeq}（旧主负责到的序号）：
 * 边界之前只认主设备，边界之后只认备用设备。旧主晚到的边界后事件、备用提前产生的
 * 边界前事件一律进隔离区。
 *
 * <p>REQUESTED 是值班人员发起、待主管执行的请求；DONE 与 timing_source 的切换在
 * 同一事务提交，任何一步失败整体回滚——不会出现两台设备都是当前来源，或接管已记录
 * 但排行榜仍沿用旧来源的半完成状态。
 */
@Data
@Entity
@Table(name = "timing_takeover")
public class TimingTakeover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 接管编号，如 TO20260924S1-1 */
    @Column(name = "takeover_no", nullable = false, length = 30)
    private String takeoverNo;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "old_device_id", nullable = false)
    private Long oldDeviceId;

    @Column(name = "new_device_id", nullable = false)
    private Long newDeviceId;

    /** 边界序号：旧主负责到该序号 */
    @Column(name = "boundary_seq")
    private Long boundarySeq;

    /** 边界时刻（主管执行时落定） */
    @Column(name = "boundary_at")
    private LocalDateTime boundaryAt;

    /** REQUESTED 待执行 / DONE 已完成 / REJECTED 已驳回 / CANCELED 已撤销 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reason", nullable = false, length = 200)
    private String reason;

    @Column(name = "requested_by", nullable = false, length = 60)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "executed_by", length = 60)
    private String executedBy;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "exec_note", length = 200)
    private String execNote;
}
