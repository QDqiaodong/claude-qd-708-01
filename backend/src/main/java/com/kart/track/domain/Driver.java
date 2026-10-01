package com.kart.track.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 模块二：车手。 */
@Data
@Entity
@Table(name = "driver")
public class Driver {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会员号，如 M2026001 */
    @Column(name = "member_no", nullable = false, length = 20)
    private String memberNo;

    @Column(name = "name", nullable = false, length = 30)
    private String name;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /** 年龄分组：KID 儿童 / TEEN 青少年 / ADULT 成人 */
    @Column(name = "age_group", nullable = false, length = 16)
    private String ageGroup;

    /** 驾照等级：NONE 无证 / BASIC 初级 / MID 中级 / ADVANCED 高级 */
    @Column(name = "license_level", nullable = false, length = 16)
    private String licenseLevel;

    /** 累计场次 */
    @Column(name = "total_sessions", nullable = false)
    private Integer totalSessions;

    /** 历史最快圈速（毫秒） */
    @Column(name = "best_lap_ms", nullable = false)
    private Integer bestLapMs;

    /** 总消费金额 */
    @Column(name = "total_spend", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalSpend;

    /** 注册日期 */
    @Column(name = "register_date", nullable = false)
    private LocalDate registerDate;

    /** 当前报名/在赛的场次 id（报名本场时写入） */
    @Column(name = "session_id")
    private Long sessionId;
}
