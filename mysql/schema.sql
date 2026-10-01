-- ============================================================================
-- 卡丁车赛道运营管理系统 · 建表 + 种子数据
-- 库名 kart_track，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 八个业务模块：
--   1. kart         卡丁车（车号·车型·排量·功率·状态·累计里程/小时·保养间隔）
--   2. driver       车手（会员号·年龄分组·驾照等级·累计场次·历史最快圈速）
--   3. race_session 场次（赛道配置·分组·最大车辆数·已报名人数·状态）
--   4. lap_record   圈速记录（写入走实体上的 @SQLInsert/@SQLUpdate/@SQLDelete 手写 SQL）
--   5. maintenance  维修保养（工时·配件费·总费用·待修/维修中/已完工）
--   6. red_flag_event 红旗事件（原因·发生时刻·生效中/已恢复/已终止；同场次同时只允许一个生效中）
--   7. red_flag_check 红旗车辆核查（可继续 / 退出比赛 / 需要维修，每车一条、互不影响）
--   8. red_flag_lap   红旗期间圈速隔离（1:1 挂 lap_record：待主管裁定 / 已确认有效 / 已作废）
--   9. timing_device  计时设备韧性台（设备身份/心跳/圈速事件顺序校验/断线补偿/主备接管边界/隔离区）
--       timing_device      计时设备稳定身份（设备号唯一，明确关联赛道与当前场次，带隔离标记与乐观锁版本号）
--       device_heartbeat 心跳流水（设备侧序号 + 服务端受理结果，未关联/已结束/隔离都有明确结果）
--       device_stream    设备在某场次的顺序进度（已连续确认序号 / 已见最大序号），补偿断点保存在这里
--       lap_event       设备侧圈速事件（设备侧唯一标识 + 递增序号 + 实际产生时刻；(设备,事件标识) 唯一去重）
--       device_gap     序号缺口区间（开放/已补齐，时间线展示用）
--       device_takeover 主备接管边界（边界时刻+边界序号；生效接管同场次唯一，原子收口）
--       device_alert     韧性台告警（缺口/波动/离线/接管请求/隔离，值班人员确认后冻结）
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

DROP TABLE IF EXISTS device_alert;
DROP TABLE IF EXISTS device_takeover;
DROP TABLE IF EXISTS device_gap;
DROP TABLE IF EXISTS lap_event;
DROP TABLE IF EXISTS device_stream;
DROP TABLE IF EXISTS device_heartbeat;
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
    current_source_device_id BIGINT NULL COMMENT '当前计时数据来源设备（韧性台主备接管边界生效时原子切换）',
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
-- 模块九-1：计时设备（稳定身份，明确关联的赛道与当前场次）
--
-- 设备号 device_no 是设备的稳定身份（不是 IP、不是会话 token，重连不变）；
-- track_config 是设备安装时归属的赛道配置，只有赛道匹配的场次才能关联；
-- session_id 为当前关联场次：NULL = 未关联（自由设备）。
-- 同一设备同一时刻只能关联一场；一场只允许一台 PRIMARY + 一台 BACKUP（应用层校验 + uk_session_role 兜底）。
-- quarantined = 主管隔离：隔离设备的上报一律拒绝并留在本场成绩之外，心跳也不再更新在线状态，
-- 由主管在韧性台查明原因后解除；version 是乐观锁版本号，任何人工状态变更都 +1，过期页面/重复操作带旧版本直接拒绝。
-- ----------------------------------------------------------------------------
CREATE TABLE timing_device (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    device_no       VARCHAR(24) NOT NULL COMMENT '设备稳定编号，如 TD-M1',
    name            VARCHAR(40) NOT NULL COMMENT '设备名称/安装位置，如主看台龙门架',
    role            VARCHAR(16) NOT NULL COMMENT 'PRIMARY 主设备 / BACKUP 备用设备',
    track_config    VARCHAR(16) NOT NULL COMMENT '归属赛道：HALF/FULL/NIGHT',
    session_id      BIGINT      NULL COMMENT '当前关联赛次',
    quarantined     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否被主管隔离',
    quarantine_reason VARCHAR(200) NULL COMMENT '隔离原因',
    heartbeat_jitter TINYINT(1) NOT NULL DEFAULT 0 COMMENT '最近是否出现心跳抖动（连续两次序号跳跃）',
    last_heartbeat_at DATETIME   NULL COMMENT '最后一次心跳时刻',
    last_heartbeat_seq BIGINT    NULL COMMENT '最近心跳携带的设备侧序号',
    version         BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号：人工状态变更自增，过期页面不得覆盖新状态',
    created_at      DATETIME    NULL,
    updated_at      DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_timing_device_no (device_no),
    UNIQUE KEY uk_device_session_role (session_id, role),
    KEY idx_device_session (session_id),
    KEY idx_device_quarantine (quarantined)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备';

-- ----------------------------------------------------------------------------
-- 模块九-2：心跳流水（每次心跳一行，受理与否都有明确结果，便于事后回看）
-- accepted=1 更新设备最后心跳；accepted=0 记录原因（未关联本场/场次已结束/已隔离）。
-- ----------------------------------------------------------------------------
CREATE TABLE device_heartbeat (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    device_id    BIGINT      NOT NULL COMMENT '设备',
    session_id    BIGINT      NULL COMMENT '心跳时设备关联的场次（快照）',
    seq_no       BIGINT       NOT NULL COMMENT '设备侧单调序号',
    received_at  DATETIME    NOT NULL COMMENT '服务端收到时刻',
    accepted      TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '是否受理（否则不更新设备在线状态）',
    reject_reason VARCHAR(60) NULL COMMENT '不受理原因：NOT_BOUND/SESSION_ENDED/QUARANTINED',
    PRIMARY KEY (id),
    KEY idx_hb_device_time (device_id, received_at),
    KEY idx_hb_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备心跳流水';

-- ----------------------------------------------------------------------------
-- 模块九-3：设备 × 场次顺序进度（断线补偿的断点保存在这里）
--
-- confirmed_seq = 已连续确认到的序号（1..confirmed_seq 全部落账且去重）；
-- observed_seq = 已见到的最大序号（可能是断线重放批量到达的远端序号）。
-- 缺口之后的事件只停留在 lap_event 待对账，confirmed_seq 不前进；
-- 补偿按序补齐，每次重放都从已确认进度继续，已确认进度不受再次中断影响、绝不从头重放。
-- ----------------------------------------------------------------------------
CREATE TABLE device_stream (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    session_id         BIGINT      NOT NULL COMMENT '场次',
    device_id          BIGINT      NOT NULL COMMENT '设备',
    device_role        VARCHAR(16)  NOT NULL COMMENT '绑定时设备角色快照 PRIMARY/BACKUP',
    confirmed_seq        BIGINT      NOT NULL DEFAULT 0 COMMENT '已连续确认序号（补偿断点）',
    observed_seq         BIGINT      NOT NULL DEFAULT 0 COMMENT '已见最大序号',
    boundary_after_seq   BIGINT      NULL COMMENT '接管边界序号：仅边界前数据被承认（主设备流）',
    lap_count           INT         NOT NULL DEFAULT 0 COMMENT '该流已确认圈速条数',
    created_at          DATETIME    NULL,
    updated_at          DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stream_session_device (session_id, device_id),
    KEY idx_stream_device (device_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '设备场次顺序进度';

-- ----------------------------------------------------------------------------
-- 模块九-4：设备侧圈速事件
--
-- 每条事件必须携带 event_uid（设备侧唯一标识）、seq_no（递增序号）、occurred_at（实际产生时刻）。
-- uk_event_device_uid 从数据库层兜底：同一事件无论重放多少次只落一行、只能形成一条圈速记录。
-- status：CONFIRMED 已确认入正常计圈链（1:1 lap_id）；PENDING 缺口之后待对账，不入榜不刷最佳；
--          QUARANTINED 主备边界违规/序号倒退，进隔离区由主管裁定；VOID 主管已丢弃。
-- red_flag=1 表示确认时落在红旗窗口：照常留底但挂红旗待裁定，不能绕过红旗规则直接入榜。
-- ----------------------------------------------------------------------------
CREATE TABLE lap_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    session_id     BIGINT       NOT NULL COMMENT '上报场次',
    device_id      BIGINT       NOT NULL COMMENT '上报设备',
    event_uid      VARCHAR(48)  NOT NULL COMMENT '设备侧事件唯一标识（重放幂等键）',
    seq_no         BIGINT       NOT NULL COMMENT '设备侧递增序号',
    occurred_at     DATETIME     NOT NULL COMMENT '圈速实际产生时刻（设备时钟）',
    driver_id      BIGINT       NOT NULL COMMENT '车手（上报快照）',
    kart_id        BIGINT       NOT NULL COMMENT '车辆（上报快照）',
    lap_time_ms     INT          NOT NULL COMMENT '圈速毫秒',
    valid           VARCHAR(16)  NOT NULL COMMENT 'VALID/OUT/CRASH（设备原值，隔离区不改判）',
    status           VARCHAR(16)  NOT NULL COMMENT 'CONFIRMED/PENDING/QUARANTINED/VOID',
    quarantine_reason VARCHAR(60)    NULL COMMENT '进入隔离区原因',
    lap_id          BIGINT       NULL COMMENT '确认后形成的圈速记录（1:1）',
    red_flag         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '确认时是否处于红旗窗口',
    red_flag_event_id BIGINT       NULL COMMENT '挂接的红旗事件',
    replay_count      INT          NOT NULL DEFAULT 1 COMMENT '重放次数（首次上报=0，重放自增）',
    ruled_by        VARCHAR(60)  NULL COMMENT '隔离区裁定人（主管）',
    ruled_at        DATETIME     NULL,
    created_at       DATETIME     NULL COMMENT '落库时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_device_uid (device_id, event_uid),
    KEY idx_event_session_status (session_id, status),
    KEY idx_event_stream (session_id, device_id, seq_no),
    KEY idx_event_lap (lap_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '设备侧圈速事件';

-- 模块九-5：序号缺口区间（发现缺口登记，按序补齐后关闭；时间线展示缺口范围）
CREATE TABLE device_gap (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id   BIGINT      NOT NULL,
    device_id    BIGINT      NOT NULL,
    from_seq      BIGINT      NOT NULL COMMENT '缺口起点序号（缺失的第一个序号）',
    to_seq        BIGINT      NOT NULL COMMENT '缺口终点序号（缺失的最后一个序号）',
    status        VARCHAR(16) NOT NULL COMMENT 'OPEN 缺口未补 / FILLED 已按序补齐',
    opened_at     DATETIME    NOT NULL COMMENT '发现时刻',
    filled_at     DATETIME    NULL COMMENT '补齐时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gap_open (session_id, device_id, from_seq),
    KEY idx_gap_device_status (device_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '设备序号缺口区间';

-- ----------------------------------------------------------------------------
-- 模块九-6：主备接管记录（只有主管可指定；接管边界时刻+边界序号）
--
-- boundary_at 之前只认主设备、之后只认备用：旧主设备晚到的边界后事件、备用设备提前产生的
-- 边界前事件一律进隔离区。active_key 生成列 + uk_active_takeover 兜底：
-- 同场次同时只能有一个 EFFECTIVE 接管，任何关联变化失败都整体回滚，不会出现两台设备同时当来源。
-- ----------------------------------------------------------------------------
CREATE TABLE device_takeover (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    session_id           BIGINT      NOT NULL,
    primary_device_id     BIGINT      NOT NULL COMMENT '原主设备',
    backup_device_id      BIGINT      NOT NULL COMMENT '接管的备用设备',
    boundary_seq         BIGINT      NOT NULL COMMENT '边界序号：主设备该序号（含）之前认主，之后认备',
    boundary_at          DATETIME    NOT NULL COMMENT '边界时刻（事件产生时刻以此切分）',
    status               VARCHAR(16) NOT NULL COMMENT 'REQUESTED 值班人员已发起/APPROVED 主管已批准/REJECTED 主管驳回/CANCELED 发起人撤回',
    requested_by         VARCHAR(60) NOT NULL COMMENT '发起人（值班人员）',
    requested_at         DATETIME    NOT NULL,
    request_version      BIGINT      NULL COMMENT '发起时设备状态版本（过期页面保护）',
    decided_by            VARCHAR(60) NULL COMMENT '审批主管',
    decided_at           DATETIME    NULL,
    reject_reason        VARCHAR(200) NULL,
    active_key           BIGINT GENERATED ALWAYS AS (CASE WHEN status = 'APPROVED' THEN session_id ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_active_takeover (active_key),
    KEY idx_takeover_session (session_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '主备接管记录';

-- ----------------------------------------------------------------------------
-- 模块九-7：韧性台告警（值班人员可确认；确认即冻结，重复异常不产生新告警）
-- type：GAP 序号缺口 / JUMP 序号倒退 / FLAPPING 心跳波动 / OFFLINE 心跳超时离线
--       TAKEOVER_REQ 接管请求待主管审批 / QUARANTINED 设备被隔离。
-- ----------------------------------------------------------------------------
CREATE TABLE device_alert (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    session_id   BIGINT      NULL COMMENT '关联场次（设备未关联时可为空）',
    device_id    BIGINT      NOT NULL,
    type         VARCHAR(20) NOT NULL COMMENT 'GAP/JUMP/FLAPPING/OFFLINE/TAKEOVER_REQ/QUARANTINED',
    content      VARCHAR(200) NOT NULL COMMENT '告警内容',
    status       VARCHAR(16) NOT NULL COMMENT 'ACTIVE 待确认 / ACK 已确认',
    created_at   DATETIME    NOT NULL,
    ack_by       VARCHAR(60) NULL COMMENT '确认人（值班人员）',
    ack_at       DATETIME    NULL,
    dedup_key    VARCHAR(120) NULL COMMENT '活跃告警去重键：同键只保留一条 ACTIVE',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_dedup (dedup_key),
    KEY idx_alert_status (status),
    KEY idx_alert_session (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '计时设备韧性台告警';


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

-- ---------------------------- 计时设备（6 台，第 1 场正在用的主/备 + 缺口演示） ----------------------------
-- TD-M1：本场主来源，心跳刚到、14 条圈序连续确认 1..14，15 缺失，16/17 重放回来待对账
-- TD-B1：本场备用设备，在线，尚未接管
-- TD-M2：未关联任何场次（自由设备），离线展示
-- TD-M3：已被主管隔离（演示隔离态）
-- TD-H1：半场主设备，关联第 2 场待开赛
-- TD-H2：夜间主设备，未关联
INSERT INTO timing_device (id, device_no, name, role, track_config, session_id, quarantined, quarantine_reason,
                         heartbeat_jitter, last_heartbeat_at, last_heartbeat_seq, version, created_at, updated_at) VALUES
    (1, 'TD-M1', '主看台龙门架·主计时', 'PRIMARY', 'FULL',  1, 0, NULL, 0,
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 SECOND),  17, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE), DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (2, 'TD-B1', '副看台地感环·备用计时', 'BACKUP', 'FULL',  1, 0, NULL, 0,
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 SECOND), 17, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 MINUTE), DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR)),
    (3, 'TD-M2', '维修间备件主机',       'PRIMARY', 'FULL', NULL, 0, NULL, 0,
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 HOUR),   8, 1, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 DAY),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 HOUR)),
    (4, 'TD-M3', '三号弯旧龙门架（故障待检）', 'PRIMARY', 'FULL', 1, 1, '连续上报与备用设备矛盾的圈速，主管先行隔离待查', 0,
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 MINUTE),  9, 1, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE)),
    (5, 'TD-H1', '半场龙门架·主计时',   'PRIMARY', 'HALF',  2, 0, NULL, 0,
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 18 MINUTE),  0, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 18 MINUTE)),
    (6, 'TD-H2', '夜间灯光龙门·主计时', 'PRIMARY', 'NIGHT', NULL, 0, NULL, 0,
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY),    0, 0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY),  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 DAY));

-- 场次当前数据来源：第 1 场认 TD-M1，第 2 场认 TD-H1
UPDATE race_session SET current_source_device_id = 1 WHERE id = 1;
UPDATE race_session SET current_source_device_id = 5 WHERE id = 2;

-- ---------------------------- 设备×场次顺序进度 ----------------------------
INSERT INTO device_stream (id, session_id, device_id, device_role, confirmed_seq, observed_seq, boundary_after_seq, lap_count, created_at, updated_at) VALUES
    (1, 1, 1, 'PRIMARY', 14, 17, NULL, 14, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE)),
    (2, 1, 2, 'BACKUP',   0,  0, NULL,  0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 14 MINUTE), DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 SECOND)),
    (3, 1, 4, 'PRIMARY',  0,  9, NULL,  0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE)),
    (4, 2, 5, 'PRIMARY',  0,  0, NULL,  0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 DAY),   DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 18 MINUTE));

-- 缺口：TD-M1 本场缺序号 15（16/17 已重放回来，仍在等对账）
INSERT INTO device_gap (id, session_id, device_id, from_seq, to_seq, status, opened_at, filled_at) VALUES
    (1, 1, 1, 15, 15, 'OPEN', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE), NULL);

-- ---------------------------- 设备侧圈速事件（与 lap_record 1..14 一一对应；16/17 待对账） ----------------------------
INSERT INTO lap_event (id, session_id, device_id, event_uid, seq_no, occurred_at, driver_id, kart_id, lap_time_ms,
                     valid, status, quarantine_reason, lap_id, red_flag, red_flag_event_id, replay_count, ruled_by, ruled_at, created_at) VALUES
    (1,  1, 1, 'TD-M1-0001', 1,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 13 MINUTE), 1,  2, 44120, 'VALID', 'CONFIRMED', NULL, 1,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 13 MINUTE)),
    (2,  1, 1, 'TD-M1-0002', 2,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE), 2,  3, 43780, 'VALID', 'CONFIRMED', NULL, 2,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE)),
    (3,  1, 1, 'TD-M1-0003', 3,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE), 3,  4, 45010, 'VALID', 'CONFIRMED', NULL, 3,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 12 MINUTE)),
    (4,  1, 1, 'TD-M1-0004', 4,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE), 8,  4, 46890, 'VALID', 'CONFIRMED', NULL, 4,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE)),
    (5,  1, 1, 'TD-M1-0005', 5,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE), 11, 8, 45260, 'VALID', 'CONFIRMED', NULL, 5,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 11 MINUTE)),
    (6,  1, 1, 'TD-M1-0006', 6,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 MINUTE), 4,  3, 46040, 'VALID', 'CONFIRMED', NULL, 6,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 10 MINUTE)),
    (7,  1, 1, 'TD-M1-0007', 7,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 MINUTE),  1,  2, 43050, 'VALID', 'CONFIRMED', NULL, 7,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 9 MINUTE)),
    (8,  1, 1, 'TD-M1-0008', 8,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),  2,  3, 43310, 'VALID', 'CONFIRMED', NULL, 8,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE)),
    (9,  1, 1, 'TD-M1-0009', 9,  DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE),  3,  4, 44580, 'VALID', 'CONFIRMED', NULL, 9,  0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 MINUTE)),
    (10, 1, 1, 'TD-M1-0010', 10, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE), 11,  8, 44970, 'VALID', 'CONFIRMED', NULL, 10, 0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 7 MINUTE)),
    (11, 1, 1, 'TD-M1-0011', 11, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  8,  4, 55230, 'OUT',   'CONFIRMED', NULL, 11, 0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE)),
    (12, 1, 1, 'TD-M1-0012', 12, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE),  4,  3, 61240, 'CRASH', 'CONFIRMED', NULL, 12, 0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 6 MINUTE)),
    (13, 1, 1, 'TD-M1-0013', 13, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE),  2,  3, 42980, 'VALID', 'CONFIRMED', NULL, 13, 0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE)),
    (14, 1, 1, 'TD-M1-0014', 14, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE),  1,  2, 43410, 'VALID', 'CONFIRMED', NULL, 14, 0, NULL, 0, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 4 MINUTE)),
    -- 断线期间 15 丢失；设备恢复后批量重放 16/17，缺口未补齐，保留为待对账，绝不提前入榜
    (15, 1, 1, 'TD-M1-0016', 16, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 90 SECOND), 3,  4, 44360, 'VALID', 'PENDING', NULL, NULL, 0, NULL, 1, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 MINUTE)),
    (16, 1, 1, 'TD-M1-0017', 17, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 70 SECOND), 11, 8, 46120, 'VALID', 'PENDING',    NULL, NULL, 0, NULL, 1, NULL, NULL, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 1 MINUTE));

-- 心跳流水（各留近几条用于时间线回看）
INSERT INTO device_heartbeat (device_id, session_id, seq_no, received_at, accepted, reject_reason) VALUES
    (1, 1, 14, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 24 SECOND), 1, NULL),
    (1, 1, 15, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 16 SECOND), 1, NULL),
    (1, 1, 17, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 SECOND),  1, NULL),
    (2, 1, 17, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 8 SECOND),  1, NULL),
    (2, 1, 12, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 32 SECOND), 1, NULL),
    (2, 1, 14, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 20 SECOND), 1, NULL),
    (4, 1,  8, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 MINUTE),  0, 'QUARANTINED'),
    (4, 1,  9, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 3 MINUTE),  0, 'QUARANTINED'),
    (5, 2,  0, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 18 MINUTE), 1, NULL),
    (3, NULL, 8, DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 2 HOUR),   0, 'NOT_BOUND');

-- 活跃告警：TD-M1 缺口 15 待值班人员确认；TD-M3 隔离告警（主管操作留底，已确认）
INSERT INTO device_alert (session_id, device_id, type, content, status, created_at, ack_by, ack_at, dedup_key) VALUES
    (1, 1, 'GAP', 'TD-M1 序号 15 缺失，之后的圈速已挂起待对账，实时排行榜已暂停采用该段数据', 'ACTIVE',
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 5 MINUTE), NULL, NULL, 'GAP:1:1:15'),
    (1, 4, 'QUARANTINED', 'TD-M3 已被主管隔离：连续上报与备用设备矛盾的圈速，主管先行隔离待查', 'ACK',
        DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 40 MINUTE), '王主管', DATE_SUB(DATE_ADD(UTC_TIMESTAMP(), INTERVAL 8 HOUR), INTERVAL 38 MINUTE), NULL);

SET FOREIGN_KEY_CHECKS = 1;
