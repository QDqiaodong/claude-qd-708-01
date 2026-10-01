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
 * 模块十：设备 × 场次序号水位。
 *
 * <p>{@code confirmedSeq} 是断线补偿的检查点——已确认进入正常计圈链的最大圈速序号。
 * 设备重连后批量重放，系统只从 {@code confirmedSeq + 1} 继续；补偿中途再次中断，
 * 已确认的进度落在这里不会回退，已落账事件也由事件总账唯一键去重，绝不从头重复。
 *
 * <p>主备接管时给主设备/备用设备各开一行，{@code boundarySeq} 标明边界序号：
 * 边界之前只认主设备，边界之后只认备用设备。
 */
@Data
@Entity
@Table(name = "timing_device_session")
public class TimingDeviceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 已确认入账的最大圈速序号（补偿检查点） */
    @Column(name = "confirmed_seq", nullable = false)
    private long confirmedSeq;

    /** 接管边界序号：仅该边界之前/之后认本设备 */
    @Column(name = "boundary_seq")
    private Long boundarySeq;

    @Column(name = "bound_at", nullable = false)
    private LocalDateTime boundAt;
}
