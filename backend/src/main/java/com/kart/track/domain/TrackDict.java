package com.kart.track.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 赛道词典与业务规则参数表（车型驾照门槛、场次容量、工时费单价、圈速合法性区间等）。
 * 规则集中在此，便于出题时被要求调整或扩展。
 */
public final class TrackDict {

    private TrackDict() {
    }

    // ---------------- 车型 ----------------
    private static final Map<String, String> KART_MODEL = Map.of(
            "FUN", "娱乐车",
            "RACE", "竞技车",
            "KID", "儿童车",
            "DOUBLE", "双人车");

    /** 各车型要求的最低驾照等级（序号越小越低） */
    private static final Map<String, String> MODEL_MIN_LICENSE = Map.of(
            "FUN", "NONE",
            "RACE", "MID",
            "KID", "NONE",
            "DOUBLE", "BASIC");

    /** 儿童车只允许儿童组驾驶 */
    private static final Map<String, String> MODEL_AGE_GROUP = Map.of(
            "KID", "KID");

    // ---------------- 车辆状态 ----------------
    private static final Map<String, String> KART_STATUS = Map.of(
            "AVAILABLE", "可用",
            "ON_TRACK", "在赛",
            "MAINTENANCE", "维修",
            "DISABLED", "停用");

    /** 允许通过「状态切换」按钮直接设置的状态（在赛只能由派车/收车产生） */
    public static final java.util.List<String> SWITCHABLE_STATUS = java.util.List.of("AVAILABLE", "MAINTENANCE", "DISABLED");

    // ---------------- 车手 ----------------
    private static final Map<String, String> AGE_GROUP = Map.of(
            "KID", "儿童",
            "TEEN", "青少年",
            "ADULT", "成人");

    /** 驾照等级序号：0 无证 → 3 高级，用于门槛比较 */
    private static final Map<String, Integer> LICENSE_RANK = Map.of(
            "NONE", 0,
            "BASIC", 1,
            "MID", 2,
            "ADVANCED", 3);

    private static final Map<String, String> LICENSE_LEVEL = Map.of(
            "NONE", "无证",
            "BASIC", "初级",
            "MID", "中级",
            "ADVANCED", "高级");

    // ---------------- 场次 ----------------
    private static final Map<String, String> TRACK_CONFIG = Map.of(
            "HALF", "半场",
            "FULL", "全场",
            "NIGHT", "夜间");

    /** 各配置的单场容量上限（派车 / 报名都受它约束） */
    private static final Map<String, Integer> CONFIG_MAX_KART = Map.of(
            "HALF", 8,
            "FULL", 12,
            "NIGHT", 10);

    private static final Map<String, String> SESSION_STATUS = Map.of(
            "PENDING", "待开赛",
            "RUNNING", "进行中",
            "RED_FLAG", "红旗处理中",
            "FINISHED", "已结束",
            "CANCELED", "已取消");

    // ---------------- 红旗事件 ----------------
    private static final Map<String, String> RED_FLAG_STATUS = Map.of(
            "ACTIVE", "红旗处理中",
            "RESUMED", "已恢复比赛",
            "TERMINATED", "已强制终止");

    /** 车辆核查结论：可继续 / 退出比赛 / 需要维修 */
    private static final Map<String, String> CHECK_CONCLUSION = Map.of(
            "CONTINUE", "可继续",
            "RETIRE", "退出比赛",
            "REPAIR", "需要维修");

    /** 红旗期间圈速裁定：待裁定 / 已确认有效 / 已作废 */
    private static final Map<String, String> LAP_RULING = Map.of(
            "PENDING", "待裁定",
            "CONFIRMED", "已确认有效",
            "VOID", "已作废");

    // ---------------- 操作人角色（处置台权限收口） ----------------
    private static final Map<String, String> OPERATOR_ROLE = Map.of(
            "STAFF", "值班人员",
            "SUPERVISOR", "主管");

    // ---------------- 模块九：计时设备 ----------------
    private static final Map<String, String> DEVICE_ROLE = Map.of(
            "PRIMARY", "主设备",
            "STANDBY", "备用设备");

    /** 心跳推导状态（不入库）：在线 / 波动 / 离线；隔离是设备标记，优先于心跳状态 */
    private static final Map<String, String> DEVICE_STATUS = Map.of(
            "ONLINE", "在线",
            "FLAPPING", "波动",
            "OFFLINE", "离线",
            "ISOLATED", "已隔离",
            "UNBOUND", "未关联本场");

    /** 圈速事件阶段：已入账 / 待对账 / 隔离区 / 已丢弃 */
    private static final Map<String, String> LAP_EVENT_STAGE = Map.of(
            "ACCEPTED", "已入账",
            "PENDING", "待对账",
            "QUARANTINED", "隔离区",
            "DISCARDED", "已丢弃");

    /** 进隔离区的原因：旧主边界后 / 备用边界前 / 圈速越界 */
    private static final Map<String, String> QUARANTINE_REASON = Map.of(
            "OLD_AFTER_BOUNDARY", "旧主边界后事件",
            "BACKUP_BEFORE_BOUNDARY", "备用边界前事件",
            "BAD_LAP_TIME", "圈速超出合理区间");

    /** 设备告警类型 */
    private static final Map<String, String> DEVICE_ALERT_TYPE = Map.of(
            "OFFLINE", "离线",
            "FLAPPING", "心跳波动",
            "GAP", "序号缺口",
            "TAKEOVER_REQ", "接管请求",
            "QUARANTINED", "设备隔离");

    /** 告警状态 */
    private static final Map<String, String> DEVICE_ALERT_STATUS = Map.of(
            "ACTIVE", "未确认",
            "ACKED", "已确认",
            "RESOLVED", "已自动消除");

    /** 接管记录状态 */
    private static final Map<String, String> TAKEOVER_STATUS = Map.of(
            "REQUESTED", "待主管执行",
            "DONE", "已完成",
            "REJECTED", "已驳回",
            "CANCELED", "已撤销");

    /** 序号缺口状态 */
    private static final Map<String, String> GAP_STATUS = Map.of(
            "OPEN", "缺口中",
            "FILLED", "已补齐");

    /** 心跳节奏：正常间隔约 3 秒 */
    public static final long HEARTBEAT_INTERVAL_MS = 3000;
    /** 最近一次心跳间隔超过该值视为节奏波动（如 4 个周期才报到） */
    public static final long HEARTBEAT_FLAP_GAP_MS = 10000;
    /** 最后心跳年龄阈值：≤10s 在线，10~30s 波动，>30s 离线 */
    public static final long HEARTBEAT_ONLINE_AGE_MS = 10000;
    public static final long HEARTBEAT_OFFLINE_AGE_MS = 30000;

    // ---------------- 圈速 ----------------
    private static final Map<String, String> LAP_VALID = Map.of(
            "VALID", "有效",
            "OUT", "无效-出界",
            "CRASH", "无效-碰撞");

    /** 单圈合理区间（毫秒）：快于 20 秒或慢于 5 分钟都视为误录 */
    public static final int MIN_LAP_MS = 20000;
    public static final int MAX_LAP_MS = 300000;

    /** 单圈里程（km）：半场 0.6，全场/夜间 1.2 */
    private static final Map<String, BigDecimal> CONFIG_LAP_KM = Map.of(
            "HALF", BigDecimal.valueOf(0.6),
            "FULL", BigDecimal.valueOf(1.2),
            "NIGHT", BigDecimal.valueOf(1.2));

    /** 折算保养到期用的日均运行小时 */
    public static final int DAILY_HOURS = 8;

    public static BigDecimal configLapKm(String config) {
        return CONFIG_LAP_KM.getOrDefault(config, BigDecimal.valueOf(1.2));
    }

    // ---------------- 维修保养 ----------------
    private static final Map<String, String> MAINT_TYPE = Map.of(
            "OIL", "换机油",
            "TIRE", "换轮胎",
            "BRAKE", "刹车片",
            "CHAIN", "链条",
            "SPARK", "火花塞",
            "ENGINE", "发动机大修",
            "BODY", "车身修复");

    /** 完工后需要重置「上次保养日期」的保养类项目 */
    private static final java.util.List<String> SERVICE_TYPES = java.util.List.of("OIL", "CHAIN", "SPARK", "ENGINE");

    private static final Map<String, String> MAINT_STATUS = Map.of(
            "WAITING", "待修",
            "REPAIRING", "维修中",
            "DONE", "已完工");

    /** 工时费单价（元/小时） */
    public static final BigDecimal LABOR_RATE = BigDecimal.valueOf(80);

    /** 单场报名/派车的默认上限兜底 */
    public static final int DEFAULT_MAX_KARTS = 12;

    // ---------------- 取值方法 ----------------

    public static boolean isValidKartModel(String model) {
        return model != null && KART_MODEL.containsKey(model);
    }

    public static String kartModelName(String model) {
        return model == null ? "" : KART_MODEL.getOrDefault(model, model);
    }

    public static String modelMinLicense(String model) {
        return MODEL_MIN_LICENSE.getOrDefault(model, "NONE");
    }

    public static String modelAgeGroup(String model) {
        return MODEL_AGE_GROUP.getOrDefault(model, "");
    }

    public static boolean isValidKartStatus(String status) {
        return status != null && KART_STATUS.containsKey(status);
    }

    public static String kartStatusName(String status) {
        return status == null ? "" : KART_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidAgeGroup(String group) {
        return group != null && AGE_GROUP.containsKey(group);
    }

    public static String ageGroupName(String group) {
        return group == null ? "" : AGE_GROUP.getOrDefault(group, group);
    }

    public static boolean isValidLicense(String level) {
        return level != null && LICENSE_LEVEL.containsKey(level);
    }

    public static String licenseName(String level) {
        return level == null ? "" : LICENSE_LEVEL.getOrDefault(level, level);
    }

    public static int licenseRank(String level) {
        return LICENSE_RANK.getOrDefault(level, 0);
    }

    public static boolean isValidTrackConfig(String config) {
        return config != null && TRACK_CONFIG.containsKey(config);
    }

    public static String trackConfigName(String config) {
        return config == null ? "" : TRACK_CONFIG.getOrDefault(config, config);
    }

    public static int configMaxKart(String config) {
        return CONFIG_MAX_KART.getOrDefault(config, DEFAULT_MAX_KARTS);
    }

    public static boolean isValidSessionStatus(String status) {
        return status != null && SESSION_STATUS.containsKey(status);
    }

    public static String sessionStatusName(String status) {
        return status == null ? "" : SESSION_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidLapValid(String valid) {
        return valid != null && LAP_VALID.containsKey(valid);
    }

    public static String lapValidName(String valid) {
        return valid == null ? "" : LAP_VALID.getOrDefault(valid, valid);
    }

    public static boolean isValidRedFlagStatus(String status) {
        return status != null && RED_FLAG_STATUS.containsKey(status);
    }

    public static String redFlagStatusName(String status) {
        return status == null ? "" : RED_FLAG_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidCheckConclusion(String conclusion) {
        return conclusion != null && CHECK_CONCLUSION.containsKey(conclusion);
    }

    public static String checkConclusionName(String conclusion) {
        return conclusion == null ? "待核查" : CHECK_CONCLUSION.getOrDefault(conclusion, conclusion);
    }

    public static boolean isValidLapRuling(String ruling) {
        return ruling != null && LAP_RULING.containsKey(ruling);
    }

    public static String lapRulingName(String ruling) {
        return ruling == null ? "" : LAP_RULING.getOrDefault(ruling, ruling);
    }

    public static boolean isValidOperatorRole(String role) {
        return role != null && OPERATOR_ROLE.containsKey(role);
    }

    public static String operatorRoleName(String role) {
        return role == null ? "" : OPERATOR_ROLE.getOrDefault(role, role);
    }

    public static boolean isValidMaintType(String type) {
        return type != null && MAINT_TYPE.containsKey(type);
    }

    public static String maintTypeName(String type) {
        return type == null ? "" : MAINT_TYPE.getOrDefault(type, type);
    }

    public static boolean isServiceType(String type) {
        return type != null && SERVICE_TYPES.contains(type);
    }

    public static boolean isValidMaintStatus(String status) {
        return status != null && MAINT_STATUS.containsKey(status);
    }

    public static String maintStatusName(String status) {
        return status == null ? "" : MAINT_STATUS.getOrDefault(status, status);
    }

    // ---------------- 计时设备取值方法 ----------------

    public static boolean isValidDeviceRole(String role) {
        return role != null && DEVICE_ROLE.containsKey(role);
    }

    public static String deviceRoleName(String role) {
        return role == null ? "" : DEVICE_ROLE.getOrDefault(role, role);
    }

    public static String deviceStatusName(String status) {
        return status == null ? "" : DEVICE_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidLapEventStage(String stage) {
        return stage != null && LAP_EVENT_STAGE.containsKey(stage);
    }

    public static String lapEventStageName(String stage) {
        return stage == null ? "" : LAP_EVENT_STAGE.getOrDefault(stage, stage);
    }

    public static String quarantineReasonName(String reason) {
        return reason == null ? "" : QUARANTINE_REASON.getOrDefault(reason, reason);
    }

    public static boolean isValidQuarantineReason(String reason) {
        return reason != null && QUARANTINE_REASON.containsKey(reason);
    }

    public static String deviceAlertTypeName(String type) {
        return type == null ? "" : DEVICE_ALERT_TYPE.getOrDefault(type, type);
    }

    public static String deviceAlertStatusName(String status) {
        return status == null ? "" : DEVICE_ALERT_STATUS.getOrDefault(status, status);
    }

    public static String takeoverStatusName(String status) {
        return status == null ? "" : TAKEOVER_STATUS.getOrDefault(status, status);
    }

    public static String gapStatusName(String status) {
        return status == null ? "" : GAP_STATUS.getOrDefault(status, status);
    }

    /**
     * 按最后心跳年龄与最近间隔推导设备心跳状态（不含隔离，隔离由调用方优先处理）。
     *
     * @param ageMs   距最后一次心跳的毫秒数（从未心跳传 Long.MAX_VALUE）
     * @param gapMs   最近两次心跳间隔（毫秒），可空
     */
    public static String deriveDeviceStatus(long ageMs, Long gapMs) {
        if (ageMs > HEARTBEAT_OFFLINE_AGE_MS) {
            return "OFFLINE";
        }
        if (ageMs > HEARTBEAT_ONLINE_AGE_MS || (gapMs != null && gapMs > HEARTBEAT_FLAP_GAP_MS)) {
            return "FLAPPING";
        }
        return "ONLINE";
    }

    /** 毫秒 → mm:ss.mmm；空值返回 --:--.--- */
    public static String formatLap(Integer ms) {
        if (ms == null || ms <= 0) {
            return "--:--.---";
        }
        int minute = ms / 60000;
        int second = (ms % 60000) / 1000;
        int milli = ms % 1000;
        return String.format("%02d:%02d.%03d", minute, second, milli);
    }

    /** 与最快圈的差值：+1.234（第一名返回 —） */
    public static String formatDelta(Integer ms, Integer bestMs) {
        if (ms == null || bestMs == null) {
            return "—";
        }
        int diff = ms - bestMs;
        if (diff <= 0) {
            return "—";
        }
        return String.format("+%.3f", diff / 1000.0);
    }
}
