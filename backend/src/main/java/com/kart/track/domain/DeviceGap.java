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
 * 模块九-5：设备序号缺口区间。
 *
 * <p>发现序号跳跃时登记 OPEN 缺口；断线恢复批量重放按序补齐后置 FILLED。
 * 同一起点的缺口唯一（{@code uk_gap_open}），重放只扩展/关闭、不重复登记。
 */
@Data
@Entity
@Table(name = "device_gap")
public class DeviceGap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 缺口起点序号（缺失的第一个序号） */
    @Column(name = "from_seq", nullable = false)
    private long fromSeq;

    /** 缺口终点序号（缺失的最后一个序号） */
    @Column(name = "to_seq", nullable = false)
    private long toSeq;

    /** OPEN 缺口未补 / FILLED 已按序补齐 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "filled_at")
    private LocalDateTime filledAt;
}
