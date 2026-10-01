-- ============================================================================
-- 卡丁车赛道运营管理系统 · 建表 + 种子数据
-- 库名 kart_track，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 业务模块（原有 8 个 + 新增计时设备韧性台 5 张相关表组）：
--   1. kart         卡丁车（车号·车型·排量·功率·状态·累计里程/小时·保养间隔）
--   2. driver       车手（会员号·年龄分组·驾照等级·累计场次·历史最快圈速）
--   3. race_session 场次（赛道配置·分组·最大车辆数·已报名人数·状态）
--   4. lap_record   圈速记录（写入走实体上的 @SQLInsert/@SQLUpdate/@SQLDelete 手写 SQL）
--   5. maintenance  维修保养（工时·配件费·总费用·待修/维修中/已完工）
--   6. red_flag_event 红旗事件（原因·发生时刻·生效中/已恢复/已终止；同场次同时只允许一个生效中）
--   7. red_flag_check 红旗车辆核查（可继续 / 退出比赛 / 需要维修，每车一条、互不影响）
--   8. red_flag_lap   红旗期间圈速隔离（1:1 挂 lap_record：待主管裁定 / 已确认有效 / 已作废）
--   9. timing_device  计时设备身份（稳定设备码·所属赛道·主/备·当前场次·隔离标记·乐观锁版本）
--  10. timing_device_session 设备×场次序号水位（confirmed_seq 是断线补偿的确认进度点）
--  11. timing_heartbeat / timing_alert 心跳流水与设备告警（在线/波动/离线/隔离由心跳年龄推导）
--  12. timing_lap_event / timing_gap 圈速事件总账与序号缺口（去重·待对账·隔离留证）
--  13. timing_takeover / timing_source 主备接管记录与场次当前数据来源（边界时刻+边界序号）
--
-- 种子数据保证下面每条业务规则都能被真的触发（不是"理论上可以"）：
--   [R1.1] 车号唯一            → K-01 已存在
--   [R1.2] 车型驾照门槛        → 竞技车要中级以上（无证/初级会被拦）、儿童车只给儿童组
--   [R1.3] 未派车不能记圈速    → 只有 ON_TRACK 的车能登记圈速
--   [R1.4] 保养到期高亮        → K-03/K-05/K-07/K-08 已超保养间隔
--   [R1.5] 派车上限            → 半场 8 台、全场 12 台、夜间 10 台
--   [R2.1] 会员号唯一          → M2026001 已存在
--   [R2.2] 夜间场不接待儿童组  → 儿童车手报名 NIGHT 场次会被拦
--   [R2.3] 报名上限            → 场次人数达 max_karts 后拒绝报名
--   [R3.1] 场次状态机顺序      → 待开赛 → 进行中 → 已结束 / 已取消
--   [R3.2] 无人报名不能开赛    → S2026092402 已有 5 人报名可开；signed_count=0 会被拦
--   [R3.3] 只有进行中能记圈速  → S2026092401 = RUNNING
--   [R4.1] 圈速区间校验        → 20 秒 ~ 300 秒之外视为误录
--   [R4.2] 本场最快圈唯一      → 新纪录会把手写 update 里的旧最快圈复位
--   [R5.1] 总费用 = 工时 × 80 + 配件费
--   [R5.2] 完工复位车辆状态    → 保养类项目完工后重置「上次保养日期」
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS kart_track DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE kart_track;

DROP TABLE IF EXISTS timing_alert;
DROP TABLE IF EXISTS timing_heartbeat;
DROP TABLE IF EXISTS timing_gap;
DROP TABLE IF EXISTS timing_lap_event;
DROP TABLE IF EXISTS timing_takeover;
DROP TABLE IF EXISTS timing_source;
DROP TABLE IF EXISTS timing_device_session;
DROP TABLE IF EXISTS timing_device;
DROP TABLE IF EXISTS red_flag_lap;
DROP TABLE IF EXISTS red_flag_check;
DROP TABLE IF EXISTS red_flag_event;
DROP TABLE IF EXISTS maintenance;
DROP TABLE IF EXISTS lap_record;
DROP TABLE IF EXISTS race_session;
DROP TABLE IF EXISTS driver;
DROP TABLE IF EXISTS kart;

-- ----------------------------------------------------------------------------
-- 模块一：卡丁车
-- ----------------------------------------------------------------------------
CREATE TABLE kart (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    kart_no            VARCHAR(16)   NOT NULL COMMENT '车号',
    model              VARCHAR(16)   NOT NULL COMMENT 'FUN 娱乐车 / RACE 竞技车 / KID 儿童车 / DOUBLE 双人车',
    engine_cc          INT           NOT NULL COMMENT '发动机排量（cc）',
    max_power          DECIMAL(5, 2) NOT NULL COMMENT '最大功率（kW）',
    status             VARCHAR(16)   NOT NULL COMMENT 'AVAILABLE 可用 / ON_TRACK 在赛 / MAINTENANCE 维修 / DISABLED 停用',
    total_km           DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '累计里程（km）',
    total_hours        DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '累计运行小时',
    last_service_date  DATE          NULL COMMENT '上次保养日期',
    service_interval_h INT           NOT NULL DEFAULT 50 COMMENT '保养间隔（小时）',
    updated_at         DATETIME      NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kart_no (kart_no),
    KEY idx_kart_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '卡丁车';

-- ----------------------------------------------------------------------------
-- 模块二：车手
-- ----------------------------------------------------------------------------
CREATE TABLE driver (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    member_no      VARCHAR(20)   NOT NULL COMMENT '会员号',
    name           VARCHAR(30)   NOT NULL COMMENT '姓名',
    phone          VARCHAR(20)   NOT NULL COMMENT '手机号',
    age_group      VARCHAR(16)   NOT NULL COMMENT 'KID 儿童 / TEEN 青少年 / ADULT 成人',
    license_level  VARCHAR(16)   NOT NULL COMMENT 'NONE 无证 / BASIC 初级 / MID 中级 / ADVANCED 高级',
    total_sessions INT           NOT NULL DEFAULT 0 COMMENT '累计场次',
    best_lap_ms    INT           NOT NULL DEFAULT 0 COMMENT '历史最快圈速（毫秒），0 表示还没成绩',
    total_spend    DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '总消费金额',
    register_date  DATE          NOT NULL COMMENT '注册日期',
    session_id     BIGINT        NULL COMMENT '当前报名/在赛的场次 id',
    PRIMARY KEY (id),
    UNIQUE KEY uk_driver_member_no (member_no),
    KEY idx_driver_session (session_id),
    KEY idx_driver_best (best_lap_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '车手';

-- ----------------------------------------------------------------------------
-- 模块三：场次
-- ----------------------------------------------------------------------------
CREATE TABLE race_session (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    session_no   VARCHAR(24) NOT NULL COMMENT '场次号',
    start_time   DATETIME    NULL COMMENT '开始时间',
    end_time     DATETIME    NULL COMMENT '结束时间',
    track_config VARCHAR(16) NOT NULL COMMENT 'HALF 半场 / FULL 全场 / NIGHT 夜间',
    group_code   VARCHAR(4)  NOT NULL COMMENT '分组：A / B / C',
    max_karts    INT         NOT NULL COMMENT '最大车辆数',
    signed_count INT         NOT NULL DEFAULT 0 COMMENT '已报名人数',
    status       VARCHAR(16) NOT NULL COMMENT 'PENDING 待开赛 / RUNNING 进行中 / RED_FLAG 红旗处理中 / FINISHED 已结束 / CANCELED 已取消',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_no (session_no),
    KEY idx_session_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '场次';

-- ----------------------------------------------------------------------------
-- 模块四：圈速记录（写链路走实体上的 @SQLInsert / @SQLUpdate / @SQLDelete）
-- ----------------------------------------------------------------------------
CREATE TABLE lap_record (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id  BIGINT      NOT NULL COMMENT '场次',
    driver_id   BIGINT      NOT NULL COMMENT '车手',
    kart_id     BIGINT      NOT NULL COMMENT '车辆',
    lap_index   INT         NOT NULL COMMENT '圈序',
    lap_time_ms INT         NOT NULL COMMENT '圈速（毫秒）',
    is_fastest  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否本场最快圈',
    valid       VARCHAR(16) NOT NULL COMMENT 'VALID 有效 / OUT 无效-出界 / CRASH 无效-碰撞',
    created_at  DATETIME    NULL COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_lap_session (session_id),
    KEY idx_lap_driver (driver_id),
    KEY idx_lap_kart (kart_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '圈速记录';

-- ----------------------------------------------------------------------------
-- 模块五：维修保养
-- ----------------------------------------------------------------------------
CREATE TABLE maintenance (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    order_no   VARCHAR(24)   NOT NULL COMMENT '维修单号',
    kart_id    BIGINT        NOT NULL COMMENT '车辆',
    maint_type VARCHAR(16)   NOT NULL COMMENT 'OIL 换机油 / TIRE 换轮胎 / BRAKE 刹车片 / CHAIN 链条 / SPARK 火花塞 / ENGINE 发动机大修 / BODY 车身修复',
    fault_desc VARCHAR(200)  NULL COMMENT '故障描述',
    work_hours DECIMAL(6, 2) NOT NULL DEFAULT 0 COMMENT '工时',
    parts_fee  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '配件费',
    total_fee  DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '总费用 = 工时 × 80 + 配件费',
    worker     VARCHAR(30)   NULL COMMENT '维修人',
    start_time DATETIME      NULL COMMENT '开始时间',
    end_time   DATETIME      NULL COMMENT '结束时间',
    status     VARCHAR(16)   NOT NULL COMMENT 'WAITING 待修 / REPAIRING 维修中 / DONE 已完工',
    PRIMARY KEY (id),
    UNIQUE KEY uk_maint_order_no (order_no),
    KEY idx_maint_kart (kart_id),
    KEY idx_maint_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '维修保养';

-- ----------------------------------------------------------------------------
-- 模块六：红旗事件
--
-- active_key 是生成列：生效中（ACTIVE）= 场次 id；已恢复/已终止/已取消 = NULL。
-- MySQL 唯一索引允许多个 NULL，因此 uk_active_session 从数据库层兜底：
-- 同一场次同时最多只有一个 ACTIVE，两名值班人员再接近地同时发起也只能落一条。
-- ----------------------------------------------------------------------------
CREATE TABLE red_flag_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    event_no       VARCHAR(24)  NOT NULL COMMENT '事件编号，如 RF20260930001',
    session_id     BIGINT       NOT NULL COMMENT '所属场次',
    reason         VARCHAR(200) NOT NULL COMMENT '红旗原因（发起时必填）',
    occurred_at    DATETIME     NOT NULL COMMENT '红旗发生时刻（服务端落库时间）',
    status         VARCHAR(16)  NOT NULL COMMENT 'ACTIVE 生效中 / RESUMED 已恢复 / TERMINATED 已强制终止',
    raised_by      VARCHAR(60)  NOT NULL COMMENT '发起人（值班人员）',
    resumed_by     VARCHAR(60)  NULL COMMENT '恢复操作人（主管）',
    resumed_at     DATETIME     NULL COMMENT '恢复时刻',
    terminated_by  VARCHAR(60)  NULL COMMENT '强制终止操作人（主管）',
    terminated_at  DATETIME     NULL COMMENT '终止时刻',
    active_key     BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN session_id ELSE NULL END) STORED COMMENT '生效中唯一约束辅助列',
    created_at     DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_redflag_event_no (event_no),
    UNIQUE KEY uk_active_session (active_key),
    KEY idx_redflag_session (session_id),
    KEY idx_redflag_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '红旗事件';

-- ----------------------------------------------------------------------------
-- 模块七：红旗车辆核查清单（发起红旗时，以当时仍在赛的车辆逐车快照生成）
--
-- 结论提交即冻结，后续任何车辆（退出/送修）都不会改写其它行。
-- need_repair 提交时车辆进维修状态并建 WAITING 维修单，单号回填到 maint_order_no。
-- ----------------------------------------------------------------------------
CREATE TABLE red_flag_check (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    event_id        BIGINT       NOT NULL COMMENT '红旗事件',
    session_id      BIGINT       NOT NULL COMMENT '所属场次（冗余便于查询）',
    kart_id         BIGINT       NOT NULL COMMENT '被核查车辆',
    driver_id       BIGINT       NULL COMMENT '发起时该车的在赛车手（快照）',
    conclusion      VARCHAR(16)  NULL COMMENT 'NULL 待核查 / CONTINUE 可继续 / RETIRE 退出比赛 / REPAIR 需要维修',
    remark          VARCHAR(200) NULL COMMENT '核查备注',
    checked_by      VARCHAR(60)  NULL COMMENT '核查提交人（值班人员）',
    checked_at      DATETIME     NULL COMMENT '核查提交时刻',
    maint_order_no  VARCHAR(24)  NULL COMMENT '需要维修时生成的待修维修单号',
    created_at      DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_check_event_kart (event_id, kart_id),
    KEY idx_check_event (event_id),
    KEY idx_check_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '红旗车辆核查清单';

-- ----------------------------------------------------------------------------
-- 模块八：红旗期间圈速隔离（与 lap_record 1:1，保护模块四原有的手写 @SQLInsert）
--
-- 红旗期间计时设备仍会上报圈速：lap_record 照常留底（valid 按设备原值落，
-- 不写最快圈、不刷车手历史最好成绩），这里挂一条 PENDING 记录，标明产生于哪次红旗。
-- 主管恢复前逐条裁定：CONFIRMED 确认有效（此时才补排榜/刷最佳）/ VOID 作废（永不入榜）。
-- 已裁定记录是冻结的，刷新或重复操作不会让旧圈再次入榜。
-- ----------------------------------------------------------------------------
CREATE TABLE red_flag_lap (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    lap_id           BIGINT       NOT NULL COMMENT '圈速记录（lap_record.id），1:1',
    event_id         BIGINT       NOT NULL COMMENT '产生于哪次红旗事件',
    session_id       BIGINT       NOT NULL COMMENT '所属场次（冗余）',
    ruling           VARCHAR(16)  NOT NULL COMMENT 'PENDING 待裁定 / CONFIRMED 已确认有效 / VOID 已作废',
    ruled_by         VARCHAR(60)  NULL COMMENT '裁定人（主管）',
    ruled_at         DATETIME     NULL COMMENT '裁定时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_redflag_lap_lap (lap_id),
    KEY idx_redflag_lap_event (event_id),
    KEY idx_redflag_lap_session_ruling (session_id, ruling)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '红旗期间圈速隔离裁定';

-- ----------------------------------------------------------------------------
-- 模块九：计时设备身份
--
-- 设备码 device_code 是设备侧烧录的稳定身份（重放/接管都认它，不认自增 id）。
-- 每台设备明确绑定一条物理赛道（track_code：HALF/FULL/NIGHT），session_id 是它
-- 当前服务的场次（可为空 = 未关联本场）。role PRIMARY/STANDBY 区分主设备与备用设备。
--
-- 隔离 quarantined=1 由主管设置/解除：隔离设备仍接收心跳留痕，但圈速上报一律拒绝，
-- 绝不混入本场成绩。version 是 JPA 乐观锁版本：过期页面/重复操作无法覆盖新状态。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_device (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    device_code     VARCHAR(40) NOT NULL COMMENT '设备侧稳定身份码（烧录，不随场次变化）',
    name            VARCHAR(40) NOT NULL COMMENT '设备名称',
    track_code      VARCHAR(16) NOT NULL COMMENT '所属物理赛道：HALF/FULL/NIGHT',
    role            VARCHAR(16) NOT NULL COMMENT 'PRIMARY 主设备 / STANDBY 备用设备',
    session_id      BIGINT      NULL COMMENT '当前关联场次（NULL=未关联本场）',
    last_heartbeat_at DATETIME  NULL COMMENT '最后一次心跳时刻',
    last_hb_seq     BIGINT      NULL COMMENT '最近一次心跳序号（设备侧心跳自增号）',
    hb_gap_ms       BIGINT      NULL COMMENT '最近两次心跳间隔（毫秒），用于波动判定',
    quarantined     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否被主管隔离：1 时圈速上报一律拒绝',
    quarantine_reason VARCHAR(200) NULL COMMENT '隔离原因',
    quarantined_by  VARCHAR(60) NULL COMMENT '隔离操作人（主管）',
    quarantined_at  DATETIME    NULL COMMENT '隔离时刻',
    enabled         TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否在用（停用设备不参与接管）',
    version         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本（@Version）',
    created_at      DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_timing_device_code (device_code),
    KEY idx_timing_device_track (track_code),
    KEY idx_timing_device_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备';

-- ----------------------------------------------------------------------------
-- 模块十：设备 × 场次 序号水位
--
-- confirmed_seq 是「已经确认进入正常计圈链」的最大圈速序号——断线补偿的检查点。
-- 设备重连批量重放时，系统只从 confirmed_seq+1 继续处理；补偿中途再次中断，
-- 已确认的进度不会回退、已落账事件不会重复处理（timing_lap_event 的 uk 再兜底去重）。
-- 接管发生时为主设备/备用设备各开一行，边界序号 boundary_seq 明确「谁负责哪一段」。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_device_session (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    device_id       BIGINT      NOT NULL COMMENT '设备',
    session_id      BIGINT      NOT NULL COMMENT '场次',
    confirmed_seq   BIGINT      NOT NULL DEFAULT 0 COMMENT '已确认入账的最大圈速序号（补偿检查点）',
    boundary_seq    BIGINT      NULL COMMENT '接管边界序号：仅该边界之前/之后认本设备（以发生时刻为准）',
    bound_at        DATETIME    NOT NULL COMMENT '绑定/接管进入本场的时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tds_device_session (device_id, session_id),
    KEY idx_tds_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '设备×场次序号水位';

-- ----------------------------------------------------------------------------
-- 模块十一（心跳）：心跳流水
--
-- 在线/波动/离线不入库、在读取时按「最后心跳年龄 + 最近间隔」推导：
--   在线 ONLINE（≤10s 且节奏稳定）/ 波动 FLAPPING（最近间隔 >10s，或年龄 10~30s）/
--   离线 OFFLINE（超过 30s 无心跳）；设备被隔离时直接显示 ISOLATED。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_heartbeat (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    device_id       BIGINT      NOT NULL COMMENT '设备',
    session_id      BIGINT      NULL COMMENT '上报时设备关联的场次（快照）',
    hb_seq          BIGINT      NOT NULL COMMENT '心跳序号（设备侧自增，重复号不更新水位）',
    occurred_at     DATETIME    NOT NULL COMMENT '心跳实际产生时刻（设备侧）',
    received_at     DATETIME    NOT NULL COMMENT '服务端收到时刻',
    gap_ms          BIGINT      NOT NULL DEFAULT 0 COMMENT '与上一心跳的间隔（毫秒），首条为 0',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hb_device_seq (device_id, hb_seq),
    KEY idx_hb_device_time (device_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备心跳流水';

-- ----------------------------------------------------------------------------
-- 模块十一（告警）：设备告警（值班人员可确认，确认即冻结不复活）
-- ----------------------------------------------------------------------------
CREATE TABLE timing_alert (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    device_id       BIGINT      NOT NULL COMMENT '设备',
    session_id      BIGINT      NULL COMMENT '产生时关联场次（快照）',
    type            VARCHAR(24) NOT NULL COMMENT 'OFFLINE 离线 / FLAPPING 心跳波动 / GAP 序号缺口 / TAKEOVER_REQ 接管请求 / QUARANTINED 隔离',
    message         VARCHAR(200) NOT NULL COMMENT '告警内容',
    status          VARCHAR(16)  NOT NULL COMMENT 'ACTIVE 未确认 / ACKED 已确认 / RESOLVED 已自动消除',
    raised_at       DATETIME     NOT NULL COMMENT '产生时刻',
    acked_by        VARCHAR(60)  NULL COMMENT '确认人（值班人员/主管）',
    acked_at        DATETIME     NULL COMMENT '确认时刻',
    dedup_key       VARCHAR(120) NOT NULL COMMENT '同设备同类型只允许一条 ACTIVE 的去重键',
    active_key      VARCHAR(120) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN dedup_key ELSE NULL END) STORED COMMENT '仅 ACTIVE 行存去重键（唯一索引允许多个 NULL）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_active (active_key),
    KEY idx_alert_device (device_id),
    KEY idx_alert_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备告警';

-- ----------------------------------------------------------------------------
-- 模块十二：圈速事件总账（设备上报一条事件 = 这里一行，重放幂等）
--
-- event_uid 是设备侧唯一标识（设备内全局唯一）：uk_device_uid 保证同一事件无论
-- 重放多少次，只产生一条事件、至多一条 lap_record（lap_id 1:1 回链）。
-- seq 是本场内设备侧递增圈速序号；source_role 记录事件来自主设备还是备用设备。
--
-- stage 状态机：
--   ACCEPTED  已确认进入正常计圈链（序号连续 + 来源有效，已落 lap_record）
--   PENDING   待对账（发现序号缺口，缺口之后先保留，绝不进排行榜/历史最好）
--   QUARANTINED 隔离区（来源不匹配接管边界等，主管查明后 DISCARD/RESEGMENT）
--   DISCARDED 主管丢弃，仅留底
-- 红旗期间被 ACCEPTED 的事件同时挂 red_flag_lap 待主管裁定，不写最快圈/最佳。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_lap_event (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    event_uid       VARCHAR(60) NOT NULL COMMENT '设备侧事件唯一标识（重放幂等键）',
    device_id       BIGINT      NOT NULL COMMENT '上报设备',
    session_id      BIGINT      NOT NULL COMMENT '所属场次',
    driver_id       BIGINT      NOT NULL COMMENT '车手（设备检测）',
    kart_id         BIGINT      NOT NULL COMMENT '车辆（设备检测）',
    seq             BIGINT      NOT NULL COMMENT '本场内设备侧递增圈速序号',
    lap_time_ms     INT         NOT NULL COMMENT '圈速（毫秒）',
    valid           VARCHAR(16) NOT NULL COMMENT 'VALID/OUT/CRASH（设备判定）',
    occurred_at     DATETIME    NOT NULL COMMENT '圈速实际产生时刻（设备侧）',
    received_at     DATETIME    NOT NULL COMMENT '服务端收到时刻（重放时远晚于产生时刻）',
    source_role     VARCHAR(16) NOT NULL COMMENT '上报时设备角色 PRIMARY/STANDBY',
    stage           VARCHAR(16) NOT NULL COMMENT 'ACCEPTED/PENDING/QUARANTINED/DISCARDED',
    lap_id          BIGINT      NULL COMMENT '入账后回链 lap_record.id（1:1，未入账为 NULL）',
    gap_id          BIGINT      NULL COMMENT '归属的序号缺口（待对账期间）',
    quarantine_reason VARCHAR(32) NULL COMMENT '隔离原因码：OLD_AFTER_BOUNDARY 旧主边界后 / BACKUP_BEFORE_BOUNDARY 备用边界前 / BAD_LAP_TIME 圈速越界',
    quarantine_note   VARCHAR(200) NULL COMMENT '隔离说明',
    decided_by      VARCHAR(60) NULL COMMENT '隔离区处置主管',
    decided_at      DATETIME    NULL COMMENT '隔离区处置时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tle_device_uid (device_id, event_uid),
    UNIQUE KEY uk_tle_device_session_seq (device_id, session_id, seq),
    KEY idx_tle_session_stage (session_id, stage),
    KEY idx_tle_device_session (device_id, session_id),
    KEY idx_tle_lap (lap_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备圈速事件总账';

-- ----------------------------------------------------------------------------
-- 模块十二：序号缺口
--
-- 收到 seq > confirmed_seq+1 时开窗（OPEN），missing_from/missing_to 为缺失区间；
-- 重放按序补齐后关窗（FILLED）。一条缺口挂住其后所有 PENDING 事件，关窗时才放行。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_gap (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    device_id       BIGINT      NOT NULL COMMENT '设备',
    session_id      BIGINT      NOT NULL COMMENT '场次',
    missing_from    BIGINT      NOT NULL COMMENT '缺失起始序号',
    missing_to      BIGINT      NOT NULL COMMENT '缺失结束序号（随更大缺口更新）',
    status          VARCHAR(16)  NOT NULL COMMENT 'OPEN 缺口未补齐 / FILLED 已按序补齐',
    opened_at       DATETIME     NOT NULL COMMENT '发现缺口时刻',
    filled_at       DATETIME     NULL COMMENT '补齐关窗时刻',
    pending_count   INT          NOT NULL DEFAULT 0 COMMENT '挂在该缺口上的待对账事件数',
    PRIMARY KEY (id),
    KEY idx_gap_device_session (device_id, session_id),
    KEY idx_gap_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时圈速序号缺口';

-- ----------------------------------------------------------------------------
-- 模块十三：主备接管记录
--
-- 边界 = boundary_at（接管生效时刻）+ boundary_seq（旧主最后负责的圈速序号）：
--   边界之前只认主设备，边界之后只认备用设备。旧主晚到的「边界后」事件、备用提前
--   产生的「边界前」事件一律进隔离区（timing_lap_event.stage=QUARANTINED）。
-- REQUESTED 是值班人员发起的接管请求（待主管执行）；同场次同时只允许一条生效请求。
-- DONE 的记录与 timing_source 一起在同一事务提交，任何一步失败整体回滚——
-- 不会出现两台设备同时是当前来源，或接管已记录但排行榜仍沿用旧来源。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_takeover (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    takeover_no     VARCHAR(30) NOT NULL COMMENT '接管编号，如 TO20260924S1-1',
    session_id      BIGINT      NOT NULL COMMENT '场次',
    old_device_id   BIGINT      NOT NULL COMMENT '原主设备',
    new_device_id   BIGINT      NOT NULL COMMENT '接管备用设备',
    boundary_seq    BIGINT      NULL COMMENT '边界序号：旧主负责到该序号（请求时记录）',
    boundary_at     DATETIME    NULL COMMENT '边界时刻（主管执行时落定）',
    status          VARCHAR(16) NOT NULL COMMENT 'REQUESTED 待执行 / DONE 已完成 / REJECTED 已驳回 / CANCELED 已撤销',
    reason          VARCHAR(200) NOT NULL COMMENT '接管原因（请求时必填）',
    requested_by    VARCHAR(60) NOT NULL COMMENT '请求发起人（值班人员）',
    requested_at    DATETIME    NOT NULL COMMENT '请求时刻',
    executed_by     VARCHAR(60) NULL COMMENT '执行/驳回主管',
    executed_at     DATETIME    NULL COMMENT '执行/驳回时刻',
    exec_note       VARCHAR(200) NULL COMMENT '驳回原因等',
    PRIMARY KEY (id),
    UNIQUE KEY uk_takeover_no (takeover_no),
    KEY idx_takeover_session (session_id),
    KEY idx_takeover_status (status),
    KEY idx_takeover_devices (old_device_id, new_device_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备主备接管';

-- ----------------------------------------------------------------------------
-- 模块十三：场次当前数据来源（一场至多一行）
--
-- 当前来源切换与接管记录在同一事务完成；该行存在即「本场当前认这台设备」，
-- 事件入账前据此校验来源，防止半完成状态下两台设备的数据都进榜。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_source (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    session_id      BIGINT      NOT NULL COMMENT '场次',
    device_id       BIGINT      NOT NULL COMMENT '当前数据来源设备',
    takeover_id     BIGINT      NULL COMMENT '最近一次接管（首台主设备绑定时为 NULL）',
    switched_at     DATETIME    NOT NULL COMMENT '成为当前来源的时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_session (session_id),
    KEY idx_source_device (device_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '场次当前计时数据来源';


-- ---------------------------- 卡丁车（12 台） ----------------------------
INSERT INTO kart (id, kart_no, model, engine_cc, max_power, status, total_km, total_hours,
                  last_service_date, service_interval_h, updated_at) VALUES
    (1,  'K-01', 'RACE',   390, 22.00, 'AVAILABLE',   1240.50, 310.20, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 4 DAY),  50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (2,  'K-02', 'RACE',   390, 22.00, 'ON_TRACK',    1180.00, 295.60, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 6 DAY),  50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (3,  'K-03', 'FUN',    270, 13.50, 'ON_TRACK',    2260.80, 640.30, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 52 DAY), 60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (4,  'K-04', 'FUN',    270, 13.50, 'ON_TRACK',    2010.20, 580.10, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 3 DAY),  60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (5,  'K-05', 'FUN',    270, 13.50, 'AVAILABLE',   1800.00, 520.00, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 9 DAY),  60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (6,  'K-06', 'KID',    120,  6.50, 'AVAILABLE',    980.40, 410.20, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 2 DAY),  40, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (7,  'K-07', 'KID',    120,  6.50, 'ON_TRACK',     860.00, 380.50, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 14 DAY), 40, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (8,  'K-08', 'DOUBLE', 340, 16.80, 'ON_TRACK',    1520.60, 470.90, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 12 DAY), 50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (9,  'K-09', 'DOUBLE', 340, 16.80, 'AVAILABLE',   1400.00, 430.00, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 4 DAY),  50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (10, 'K-10', 'RACE',   390, 22.00, 'MAINTENANCE', 2600.00, 710.40, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 96 DAY), 50, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (11, 'K-11', 'FUN',    270, 13.50, 'MAINTENANCE', 3050.20, 880.00, DATE_SUB(DATE(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)), INTERVAL 84 DAY), 60, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (12, 'K-12', 'KID',    120,  6.50, 'DISABLED',    3200.00, 990.00, NULL,                                 40, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR));

-- ---------------------------- 车手（12 人） ----------------------------
INSERT INTO driver (id, member_no, name, phone, age_group, license_level, total_sessions,
                    best_lap_ms, total_spend, register_date, session_id) VALUES
    (1,  'M2026001', '陈亦航', '13800000001', 'ADULT', 'ADVANCED', 42, 42350, 12800.00, '2025-03-11', 1),
    (2,  'M2026002', '林骁',   '13800000002', 'ADULT', 'ADVANCED', 36, 42980, 11200.00, '2025-05-02', 1),
    (3,  'M2026003', '赵子昂', '13800000003', 'ADULT', 'MID',      28, 44120,  8600.00, '2025-08-19', 1),
    (4,  'M2026004', '周雨桐', '13800000004', 'TEEN',  'MID',      19, 45260,  6400.00, '2025-11-05', 1),
    (5,  'M2026005', '孙慕青', '13800000005', 'ADULT', 'BASIC',    12, 47890,  3200.00, '2026-01-22', 2),
    (6,  'M2026006', '吴念',   '13800000006', 'TEEN',  'BASIC',     9, 51240,  2600.00, '2026-02-14', 2),
    (7,  'M2026007', '郑一鸣', '13800000007', 'ADULT', 'NONE',      3,     0,   900.00, '2026-06-08', NULL),
    (8,  'M2026008', '何知遥', '13800000008', 'TEEN',  'MID',      15, 46310,  4100.00, '2025-12-30', 1),
    (9,  'M2026009', '小豆丁', '13800000009', 'KID',   'NONE',      6,     0,  1500.00, '2026-04-18', NULL),
    (10, 'M2026010', '糖豆',   '13800000010', 'KID',   'NONE',      4,     0,  1200.00, '2026-05-27', NULL),
    (11, 'M2026011', '顾南',   '13800000011', 'ADULT', 'MID',      22, 44780,  7200.00, '2025-09-16', 1),
    (12, 'M2026012', '李承泽', '13800000012', 'ADULT', 'BASIC',     7, 49050,  2100.00, '2026-03-03', 2);

-- ---------------------------- 场次（10 场，第 1 场进行中） ----------------------------
INSERT INTO race_session (id, session_no, start_time, end_time, track_config, group_code,
                          max_karts, signed_count, status) VALUES
    (1,  'S2026092401', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE), NULL,                                 'FULL',  'A', 12, 8,  'RUNNING'),
    (2,  'S2026092402', DATE_ADD(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE), NULL,                                 'HALF',  'B',  8, 5,  'PENDING'),
    (3,  'S2026092303', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), INTERVAL 20 MINUTE), 'FULL',  'A', 12, 12, 'FINISHED'),
    (4,  'S2026092302', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 26 HOUR), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 25 HOUR),    'HALF',  'C',  8,  8, 'FINISHED'),
    (5,  'S2026092201', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY), INTERVAL 25 MINUTE), 'NIGHT', 'A', 10, 10, 'FINISHED'),
    (6,  'S2026092101', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY), INTERVAL 23 MINUTE), 'FULL',  'B', 12, 11, 'FINISHED'),
    (7,  'S2026092001', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY), INTERVAL 17 MINUTE), 'HALF',  'A',  8,  7, 'FINISHED'),
    (8,  'S2026091901', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY), INTERVAL 22 MINUTE), 'FULL',  'C', 12,  9, 'FINISHED'),
    (9,  'S2026091801', NULL,                              NULL,                                 'NIGHT', 'B', 10,  0, 'CANCELED'),
    (10, 'S2026091701', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY),   DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY), INTERVAL 15 MINUTE),  'HALF',  'C',  8,  6, 'FINISHED');

-- ---------------------------- 圈速记录（第 1 场进行中，其余为历史场） ----------------------------
INSERT INTO lap_record (id, session_id, driver_id, kart_id, lap_index, lap_time_ms, is_fastest, valid, created_at) VALUES
    (1,  1, 1,  2, 1, 44120, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 13 MINUTE)),
    (2,  1, 2,  3, 1, 43780, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE)),
    (3,  1, 3,  4, 1, 45010, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE)),
    (4,  1, 8,  4, 1, 46890, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE)),
    (5,  1, 11, 8, 1, 45260, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE)),
    (6,  1, 4,  3, 1, 46040, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 MINUTE)),
    (7,  1, 1,  2, 2, 43050, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 MINUTE)),
    (8,  1, 2,  3, 2, 43310, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE)),
    (9,  1, 3,  4, 2, 44580, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE)),
    (10, 1, 11, 8, 2, 44970, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE)),
    (11, 1, 8,  4, 2, 55230, 0, 'OUT',   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE)),
    (12, 1, 4,  3, 2, 61240, 0, 'CRASH', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE)),
    (13, 1, 2,  3, 3, 42980, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE)),
    (14, 1, 1,  2, 3, 43410, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE)),
    (15, 3, 1,  2, 1, 42350, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (16, 3, 2,  3, 1, 43020, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (17, 3, 11, 8, 1, 44780, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (18, 3, 5,  4, 1, 47890, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (19, 4, 3,  5, 1, 45210, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 26 HOUR)),
    (20, 4, 6,  6, 1, 51240, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 26 HOUR)),
    (21, 5, 1,  2, 1, 44180, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (22, 5, 4,  3, 1, 46230, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (23, 5, 12, 9, 1, 49050, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (24, 6, 2,  3, 1, 43360, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY)),
    (25, 6, 8,  7, 1, 46310, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY)),
    (26, 7, 5,  4, 1, 48210, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY)),
    (27, 7, 6,  6, 1, 52180, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY)),
    (28, 8, 3,  5, 1, 45990, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY)),
    (29, 8, 12, 9, 1, 49670, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY)),
    (30, 10, 1, 2, 1, 43980, 1, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY)),
    (31, 10, 4,  3, 1, 46820, 0, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY)),
    (32, 10, 9,  6, 1, 62410, 0, 'OUT',   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 DAY));

-- ---------------------------- 维修保养（12 单） ----------------------------
INSERT INTO maintenance (id, order_no, kart_id, maint_type, fault_desc, work_hours, parts_fee,
                         total_fee, worker, start_time, end_time, status) VALUES
    (1,  'MT-20260901', 1,  'OIL',    '按保养间隔更换机油与机滤',              1.00,  180.00,  260.00, '王师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 DAY), 'DONE'),
    (2,  'MT-20260902', 9,  'BRAKE',  '刹车片厚度不足 2mm',                    2.00,  320.00,  480.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 DAY), 'DONE'),
    (3,  'MT-20260903', 6,  'TIRE',   '左前胎鼓包，已换新胎',                  1.00,  340.00,  420.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY), 'DONE'),
    (4,  'MT-20260904', 5,  'BODY',   '车头护板撞裂，钣金修复并补漆',          3.00,  420.00,  660.00, '张师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 DAY), 'DONE'),
    (5,  'MT-20260905', 3,  'TIRE',   '后轮磨损到极限，已换胎',                1.00,  340.00,  420.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 DAY), 'DONE'),
    (6,  'MT-20260906', 10, 'ENGINE', '发动机异响，二缸压缩比偏低，正在大修',  6.50, 2380.00, 2900.00, '王师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 90 MINUTE), NULL, 'REPAIRING'),
    (7,  'MT-20260907', 11, 'TIRE',   '后轮磨损到极限，正在换胎',              1.50,  680.00,  800.00, '李师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE), NULL, 'REPAIRING'),
    (8,  'MT-20260908', 12, 'BODY',   '车身多处开裂，钣金修复中',              3.00,  420.00,  660.00, '张师傅', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE), NULL, 'REPAIRING'),
    (9,  'MT-20260909', 1,  'SPARK',  '火花塞积碳，冷启动困难',                1.00,   90.00,  170.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING'),
    (10, 'MT-20260910', 5,  'BODY',   '前保险杠撞击变形，需钣金修复',          3.00,  420.00,  660.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING'),
    (11, 'MT-20260911', 6,  'BRAKE',  '刹车踏板行程偏长，需排气换片',          2.00,  300.00,  460.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING'),
    (12, 'MT-20260912', 9,  'CHAIN',  '链条异响，待本场结束后进厂',            1.50,  160.00,  280.00, NULL,     DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), NULL, 'WAITING');

-- ---------------------------- 计时设备（8 台：主/备/离线/波动/隔离/历史主备） ----------------------------
-- 第 1 场 S2026092401（FULL，RUNNING）：TD-FULL-A 主设备在线、TD-FULL-B 备用在线；
-- TD-FULL-C 波动、TD-FULL-D 离线（演示心跳状态）；TD-FULL-E 被主管隔离（演示隔离）。
-- 第 3 场（FINISHED）历史主备 TD-OLD-A / TD-OLD-B 各保留一行，接管时间线可回看。
INSERT INTO timing_device (id, device_code, name, track_code, role, session_id,
                           last_heartbeat_at, last_hb_seq, hb_gap_ms,
                           quarantined, quarantine_reason, quarantined_by, quarantined_at,
                           enabled, version, created_at) VALUES
    (1, 'TD-FULL-A', '全场主计时门 A', 'FULL', 'PRIMARY', 1,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 SECOND),  154,  3000,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE)),
    (2, 'TD-FULL-B', '全场备用计时门 B', 'FULL', 'STANDBY', 1,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 SECOND),  151,  3000,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE)),
    (3, 'TD-FULL-C', '全场检测点 C（波动）', 'FULL', 'PRIMARY', 1,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 16 SECOND),  88, 14000,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE)),
    (4, 'TD-FULL-D', '全场检测点 D（离线）', 'FULL', 'PRIMARY', 1,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  203,  3000,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE)),
    (5, 'TD-FULL-E', '全场检测点 E（隔离）', 'FULL', 'PRIMARY', 1,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 SECOND),  132,  3000,
     1, '上报序号多次跳变，待现场核查', '王主管', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),
     1, 3, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE)),
    (6, 'TD-HALF-A', '半场主计时门',     'HALF', 'PRIMARY', NULL, NULL, NULL, NULL,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY)),
    (7, 'TD-OLD-A',  '全场旧主计时门（第3场）', 'FULL', 'PRIMARY', NULL,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),  999, 3000,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY)),
    (8, 'TD-OLD-B',  '全场备用门（第3场已接管）', 'FULL', 'STANDBY', NULL,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),  990, 3000,
     0, NULL, NULL, NULL, 1, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY));

-- 设备×场次水位：第 1 场主设备已确认到 14（与 14 条种子圈速对应），备用尚未接管（边界 NULL）；
-- 第 3 场旧主边界 2、备用从 3 开始（演示一场比赛内的来源切换）。
INSERT INTO timing_device_session (id, device_id, session_id, confirmed_seq, boundary_seq, bound_at) VALUES
    (1, 1, 1, 14, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE)),
    (2, 2, 1,  0, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE)),
    (3, 3, 1,  9, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE)),
    (4, 4, 1, 12, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE)),
    (5, 5, 1, 10, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE)),
    (6, 7, 3,  2, 2,    DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY)),
    (7, 8, 3,  4, 2,    DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY));

-- 心跳种子：每台设备 3 条；A/B/E 稳定 3s，C 最近一次间隔 14s（波动），D 最后心跳在 6 分钟前（离线）。
INSERT INTO timing_heartbeat (id, device_id, session_id, hb_seq, occurred_at, received_at, gap_ms) VALUES
    (1,  1, 1, 152, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 SECOND),  3000),
    (2,  1, 1, 153, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 SECOND),  3000),
    (3,  1, 1, 154, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 SECOND),  3000),
    (4,  2, 1, 149, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 SECOND), 3000),
    (5,  2, 1, 150, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 SECOND),  3000),
    (6,  2, 1, 151, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 SECOND),  3000),
    (7,  3, 1, 86,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 44 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 44 SECOND), 3000),
    (8,  3, 1, 87,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 30 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 30 SECOND), 14000),
    (9,  3, 1, 88,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 16 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 16 SECOND), 14000),
    (10, 4, 1, 201, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE), 3000),
    (11, 4, 1, 202, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 363 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 363 SECOND), 3000),
    (12, 4, 1, 203, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE), 3000),
    (13, 5, 1, 130, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 SECOND), 3000),
    (14, 5, 1, 131, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 SECOND),  3000),
    (15, 5, 1, 132, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 SECOND),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 SECOND),  3000),
    (16, 7, 3, 997, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86406 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86406 SECOND), 3000),
    (17, 7, 3, 998, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86403 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86403 SECOND), 3000),
    (18, 7, 3, 999, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), 3000),
    (19, 8, 3, 988, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86406 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86406 SECOND), 3000),
    (20, 8, 3, 989, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86403 SECOND), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 86403 SECOND), 3000),
    (21, 8, 3, 990, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), 3000);

-- 当前数据来源：第 1 场认主门 A；第 3 场已切到备用门 B（历史回看）。
INSERT INTO timing_source (id, session_id, device_id, takeover_id, switched_at) VALUES
    (1, 1, 1, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE)),
    (2, 3, 8, 1,    DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 10 MINUTE);

-- 第 3 场一次已完成接管：旧主 TD-OLD-A 负责到边界序号 2，之后认备用 TD-OLD-B。
INSERT INTO timing_takeover (id, takeover_no, session_id, old_device_id, new_device_id, boundary_seq,
                             boundary_at, status, reason, requested_by, requested_at,
                             executed_by, executed_at, exec_note) VALUES
    (1, 'TO-S3-1', 3, 7, 8, 2,
     DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), INTERVAL 10 MINUTE),
     'DONE', '主计时门 A 触发回路故障，读数中断', '张值班',
     DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), INTERVAL 9 MINUTE),
     '王主管', DATE_ADD(DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), INTERVAL 10 MINUTE), NULL);

-- 第 1 场主门 A 的 14 条事件与 14 条种子圈速 1:1（重放/去重/序号链演示的基线）。
INSERT INTO timing_lap_event (id, event_uid, device_id, session_id, driver_id, kart_id, seq,
                              lap_time_ms, valid, occurred_at, received_at, source_role,
                              stage, lap_id, gap_id, quarantine_reason, quarantine_note,
                              decided_by, decided_at) VALUES
    (1,  'TD-FULL-A-E001', 1, 1, 1,  2, 1,  44120, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 13 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 13 MINUTE), 'PRIMARY', 'ACCEPTED', 1,  NULL, NULL, NULL, NULL, NULL),
    (2,  'TD-FULL-A-E002', 1, 1, 2,  3, 2,  43780, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE), 'PRIMARY', 'ACCEPTED', 2,  NULL, NULL, NULL, NULL, NULL),
    (3,  'TD-FULL-A-E003', 1, 1, 3,  4, 3,  45010, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE), 'PRIMARY', 'ACCEPTED', 3,  NULL, NULL, NULL, NULL, NULL),
    (4,  'TD-FULL-A-E004', 1, 1, 8,  4, 4,  46890, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE), 'PRIMARY', 'ACCEPTED', 4,  NULL, NULL, NULL, NULL, NULL),
    (5,  'TD-FULL-A-E005', 1, 1, 11, 8, 5,  45260, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE), 'PRIMARY', 'ACCEPTED', 5,  NULL, NULL, NULL, NULL, NULL),
    (6,  'TD-FULL-A-E006', 1, 1, 4,  3, 6,  46040, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 MINUTE), 'PRIMARY', 'ACCEPTED', 6,  NULL, NULL, NULL, NULL, NULL),
    (7,  'TD-FULL-A-E007', 1, 1, 1,  2, 7,  43050, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 MINUTE),  'PRIMARY', 'ACCEPTED', 7,  NULL, NULL, NULL, NULL, NULL),
    (8,  'TD-FULL-A-E008', 1, 1, 2,  3, 8,  43310, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),  'PRIMARY', 'ACCEPTED', 8,  NULL, NULL, NULL, NULL, NULL),
    (9,  'TD-FULL-A-E009', 1, 1, 3,  4, 9,  44580, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),  'PRIMARY', 'ACCEPTED', 9,  NULL, NULL, NULL, NULL, NULL),
    (10, 'TD-FULL-A-E010', 1, 1, 11, 8, 10, 44970, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE),  'PRIMARY', 'ACCEPTED', 10, NULL, NULL, NULL, NULL, NULL),
    (11, 'TD-FULL-A-E011', 1, 1, 8,  4, 11, 55230, 'OUT',   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  'PRIMARY', 'ACCEPTED', 11, NULL, NULL, NULL, NULL, NULL),
    (12, 'TD-FULL-A-E012', 1, 1, 4,  3, 12, 61240, 'CRASH', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  'PRIMARY', 'ACCEPTED', 12, NULL, NULL, NULL, NULL, NULL),
    (13, 'TD-FULL-A-E013', 1, 1, 2,  3, 13, 42980, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE),  'PRIMARY', 'ACCEPTED', 13, NULL, NULL, NULL, NULL, NULL),
    (14, 'TD-FULL-A-E014', 1, 1, 1,  2, 14, 43410, 'VALID', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE),  'PRIMARY', 'ACCEPTED', 14, NULL, NULL, NULL, NULL, NULL);

-- 第 3 场接管边界两侧各两条事件：旧主 1-2 正常、备用 3-4 正常；
-- 旧主晚到的边界后事件 1 条进隔离区（未处置），另 1 条已被主管丢弃（演示隔离区处置）。
INSERT INTO timing_lap_event (id, event_uid, device_id, session_id, driver_id, kart_id, seq,
                              lap_time_ms, valid, occurred_at, received_at, source_role,
                              stage, lap_id, gap_id, quarantine_reason, quarantine_note,
                              decided_by, decided_at) VALUES
    (15, 'TD-OLD-A-E001', 7, 3, 1, 2, 1, 42350, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 2 MINUTE,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 2 MINUTE,
     'PRIMARY', 'ACCEPTED', 15, NULL, NULL, NULL, NULL, NULL),
    (16, 'TD-OLD-A-E002', 7, 3, 2, 3, 2, 43020, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 8 MINUTE,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 8 MINUTE,
     'PRIMARY', 'ACCEPTED', 16, NULL, NULL, NULL, NULL, NULL),
    (17, 'TD-OLD-B-E003', 8, 3, 11, 8, 3, 44780, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 12 MINUTE,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 12 MINUTE,
     'STANDBY', 'ACCEPTED', 17, NULL, NULL, NULL, NULL, NULL),
    (18, 'TD-OLD-B-E004', 8, 3, 5, 4, 4, 47890, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 16 MINUTE,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 16 MINUTE,
     'STANDBY', 'ACCEPTED', 18, NULL, NULL, NULL, NULL, NULL),
    (19, 'TD-OLD-A-E003', 7, 3, 2, 3, 3, 42880, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 13 MINUTE,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 23 HOUR),
     'PRIMARY', 'QUARANTINED', NULL, NULL, 'OLD_AFTER_BOUNDARY',
     '旧主设备晚到的边界后圈速（产生于接管时刻之后），未入本场成绩', NULL, NULL),
    (20, 'TD-OLD-A-E004', 7, 3, 2, 3, 4, 43110, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY) + INTERVAL 17 MINUTE,
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 23 HOUR),
     'PRIMARY', 'DISCARDED', NULL, NULL, 'OLD_AFTER_BOUNDARY',
     '旧主设备晚到的边界后圈速，主管核对原始触发记录后丢弃', '王主管',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 23 HOUR) + INTERVAL 10 MINUTE);

-- 设备告警种子：离线设备 D 一条待确认告警。
INSERT INTO timing_alert (id, device_id, session_id, type, message, status, raised_at,
                          acked_by, acked_at, dedup_key) VALUES
    (1, 4, 1, 'OFFLINE', '全场检测点 D（离线）已超过 30 秒无心跳，判定离线', 'ACTIVE',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 330 SECOND),
     NULL, NULL, 'dev4:OFFLINE'),
    (2, 1, 1, 'GAP', '全场主计时门 A 圈速序号缺失 15~15，#16、#17 已保留为待对账，等待设备断线重放补齐', 'ACTIVE',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 MINUTE),
     NULL, NULL, 'dev1:GAP');

-- 当前主设备 A 的断线场景：已确认到 #14，#15 缺失（OPEN 缺口），#16/#17 已先到但只能挂待对账。
INSERT INTO timing_gap (id, device_id, session_id, missing_from, missing_to, status, opened_at,
                        filled_at, pending_count) VALUES
    (1, 1, 1, 15, 15, 'OPEN',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 MINUTE), NULL, 2);

INSERT INTO timing_lap_event (id, event_uid, device_id, session_id, driver_id, kart_id, seq,
                              lap_time_ms, valid, occurred_at, received_at, source_role,
                              stage, lap_id, gap_id, quarantine_reason, quarantine_note,
                              decided_by, decided_at) VALUES
    (21, 'TD-FULL-A-E016', 1, 1, 2, 3, 16, 43120, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 MINUTE),
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 MINUTE),
     'PRIMARY', 'PENDING', NULL, 1, NULL, NULL, NULL, NULL),
    (22, 'TD-FULL-A-E017', 1, 1, 1, 2, 17, 42860, 'VALID',
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 150 SECOND),
     DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 MINUTE),
     'PRIMARY', 'PENDING', NULL, 1, NULL, NULL, NULL, NULL);

SET FOREIGN_KEY_CHECKS = 1;
