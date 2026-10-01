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
 * 模块十三：场次当前计时数据来源（一场至多一行，{@code uk_source_session} 兜底）。
 *
 * <p>当前来源的切换与接管记录在同一事务完成；该行存在即「本场当前认这台设备」。
 * 圈速事件入账前必须与该行校验设备一致，防止半完成状态下两台设备的数据都进榜。
 */
@Data
@Entity
@Table(name = "timing_source")
public class TimingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 最近一次接管（首台主设备绑定时为 NULL） */
    @Column(name = "takeover_id")
    private Long takeoverId;

    @Column(name = "switched_at", nullable = false)
    private LocalDateTime switchedAt;
}
