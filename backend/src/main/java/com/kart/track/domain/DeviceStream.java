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
 * 模块九-3：设备在某场次的顺序进度（断线补偿的断点保存在这里）。
 *
 * <p>{@code confirmedSeq} 是已连续确认到的序号：序号 1..confirmedSeq 的事件全部已落账（幂等去重）。
 * 发现缺口后后续事件只挂为待对账，confirmedSeq 不前进；设备批量重放时按序补齐缺口，
 * 每次补偿都从该进度继续——补偿过程中再次中断也只重做未确认部分，绝不从头重复处理。
 *
 * <p>主备接管生效时，原主设备流写 {@code boundaryAfterSeq}（边界序号）：即使补到缺口，
 * 边界之后的主设备事件仍不得确认；备用设备流从序号 1 开始独立计数。
 */
@Data
@Entity
@Table(name = "device_stream")
public class DeviceStream {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 绑定时设备角色快照 PRIMARY / BACKUP */
    @Column(name = "device_role", nullable = false, length = 16)
    private String deviceRole;

    /** 已连续确认序号（补偿断点） */
    @Column(name = "confirmed_seq", nullable = false)
    private long confirmedSeq;

    /** 已见最大序号（断线批量重放时可能远大于 confirmedSeq） */
    @Column(name = "observed_seq", nullable = false)
    private long observedSeq;

    /** 接管边界序号：仅边界（含）之前的数据可被确认（原主设备流） */
    @Column(name = "boundary_after_seq")
    private Long boundaryAfterSeq;

    /** 该流已确认圈速条数 */
    @Column(name = "lap_count", nullable = false)
    private int lapCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
