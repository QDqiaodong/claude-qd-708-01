package com.kart.track.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块一：卡丁车。 */
@Data
@Entity
@Table(name = "kart")
public class Kart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 车号，如 K-01 */
    @Column(name = "kart_no", nullable = false, length = 16)
    private String kartNo;

    /** 车型：FUN 娱乐车 / RACE 竞技车 / KID 儿童车 / DOUBLE 双人车 */
    @Column(name = "model", nullable = false, length = 16)
    private String model;

    /** 发动机排量（cc） */
    @Column(name = "engine_cc", nullable = false)
    private Integer engineCc;

    /** 最大功率（kW） */
    @Column(name = "max_power", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxPower;

    /** AVAILABLE 可用 / ON_TRACK 在赛 / MAINTENANCE 维修 / DISABLED 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 累计里程（km） */
    @Column(name = "total_km", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalKm;

    /** 累计运行小时 */
    @Column(name = "total_hours", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalHours;

    /** 上次保养日期 */
    @Column(name = "last_service_date")
    private LocalDate lastServiceDate;

    /** 保养间隔（小时） */
    @Column(name = "service_interval_h", nullable = false)
    private Integer serviceIntervalH;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
