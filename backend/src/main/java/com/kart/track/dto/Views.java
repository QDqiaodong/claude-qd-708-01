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
            long timingAlertActive,
            long timingQuarantined,
            long timingPendingEvents) {
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

    /** 设备心跳流水一行 */
    public record TimingHeartbeatView(
            Long id,
            Long deviceId,
            Long hbSeq,
            LocalDateTime occurredAt,
            LocalDateTime receivedAt,
            long gapMs,
            long ageMs,
            boolean gapFlap) {
    }

    /** 设备告警一行 */
    public record TimingAlertView(
            Long id,
            Long deviceId,
            String deviceCode,
            String deviceName,
            Long sessionId,
            String type,
            String typeName,
            String message,
            String status,
            String statusName,
            LocalDateTime raisedAt,
            String ackedBy,
            LocalDateTime ackedAt) {
    }

    /** 韧性台设备卡片：身份/赛道/场次/推导状态/最近序号/缺口/当前来源标记 */
    public record TimingDeviceView(
            Long id,
            String deviceCode,
            String name,
            String trackCode,
            String trackCodeName,
            String role,
            String roleName,
            Long sessionId,
            String sessionNo,
            boolean boundToSession,
            boolean quarantined,
            String quarantineReason,
            String quarantinedBy,
            LocalDateTime quarantinedAt,
            boolean enabled,
            String status,
            String statusName,
            LocalDateTime lastHeartbeatAt,
            long lastAgeMs,
            Long lastHbSeq,
            Long hbGapMs,
            long confirmedSeq,
            Long boundarySeq,
            Long openGapFrom,
            Long openGapTo,
            long pendingCount,
            boolean currentSource,
            int version,
            List<TimingHeartbeatView> recentHeartbeats,
            List<TimingAlertView> alerts) {
    }

    /** 序号缺口一行 */
    public record TimingGapView(
            Long id,
            Long deviceId,
            String deviceCode,
            String deviceName,
            long missingFrom,
            long missingTo,
            String status,
            String statusName,
            LocalDateTime openedAt,
            LocalDateTime filledAt,
            int pendingCount) {
    }

    /** 圈速事件总账一行（设备时间线/待对账/隔离区共用） */
    public record TimingLapEventView(
            Long id,
            String eventUid,
            Long deviceId,
            String deviceCode,
            String deviceName,
            String sourceRole,
            String sourceRoleName,
            Long sessionId,
            Long driverId,
            String driverName,
            Long kartId,
            String kartNo,
            long seq,
            Integer lapTimeMs,
            String lapTimeText,
            String valid,
            String validName,
            LocalDateTime occurredAt,
            LocalDateTime receivedAt,
            String stage,
            String stageName,
            Long lapId,
            Long gapId,
            String quarantineReason,
            String quarantineReasonName,
            String quarantineNote,
            String decidedBy,
            LocalDateTime decidedAt,
            boolean redFlagHeld) {
    }

    /** 主备接管记录一行 */
    public record TimingTakeoverView(
            Long id,
            String takeoverNo,
            Long sessionId,
            String sessionNo,
            Long oldDeviceId,
            String oldDeviceCode,
            String oldDeviceName,
            Long newDeviceId,
            String newDeviceCode,
            String newDeviceName,
            Long boundarySeq,
            LocalDateTime boundaryAt,
            String status,
            String statusName,
            String reason,
            String requestedBy,
            LocalDateTime requestedAt,
            String executedBy,
            LocalDateTime executedAt,
            String execNote,
            boolean executable) {
    }

    /** 场次当前数据来源 */
    public record TimingSourceView(
            Long deviceId,
            String deviceCode,
            String deviceName,
            String role,
            String roleName,
            Long takeoverId,
            LocalDateTime switchedAt) {
    }

    /** 设备时间线一个节点（心跳/事件/接管混排，按时刻回看一场比赛的来源变化） */
    public record TimingTimelineItem(
            String kind,
            String kindName,
            LocalDateTime at,
            Long deviceId,
            String deviceCode,
            String title,
            String detail,
            String tone) {
    }

    /** 圈速事件上报结果：明确告知设备这条事件的去向（重放返回同一结果） */
    public record LapIngestResult(
            String eventUid,
            Long deviceId,
            String deviceCode,
            Long sessionId,
            String stage,
            String stageName,
            String result,
            String resultText,
            long seq,
            long confirmedSeq,
            Long lapId,
            boolean duplicate,
            Long gapId,
            String quarantineReason,
            String quarantineReasonName,
            List<TimingLapEventView> advanced) {
    }

    /** 心跳上报结果 */
    public record HeartbeatResult(
            Long deviceId,
            String deviceCode,
            Long hbSeq,
            boolean duplicate,
            String status,
            String statusName,
            boolean accepted,
            String message) {
    }

    /** 韧性台一屏：场次 + 当前来源 + 待执行接管 + 设备/缺口/待对账/隔离/接管/告警/时间线 */
    public record TimingConsoleView(
            Long sessionId,
            String sessionNo,
            String sessionStatus,
            String sessionStatusName,
            String trackCode,
            String trackCodeName,
            TimingSourceView currentSource,
            TimingTakeoverView pendingTakeover,
            List<TimingDeviceView> devices,
            List<TimingGapView> gaps,
            List<TimingLapEventView> pendingEvents,
            List<TimingLapEventView> quarantinedEvents,
            List<TimingTakeoverView> takeovers,
            List<TimingAlertView> alerts,
            List<TimingTimelineItem> timeline,
            long acceptedCount,
            long pendingCount,
            long quarantinedCount,
            long openGapCount) {
    }
}
