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
 * 模块七：红旗车辆核查清单中的一行（发起红旗时按当时仍在赛的车辆逐车快照生成）。
 *
 * <p>结论一旦提交即冻结：某台车选「退出比赛」或「需要维修」，不会释放或改写其它车辆的结论。
 */
@Data
@Entity
@Table(name = "red_flag_check")
public class RedFlagCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "kart_id", nullable = false)
    private Long kartId;

    /** 发起时该车的在赛车手（快照，允许为空） */
    @Column(name = "driver_id")
    private Long driverId;

    /** NULL 待核查 / CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修 */
    @Column(name = "conclusion", length = 16)
    private String conclusion;

    @Column(name = "remark", length = 200)
    private String remark;

    @Column(name = "checked_by", length = 60)
    private String checkedBy;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    /** 选「需要维修」时生成的待修维修单号（可追踪） */
    @Column(name = "maint_order_no", length = 24)
    private String maintOrderNo;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
