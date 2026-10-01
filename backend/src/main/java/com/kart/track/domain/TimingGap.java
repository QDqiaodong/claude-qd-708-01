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
 * 模块十二：圈速序号缺口。
 *
 * <p>收到 {@code seq > confirmed_seq + 1} 时开窗 OPEN，记录缺失区间
 * {@code missingFrom ~ missingTo}；设备重放按序补齐后关窗 FILLED。
 * 缺口挂住其后的所有 PENDING 事件（{@code pendingCount}），关窗时按序放行。
 */
@Data
@Entity
@Table(name = "timing_gap")
public class TimingGap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "missing_from", nullable = false)
    private long missingFrom;

    /** 缺失结束序号（更大缺口出现时更新） */
    @Column(name = "missing_to", nullable = false)
    private long missingTo;

    /** OPEN 缺口未补齐 / FILLED 已按序补齐 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;

    /** 挂在该缺口上的待对账事件数 */
    @Column(name = "pending_count", nullable = false)
    private int pendingCount;
}
