package com.kart.track.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：这些对象只在 service 里构造、直接序列化给前端，不需要 setter。
 */
public final class Views {

    private Views() {
    }

    /** 模块一：卡丁车 */
    public record KartView(
            Long id,
            String kartNo,
            String model,
            String modelName,
            Integer engineCc,
            BigDecimal maxPower,
            String status,
            String statusName,
            BigDecimal totalKm,
            BigDecimal totalHours,
            LocalDate lastServiceDate,
            Integer serviceIntervalH,
            boolean serviceDue,
            String serviceDueText,
            String sessionNo,
            LocalDateTime updatedAt) {
    }

    /** 模块二：车手 */
    public record DriverView(
            Long id,
            String memberNo,
            String name,
            String phone,
            String ageGroup,
            String ageGroupName,
            String licenseLevel,
            String licenseName,
            Integer totalSessions,
            Integer bestLapMs,
            String bestLapText,
            BigDecimal totalSpend,
            LocalDate registerDate,
            Long sessionId,
            String sessionNo) {
    }

    /** 模块三：场次 */
    public record SessionView(
            Long id,
            String sessionNo,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String trackConfig,
            String trackConfigName,
            String groupCode,
            Integer maxKarts,
            Integer signedCount,
            String status,
            String statusName,
            long elapsedSec,
            long lapCount,
            String fastestText,
            boolean redFlagActive,
            Long activeRedFlagId,
            long redFlagCount) {
    }

    /** 模块四：圈速记录（写入走 @SQLInsert 手写 SQL；红旗期间记录带隔离裁定信息） */
    public record LapRecordView(
            Long id,
            Long sessionId,
            String sessionNo,
            Long driverId,
            String driverName,
            Long kartId,
            String kartNo,
            Integer lapIndex,
            Integer lapTimeMs,
            String lapTimeText,
            boolean fastest,
            String valid,
            String validName,
            LocalDateTime createdAt,
            boolean redFlagLap,
            Long redFlagEventId,
            String ruling,
            String rulingName) {
    }

    /** 模块五：维修保养 */
    public record MaintenanceView(
            Long id,
            String orderNo,
            Long kartId,
            String kartNo,
            String maintType,
            String maintTypeName,
            String faultDesc,
            BigDecimal workHours,
            BigDecimal partsFee,
            BigDecimal totalFee,
            String worker,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status,
            String statusName,
            long durationMin) {
    }

    /** 实时圈速排行榜一行 */
    public record LeaderRow(
            int rank,
            String driverName,
            String kartNo,
            Integer bestLapMs,
            String lapText,
            String deltaText,
            int lapCount,
            boolean first) {
    }

    /** 赛道俯视图上的一个小车点位 */
    public record TrackDot(
            Long kartId,
            String kartNo,
            String status,
            String statusName,
            String driverName,
            double offset,
            double speed) {
    }

    /** 首页「赛道实况」一屏数据 */
    public record LiveView(
            SessionView session,
            List<LeaderRow> leaderboard,
            List<TrackDot> dots,
            long lapCount,
            String fastestText,
            String sessionSummary) {
    }

    /** 维修保养页的费用统计 */
    public record MaintSummary(
            long total,
            long waiting,
            long repairing,
            long done,
            BigDecimal partsFeeTotal,
            BigDecimal laborFeeTotal,
            BigDecimal totalFee) {
    }

        /** 顶栏计数器 */
    public record Overview(
            long kartTotal,
            long kartAvailable,
            long kartOnTrack,
            long kartMaintenance,
            long kartDisabled,
            long driverTotal,
            long sessionPending,
            long sessionRunning,
            long lapTotal,
            long maintOpen,
            long maintDone,
            BigDecimal maintFee,
            long redFlagActive,
            long redFlagPendingLaps,
            long timingDeviceTotal,
            long timingAlertActive,
            long timingIsolated) {
    }

    /** 红旗车辆核查清单一行（含车辆/车手快照名与最新状态） */
    public record RedFlagCheckView(
            Long id,
            Long eventId,
            Long kartId,
            String kartNo,
            String kartModelName,
            String kartStatus,
            String kartStatusName,
            Long driverId,
            String driverName,
            String conclusion,
            String conclusionName,
            boolean checked,
            String remark,
            String checkedBy,
            LocalDateTime checkedAt,
            String maintOrderNo) {
    }

    /** 红旗期间待裁定圈速一行（圈速 + 隔离裁定信息） */
    public record RedFlagLapView(
            Long id,
            Long lapId,
            Long eventId,
            Long sessionId,
            String sessionNo,
            Long driverId,
            String driverName,
            Long kartId,
            String kartNo,
            Integer lapIndex,
            Integer lapTimeMs,
            String lapTimeText,
            boolean fastest,
            String valid,
            String validName,
            LocalDateTime lapCreatedAt,
            String ruling,
            String rulingName,
            String ruledBy,
            LocalDateTime ruledAt) {
    }

    /** 一次红旗事件（处置台头部 + 场次详情历史回看） */
    public record RedFlagEventView(
            Long id,
            String eventNo,
            Long sessionId,
            String sessionNo,
            String reason,
            String status,
            String statusName,
            boolean active,
            String raisedBy,
            LocalDateTime occurredAt,
            String resumedBy,
            LocalDateTime resumedAt,
            String terminatedBy,
            LocalDateTime terminatedAt,
            long durationSec,
            String durationText,
            long totalChecks,
            long checkedCount,
            long continueCount,
            long retireCount,
            long repairCount,
            long totalLaps,
            long pendingLapCount,
            long confirmedLapCount,
            long voidLapCount,
            boolean allChecked,
            boolean allRuled,
            boolean resumable,
            String finalAction,
            String finalActionBy,
            LocalDateTime finalActionAt,
            List<RedFlagCheckView> checks,
            List<RedFlagLapView> laps) {
    }

    /** 处置台一屏数据：当前生效事件（可空）+ 本场历史红旗 */
    public record RedFlagConsoleView(
            Long sessionId,
            String sessionNo,
            String sessionStatus,
            String sessionStatusName,
            boolean redFlagActive,
            RedFlagEventView activeEvent,
            List<RedFlagEventView> history) {
    }

    // ============================================================ 模块九：计时设备韧性台

    /** 单条设备侧圈速事件（已确认/待对账/隔离区共用） */
    public record LapEventView(
            Long id,
            Long sessionId,
            Long deviceId,
            String deviceNo,
            String eventUid,
            long seqNo,
            LocalDateTime occurredAt,
            Long driverId,
            String driverName,
            Long kartId,
            String kartNo,
            Integer lapTimeMs,
            String lapTimeText,
            String valid,
            String validName,
            String status,
            String statusName,
            String quarantineReason,
            String quarantineReasonName,
            Long lapId,
            boolean redFlag,
            Long redFlagEventId,
            int replayCount,
            String ruledBy,
            LocalDateTime ruledAt,
            LocalDateTime createdAt) {
    }

    /** 序号缺口区间 */
    public record DeviceGapView(
            Long id,
            Long deviceId,
            String deviceNo,
            long fromSeq,
            long toSeq,
            String status,
            String statusName,
            LocalDateTime openedAt,
            LocalDateTime filledAt) {
    }

    /** 主备接管记录 */
    public record TakeoverView(
            Long id,
            Long sessionId,
            Long primaryDeviceId,
            String primaryDeviceNo,
            Long backupDeviceId,
            String backupDeviceNo,
            long boundarySeq,
            LocalDateTime boundaryAt,
            String status,
            String statusName,
            String requestedBy,
            LocalDateTime requestedAt,
            String reason,
            Long requestVersion,
            String decidedBy,
            LocalDateTime decidedAt,
            String rejectReason,
            boolean effective) {
    }

    /** 韧性台告警 */
    public record DeviceAlertView(
            Long id,
            Long sessionId,
            Long deviceId,
            String deviceNo,
            String type,
            String typeName,
            String content,
            String status,
            LocalDateTime createdAt,
            String ackBy,
            LocalDateTime ackAt) {
    }

    /** 设备时间线条目（告警/接管/缺口/心跳合并按时间倒序） */
    public record DeviceTimelineView(
            LocalDateTime at,
            String kind,
            String kindName,
            Long deviceId,
            String deviceNo,
            String content,
            String tone) {
    }

    /** 韧性台一台设备卡片所需的全部状态 */
    public record TimingDeviceView(
            Long id,
            String deviceNo,
            String name,
            String role,
            String roleName,
            String trackConfig,
            String trackConfigName,
            Long sessionId,
            String sessionNo,
            boolean quarantined,
            String quarantineReason,
            boolean heartbeatJitter,
            String heartbeatStatus,
            String heartbeatStatusName,
            LocalDateTime lastHeartbeatAt,
            Long lastHeartbeatSeq,
            long heartbeatAgeSec,
            String heartbeatAgeText,
            boolean currentSource,
            long version,
            long confirmedSeq,
            long observedSeq,
            Long boundaryAfterSeq,
            int lapCount,
            long openGapCount,
            long pendingEventCount,
            long quarantineEventCount,
            List<DeviceGapView> gaps,
            List<LapEventView> pendingEvents) {
    }

    /** 韧性台汇总计数 */
    public record TimingSummary(
            long deviceTotal,
            long onlineCount,
            long flappingCount,
            long offlineCount,
            long isolatedCount,
            long unboundCount,
            long activeAlertCount,
            long pendingEventCount,
            long openGapCount,
            long quarantineEventCount,
            long takeoverRequestCount) {
    }

    /** 心跳受理结果（不受理时明确原因） */
    public record HeartbeatResult(
            boolean accepted,
            String rejectReason,
            String rejectReasonName,
            String heartbeatStatus,
            String heartbeatStatusName,
            LocalDateTime serverTime) {
    }

    /** 单条圈速事件上报结果：明确告诉设备这条事件被如何处理 */
    public record IngestResult(
            boolean accepted,
            String eventUid,
            long seqNo,
            String status,
            String statusName,
            String quarantineReason,
            String quarantineReasonName,
            Long lapId,
            int replayCount,
            boolean redFlag,
            String message) {
    }

    /** 韧性台一屏：本场设备 + 待对账 + 隔离区 + 接管 + 告警 + 时间线 + 当前来源 */
    public record TimingConsoleView(
            Long sessionId,
            String sessionNo,
            String sessionStatus,
            String sessionStatusName,
            boolean sessionActive,
            boolean redFlagActive,
            Long currentSourceDeviceId,
            TimingDeviceView currentSource,
            TimingSummary summary,
            List<TimingDeviceView> devices,
            List<TimingDeviceView> unboundDevices,
            List<LapEventView> pendingEvents,
            List<LapEventView> quarantineEvents,
            List<DeviceGapView> gaps,
            List<TakeoverView> takeovers,
            List<DeviceAlertView> alerts,
            List<DeviceTimelineView> timeline) {
    }
}
