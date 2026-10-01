package com.kart.track.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块五：维修保养。 */
@Data
@Entity
@Table(name = "maintenance")
public class Maintenance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 维修单号，如 MT-20260901 */
    @Column(name = "order_no", nullable = false, length = 24)
    private String orderNo;

    @Column(name = "kart_id", nullable = false)
    private Long kartId;

    /** OIL 换机油 / TIRE 换轮胎 / BRAKE 刹车片 / CHAIN 链条 / SPARK 火花塞 / ENGINE 发动机大修 / BODY 车身修复 */
    @Column(name = "maint_type", nullable = false, length = 16)
    private String maintType;

    @Column(name = "fault_desc", length = 200)
    private String faultDesc;

    /** 工时（小时） */
    @Column(name = "work_hours", nullable = false, precision = 6, scale = 2)
    private BigDecimal workHours;

    /** 配件费 */
    @Column(name = "parts_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal partsFee;

    /** 总费用 = 工时 × 工时费单价 + 配件费 */
    @Column(name = "total_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalFee;

    @Column(name = "worker", length = 30)
    private String worker;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** WAITING 待修 / REPAIRING 维修中 / DONE 已完工 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;
}
