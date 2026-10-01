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
 * 模块九-4：设备侧圈速事件。
 *
 * <p>每条事件必须携带：{@code eventUid}（设备侧唯一标识）、{@code seqNo}（该设备本场递增序号）、
 * {@code occurredAt}（圈速实际产生时刻，设备时钟）。唯一索引 {@code uk_event_device_uid} 从数据库层兜底：
 * 同一事件无论重放多少次只落一行、只能形成一条圈速记录。
 *
 * <p>状态机：
 * <ul>
 *   <li>PENDING：缺口之后的数据先保留待对账，不进实时排行榜、不刷车手历史最好成绩；</li>
 *   <li>CONFIRMED：序号连续且来源有效，已形成 1:1 的 lap_record（红旗窗口下同时挂红旗待裁定）；</li>
 *   <li>QUARANTINED：主备边界违规 / 序号倒退，进隔离区，由主管决定丢弃或重新归段；</li>
 *   <li>VOID：主管已丢弃（留底永不入榜）。</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "lap_event")
public class LapEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    /** 设备侧事件唯一标识（重放幂等键） */
    @Column(name = "event_uid", nullable = false, length = 48)
    private String eventUid;

    /** 设备侧本场递增序号 */
    @Column(name = "seq_no", nullable = false)
    private long seqNo;

    /** 圈速实际产生时刻（设备时钟，主备边界按它切分） */
    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "kart_id", nullable = false)
    private Long kartId;

    @Column(name = "lap_time_ms", nullable = false)
    private int lapTimeMs;

    /** VALID / OUT / CRASH（设备原值，隔离区不改判） */
    @Column(name = "valid", nullable = false, length = 16)
    private String valid;

    /** CONFIRMED / PENDING / QUARANTINED / VOID */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** LATE_PRIMARY 旧设备晚到的边界后事件 / EARLY_BACKUP 备用提前产生的边界前事件 / SEQ_REGRESSION 序号倒退 */
    @Column(name = "quarantine_reason", length = 60)
    private String quarantineReason;

    /** 确认后形成的圈速记录（1:1） */
    @Column(name = "lap_id")
    private Long lapId;

    /** 确认时是否处于红旗窗口：落 lap_record 留底但挂红旗待裁定，不能绕过红旗规则 */
    @Column(name = "red_flag", nullable = false)
    private boolean redFlag;

    @Column(name = "red_flag_event_id")
    private Long redFlagEventId;

    /** 重放次数：首次上报 0，每重放一次自增 */
    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "ruled_by", length = 60)
    private String ruledBy;

    @Column(name = "ruled_at")
    private LocalDateTime ruledAt;

    /** 服务端落库时刻 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
