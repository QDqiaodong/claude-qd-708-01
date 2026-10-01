<template>
  <div>
    <!-- 头部：场次 + 当前数据来源 + 状态汇总 -->
    <div class="tm-hero">
      <div class="tm-hero-main">
        <div class="tm-hero-label">计时设备韧性台 · {{ consoleData.sessionNo || '暂无场次' }}</div>
        <div class="tm-hero-row">
          <el-tag size="large" :type="sessionTagType">{{ consoleData.sessionStatusName || '—' }}</el-tag>
          <el-tag v-if="consoleData.redFlagActive" type="danger" effect="dark" size="large">🚩 红旗处理中 · 圈速挂待裁定</el-tag>
          <span class="tm-source">
            当前数据来源：
            <b v-if="source">{{ source.deviceNo }} · {{ source.name }}（{{ source.roleName }}）</b>
            <b v-else class="tm-source-none">未确定</b>
          </span>
        </div>
      </div>
      <div class="tm-hero-actions">
        <el-button @click="openRegister" size="small">＋ 登记设备</el-button>
        <el-button @click="openSimulator" type="primary" size="small">设备模拟上报</el-button>
        <span class="tm-hint">每 3 秒自动刷新 · {{ operatorDisplay }}</span>
      </div>
    </div>

    <!-- 汇总计数 -->
    <div class="sum-grid" style="margin-bottom: 16px">
      <div class="sum-card accent"><div class="label">在线</div><div class="value">{{ summary.onlineCount }}</div></div>
      <div class="sum-card"><div class="label">波动</div><div class="value tm-flapping">{{ summary.flappingCount }}</div></div>
      <div class="sum-card"><div class="label">离线</div><div class="value tm-offline">{{ summary.offlineCount }}</div></div>
      <div class="sum-card"><div class="label">隔离</div><div class="value tm-isolated">{{ summary.isolatedCount }}</div></div>
      <div class="sum-card"><div class="label">未关联</div><div class="value">{{ summary.unboundCount }}</div></div>
      <div class="sum-card" :class="{ accent: summary.openGapCount > 0 }">
        <div class="label">未补缺口</div><div class="value">{{ summary.openGapCount }}</div>
      </div>
      <div class="sum-card" :class="{ accent: summary.pendingEventCount > 0 }">
        <div class="label">待对账圈速</div><div class="value">{{ summary.pendingEventCount }}</div>
      </div>
      <div class="sum-card" :class="{ accent: summary.quarantineEventCount > 0 }">
        <div class="label">隔离区事件</div><div class="value">{{ summary.quarantineEventCount }}</div>
      </div>
      <div class="sum-card" :class="{ accent: summary.takeoverRequestCount > 0 }">
        <div class="label">接管待批</div><div class="value">{{ summary.takeoverRequestCount }}</div>
      </div>
      <div class="sum-card" :class="{ accent: summary.activeAlertCount > 0 }">
        <div class="label">待确认告警</div><div class="value">{{ summary.activeAlertCount }}</div>
      </div>
    </div>

    <el-alert v-if="!consoleData.sessionActive" type="info" :closable="false" show-icon style="margin-bottom: 12px"
      :title="`该场次当前为「${consoleData.sessionStatusName}」：设备上报会被明确拒绝（SESSION_ENDED），历史时间线仍可回看，数据不会混入本场成绩。`" />

    <div class="cols">
      <!-- 左列：设备卡片 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">本场设备（{{ devices.length }}）</span>
          <span class="panel-extra">稳定身份 · 最后心跳 · 已确认序号 / 已见序号 · 数据来源</span>
        </div>
        <div v-if="devices.length" class="tm-device-list">
          <div v-for="d in devices" :key="d.id" class="tm-device-card" :class="deviceCardClass(d)">
            <div class="tm-device-head">
              <span class="tm-device-no">{{ d.deviceNo }}</span>
              <span class="tag" :class="roleClass(d.role)">{{ d.roleName }}</span>
              <span class="tag" :class="hbClass(d.heartbeatStatus)">{{ d.heartbeatStatusName }}</span>
              <el-tag v-if="d.currentSource" type="danger" effect="dark" size="small">当前来源</el-tag>
              <span class="tm-device-age">{{ d.heartbeatAgeText }} · 序号 {{ d.lastHeartbeatSeq ?? '—' }}</span>
            </div>
            <div class="tm-device-name">{{ d.name }} · {{ d.trackConfigName }}赛道 · v{{ d.version }}</div>
            <div v-if="d.quarantined" class="tm-quar-reason">⛔ 隔离原因：{{ d.quarantineReason }}</div>
            <div class="tm-device-metrics">
              <span>已确认序号 <b>{{ d.confirmedSeq }}</b></span>
              <span>已见序号 <b>{{ d.observedSeq }}</b></span>
              <span v-if="d.boundaryAfterSeq != null">接管边界序号 <b>{{ d.boundaryAfterSeq }}</b></span>
              <span>已计圈 <b>{{ d.lapCount }}</b></span>
              <span>待对账 <b :class="{ 'tm-warn': d.pendingEventCount > 0 }">{{ d.pendingEventCount }}</b></span>
              <span>隔离事件 <b :class="{ 'tm-danger': d.quarantineEventCount > 0 }">{{ d.quarantineEventCount }}</b></span>
            </div>
            <div v-if="d.openGapCount" class="tm-gap-line">
              ⚠ 缺口：<b v-for="g in d.gaps" :key="g.id">{{ rangeText(g) }} </b>
            </div>
            <div class="tm-device-ops">
              <el-button v-if="!d.quarantined" link type="warning" size="small" :disabled="!isStaff"
                         @click="openTakeover(d)">请求备用接管</el-button>
              <template v-if="isSupervisor">
                <el-button v-if="!d.quarantined" link type="danger" size="small" @click="openQuarantine(d)">隔离</el-button>
                <el-button v-else link type="success" size="small" @click="release(d)">解除隔离</el-button>
              </template>
              <el-button link type="info" size="small" :disabled="!isStaff || d.currentSource"
                         @click="unbind(d)">解除关联</el-button>
            </div>
            <div v-if="!isStaff" class="tm-op-hint">仅查看；接管/隔离操作需对应权限</div>
          </div>
        </div>
        <div v-else class="empty">本场还没有关联计时设备</div>

        <div class="panel-head" style="margin-top: 14px">
          <span class="panel-title">同赛道未关联设备（{{ unboundDevices.length }}）</span>
          <span class="panel-extra">未关联设备的心跳/事件一律拒绝并留痕</span>
        </div>
        <div v-if="unboundDevices.length" class="tm-device-list">
          <div v-for="d in unboundDevices" :key="d.id" class="tm-device-card is-unbound">
            <div class="tm-device-head">
              <span class="tm-device-no">{{ d.deviceNo }}</span>
              <span class="tag" :class="roleClass(d.role)">{{ d.roleName }}</span>
              <span class="tag mute">{{ d.heartbeatStatusName }}</span>
            </div>
            <div class="tm-device-name">{{ d.name }} · {{ d.trackConfigName }}赛道 · v{{ d.version }}</div>
            <div class="tm-device-ops">
              <el-button link type="primary" size="small" :disabled="!isStaff"
                         @click="bind(d)">关联到本场</el-button>
              <el-button v-if="isSupervisor && !d.quarantined" link type="danger" size="small"
                         @click="openQuarantine(d)">隔离</el-button>
              <el-button v-if="isSupervisor && d.quarantined" link type="success" size="small"
                         @click="release(d)">解除隔离</el-button>
            </div>
          </div>
        </div>
        <div v-else class="empty">没有同赛道的空闲设备</div>
      </div>

      <!-- 右列：告警 + 接管记录 -->
      <div>
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">告警（{{ alerts.length }}）</span>
            <span class="panel-extra">值班人员确认即冻结</span>
          </div>
          <div class="tm-alert-list">
            <div v-for="a in alerts.slice(0, 12)" :key="a.id" class="tm-alert" :class="{ 'is-ack': a.status === 'ACK' }">
              <div class="tm-alert-main">
                <span class="tag" :class="alertClass(a.type)">{{ a.typeName }}</span>
                <span class="tm-alert-device">{{ a.deviceNo }}</span>
                <span class="tm-alert-content">{{ a.content }}</span>
              </div>
              <div class="tm-alert-foot">
                <span>{{ fmtTime(a.createdAt) }}</span>
                <el-button v-if="a.status === 'ACTIVE'" link type="primary" size="small" :disabled="!isStaff"
                           @click="ack(a)">确认</el-button>
                <span v-else class="tm-ack">{{ a.ackBy }} · {{ fmtTime(a.ackAt) }}</span>
              </div>
            </div>
          </div>
          <div v-if="!alerts.length" class="empty">暂无告警</div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">主备接管记录（{{ takeovers.length }}）</span>
            <span class="panel-extra">边界时刻+边界序号，主管审批原子生效</span>
          </div>
          <el-table :data="takeovers" size="small" stripe>
            <el-table-column label="主 → 备" min-width="150">
              <template #default="{ row }">
                <b>{{ row.primaryDeviceNo }}</b> → <b>{{ row.backupDeviceNo }}</b>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="92">
              <template #default="{ row }"><span class="tag" :class="takeoverClass(row.status)">{{ row.statusName }}</span></template>
            </el-table-column>
            <el-table-column label="边界" width="150">
              <template #default="{ row }">
                #{{ row.boundarySeq }}<br />
                <span style="font-size: 11px; color: var(--kt-text-sub)">{{ fmtTime(row.boundaryAt) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="发起/审批" min-width="120">
              <template #default="{ row }">
                {{ row.requestedBy }} 发起
                <div v-if="row.decidedBy" style="font-size: 11px; color: var(--kt-text-sub)">{{ row.decidedBy }} 处理</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <template v-if="row.status === 'REQUESTED'">
                  <el-button link type="success" size="small" :disabled="!isSupervisor" @click="approve(row)">批准接管</el-button>
                  <el-button link type="danger" size="small" :disabled="!isSupervisor" @click="openReject(row)">驳回</el-button>
                  <el-button link type="info" size="small" :disabled="!isStaff" @click="cancelTakeover(row)">撤回</el-button>
                </template>
                <span v-else-if="row.status === 'REJECTED'" style="font-size: 11px">{{ row.rejectReason }}</span>
                <span v-else style="font-size: 11px; color: var(--kt-text-sub)">已闭环</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!takeovers.length" class="empty">本场暂无接管记录</div>
        </div>
      </div>
    </div>

    <!-- 缺口区间 + 待对账 -->
    <div class="cols">
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">序号缺口区间（{{ gaps.length }}）</span>
          <span class="panel-extra">批量重放按序补齐后自动关闭</span>
        </div>
        <el-table :data="gaps" size="small" stripe>
          <el-table-column prop="deviceNo" label="设备" width="100" />
          <el-table-column label="缺失序号范围" width="140">
            <template #default="{ row }"><b>{{ rangeText(row) }}</b></template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="tag" :class="row.status === 'OPEN' ? 'warn' : 'ok'">{{ row.statusName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="发现时刻" width="170">
            <template #default="{ row }">{{ fmtTime(row.openedAt) }}</template>
          </el-table-column>
          <el-table-column label="补齐时刻" min-width="170">
            <template #default="{ row }">{{ row.filledAt ? fmtTime(row.filledAt) : '—' }}</template>
          </el-table-column>
        </el-table>
        <div v-if="!gaps.length" class="empty">没有发现过序号缺口</div>
      </div>

      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">待对账圈速（{{ pendingEvents.length }}）</span>
          <span class="panel-extra">缺口之后的数据，不入实时榜、不刷车手最佳；补齐后自动确认</span>
        </div>
        <el-table :data="pendingEvents" size="small" stripe max-height="260">
          <el-table-column prop="deviceNo" label="设备" width="84" />
          <el-table-column prop="seqNo" label="序号" width="64" />
          <el-table-column prop="driverName" label="车手" width="84" />
          <el-table-column prop="kartNo" label="车号" width="64" />
          <el-table-column prop="lapTimeText" label="圈速" width="100" />
          <el-table-column label="产生时刻" min-width="150">
            <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column label="重放" width="56">
            <template #default="{ row }">{{ row.replayCount }}</template>
          </el-table-column>
        </el-table>
        <div v-if="!pendingEvents.length" class="empty">没有待对账圈速，顺序链路完整</div>
      </div>
    </div>

    <!-- 隔离区 -->
    <div class="panel tm-quar-panel">
      <div class="panel-head">
        <span class="panel-title">隔离区事件（{{ quarantineEvents.length }}）</span>
        <span class="panel-extra">主备边界违规 / 序号倒退；仅主管可丢弃或重新归段</span>
      </div>
      <el-table :data="quarantineEvents" size="small" stripe>
        <el-table-column prop="deviceNo" label="设备" width="90" />
        <el-table-column prop="seqNo" label="序号" width="64" />
        <el-table-column prop="eventUid" label="事件标识" width="130" show-overflow-tooltip />
        <el-table-column prop="driverName" label="车手" width="84" />
        <el-table-column prop="kartNo" label="车号" width="64" />
        <el-table-column prop="lapTimeText" label="圈速" width="100" />
        <el-table-column label="产生时刻" width="160">
          <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="隔离原因" min-width="150">
          <template #default="{ row }"><span class="tag danger">{{ row.quarantineReasonName }}</span></template>
        </el-table-column>
        <el-table-column label="主管操作" width="170">
          <template #default="{ row }">
            <el-button link type="success" size="small" :disabled="!isSupervisor"
                       @click="rule(row, 'RESEGMENT')">重新归段</el-button>
            <el-button link type="danger" size="small" :disabled="!isSupervisor"
                       @click="rule(row, 'DISCARD')">丢弃</el-button>
            <div v-if="!isSupervisor" class="tm-op-hint">仅主管可处理隔离区</div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!quarantineEvents.length" class="empty">隔离区为空：没有越过接管边界或序号倒退的事件</div>
    </div>

    <!-- 时间线 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">本场计时来源时间线</span>
        <span class="panel-extra">心跳 / 缺口 / 接管 / 告警合并回看一次比赛内的完整来源变化</span>
      </div>
      <el-timeline class="tm-timeline">
        <el-timeline-item v-for="(t, i) in timeline.slice(0, 40)" :key="i" :timestamp="fmtTime(t.at)"
                          :type="timelineType(t.tone)" placement="top">
          <span class="tag" :class="timelineTagClass(t.kind)">{{ t.kindName }}</span>
          <b style="margin: 0 6px">{{ t.deviceNo }}</b>{{ t.content }}
        </el-timeline-item>
      </el-timeline>
      <div v-if="!timeline.length" class="empty">暂无时间线事件</div>
    </div>

    <!-- 登记设备 -->
    <el-dialog v-model="registerDialog" title="登记计时设备（稳定身份）" width="460px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="设备编号是设备的稳定身份（不是 IP/会话），断线重连不变；角色与归属赛道安装时确定。" />
      <el-form label-width="92px">
        <el-form-item label="设备编号" required>
          <el-input v-model="registerForm.deviceNo" placeholder="例如 TD-B2" maxlength="24" />
        </el-form-item>
        <el-form-item label="设备名称" required>
          <el-input v-model="registerForm.name" placeholder="例如 备用龙门架·南看台" maxlength="40" />
        </el-form-item>
        <el-form-item label="设备角色">
          <el-radio-group v-model="registerForm.role">
            <el-radio label="PRIMARY">主设备</el-radio>
            <el-radio label="BACKUP">备用设备</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="归属赛道">
          <el-select v-model="registerForm.trackConfig" style="width: 100%">
            <el-option label="半场 HALF" value="HALF" />
            <el-option label="全场 FULL" value="FULL" />
            <el-option label="夜间 NIGHT" value="NIGHT" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog = false">取消</el-button>
        <el-button type="primary" @click="submitRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 设备模拟上报（演示心跳 / 单条事件 / 断线批量重放） -->
    <el-dialog v-model="simDialog" title="设备模拟上报" width="560px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px"
        title="真实环境由设备 SDK 调用这些接口；这里用于演示未关联拒绝、序号缺口、批量重放幂等与主备边界隔离。" />
      <el-form label-width="110px" size="small">
        <el-form-item label="设备编号">
          <el-input v-model="simDevice" placeholder="TD-M1 / TD-B1 / TD-M2 ..." />
        </el-form-item>
        <el-form-item label="心跳序号">
          <el-input-number v-model="simSeq" :min="0" :step="1" />
          <el-button style="margin-left: 10px" @click="sendHeartbeat">发一次心跳</el-button>
        </el-form-item>
        <el-divider style="margin: 8px 0">单条圈速事件（唯一标识+序号+产生时刻）</el-divider>
        <el-form-item label="事件序号">
          <el-input-number v-model="simSeq" :min="1" :step="1" />
        </el-form-item>
        <el-form-item label="事件标识">
          <el-input v-model="simUid" placeholder="留空则按 设备号-序号 生成" />
        </el-form-item>
        <el-form-item label="车手 id / 车 id">
          <el-input-number v-model="simDriver" :min="1" />
          <el-input-number v-model="simKart" :min="1" style="margin-left: 8px" />
        </el-form-item>
        <el-form-item label="圈速毫秒">
          <el-input-number v-model="simLap" :min="20000" :max="300000" :step="100" />
        </el-form-item>
        <el-form-item label="产生时刻偏移">
          <el-input-number v-model="simOccurredOffset" :step="10" /> 秒（负数=过去时刻，用于制造边界前/红旗窗口事件）
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="sendEvent">上报单条事件</el-button>
        </el-form-item>
        <el-divider style="margin: 8px 0">断线恢复批量重放（逐条事务、幂等去重、断点续补）</el-divider>
        <el-form-item label="重放序号列表">
          <el-input v-model="simReplaySeqs" placeholder="例如 15,16,17" />
        </el-form-item>
        <el-form-item>
          <el-button type="success" @click="sendReplay">批量重放</el-button>
        </el-form-item>
      </el-form>
      <div v-if="simResults.length" class="tm-sim-results">
        <div v-for="(r, i) in simResults.slice(0, 10)" :key="i" class="tm-sim-line">
          <span class="tag mute">{{ r.type }}</span> {{ r.text }}
        </div>
      </div>
      <template #footer>
        <el-button @click="simDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { timingApi } from '../api'

const track = inject('track')

const emptyConsole = {
  sessionId: null, sessionNo: '', sessionStatus: '', sessionStatusName: '', sessionActive: false,
  redFlagActive: false, currentSourceDeviceId: null, currentSource: null,
  summary: { onlineCount: 0, flappingCount: 0, offlineCount: 0, isolatedCount: 0, unboundCount: 0,
    activeAlertCount: 0, pendingEventCount: 0, openGapCount: 0, quarantineEventCount: 0, takeoverRequestCount: 0 },
  devices: [], unboundDevices: [], pendingEvents: [], quarantineEvents: [], gaps: [], takeovers: [],
  alerts: [], timeline: []
}
const consoleData = ref({ ...emptyConsole })
let pollId = null

const isSupervisor = computed(() => track.operatorRole.value === 'SUPERVISOR')
const isStaff = computed(() => track.operatorRole.value === 'STAFF' || isSupervisor.value)
const operatorDisplay = computed(() =>
  `${track.operatorId.value || '未选择'}（${isSupervisor.value ? '主管' : '值班人员'}）`)
const summary = computed(() => consoleData.value.summary || {})
const devices = computed(() => consoleData.value.devices || [])
const unboundDevices = computed(() => consoleData.value.unboundDevices || [])
const pendingEvents = computed(() => consoleData.value.pendingEvents || [])
const quarantineEvents = computed(() => consoleData.value.quarantineEvents || [])
const gaps = computed(() => consoleData.value.gaps || [])
const takeovers = computed(() => consoleData.value.takeovers || [])
const alerts = computed(() => consoleData.value.alerts || [])
const timeline = computed(() => consoleData.value.timeline || [])
const source = computed(() => consoleData.value.currentSource)
const sessionTagType = computed(() => {
  const s = consoleData.value.sessionStatus
  if (s === 'RUNNING') return 'success'
  if (s === 'RED_FLAG') return 'danger'
  if (s === 'PENDING') return 'warning'
  return 'info'
})

async function load() {
  try {
    const data = await timingApi.console(track.sessionId.value)
    if (data) consoleData.value = data
  } catch { /* 轮询静默：网络错误已由拦截器提示 */ }
}

/* ---------- 设备 ---------- */
async function bind(d) {
  await timingApi.bind(d.id, consoleData.value.sessionId, d.version)
  await load()
}
async function unbind(d) {
  try {
    await ElMessageBox.confirm(`解除后 ${d.deviceNo} 的上报将被明确拒绝，确定解除与本场的关联？`,
      '解除设备关联', { type: 'warning' })
  } catch { return }
  await timingApi.unbind(d.id, d.version)
  await load()
}

/* ---------- 接管 ---------- */
async function openTakeover(d) {
  // 主设备才需要请求备用接管；找到本场 BACKUP
  const backup = devices.value.find((x) => x.role === 'BACKUP')
  if (!backup) {
    ElMessage.warning('本场没有关联备用设备，无法发起接管')
    return
  }
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('说明主设备故障情况（带给主管审批）',
      `请求由 ${backup.deviceNo} 接管 ${d.deviceNo}`, {
        confirmButtonText: '发起接管请求',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例如：主设备龙门架断电，最近 30 秒无心跳，请求备用接管'
      })
    reason = res.value || ''
  } catch { return }
  await timingApi.requestTakeover(consoleData.value.sessionId, {
    backupDeviceId: backup.id,
    primaryVersion: d.version,
    reason
  })
  await load()
}

async function approve(row) {
  try {
    await ElMessageBox.confirm(
      `批准后立即生效：边界时刻 ${fmtTime(row.boundaryAt)}、边界序号 #${row.boundarySeq} 之前只认 ${row.primaryDeviceNo}，之后只认 ${row.backupDeviceNo}；越界事件自动进隔离区。确定？`,
      '主管批准主备接管', { type: 'warning', confirmButtonText: '批准并切换来源' })
  } catch { return }
  await timingApi.approveTakeover(row.id)
  await load()
  await track.refreshOverview()
}

async function openReject(row) {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt('驳回原因（必填，数据来源维持主设备）', '驳回接管请求', {
      confirmButtonText: '确认驳回',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '例如：备用设备未完成自检，现场已重启主设备恢复'
    })
    reason = (res.value || '').trim()
  } catch { return }
  if (!reason) { ElMessage.warning('请填写驳回原因'); return }
  await timingApi.rejectTakeover(row.id, reason)
  await load()
}

async function cancelTakeover(row) {
  await timingApi.cancelTakeover(row.id)
  await load()
}

/* ---------- 隔离 ---------- */
async function openQuarantine(d) {
  let reason = ''
  try {
    const res = await ElMessageBox.prompt(`隔离后 ${d.deviceNo} 的心跳与圈速都会被明确拒绝、不混入本场。隔离原因：`,
      '隔离计时设备', {
        confirmButtonText: '确认隔离',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例如：连续上报与备用设备矛盾的圈速，待现场核查'
      })
    reason = (res.value || '').trim()
  } catch { return }
  if (!reason) { ElMessage.warning('请填写隔离原因'); return }
  await timingApi.quarantine(d.id, reason, d.version)
  await load()
}

async function release(d) {
  await timingApi.releaseQuarantine(d.id, d.version)
  await load()
}

async function rule(event, action) {
  try {
    await ElMessageBox.confirm(
      action === 'DISCARD'
        ? '丢弃后该事件永久留底但永不入榜，确定？'
        : '重新归段将按接管边界与当前连续序号重新判定：连续则立即入链，仍有缺口则回到待对账。确定？',
      `${event.deviceNo} #${event.seqNo} · ${action === 'DISCARD' ? '丢弃' : '重新归段'}`,
      { type: action === 'DISCARD' ? 'warning' : 'info' })
  } catch { return }
  await timingApi.ruleEvent(event.id, action)
  await load()
  await track.refreshOverview()
}

/* ---------- 告警 ---------- */
async function ack(a) {
  await timingApi.ackAlert(a.id)
  await load()
  await track.refreshOverview()
}

/* ---------- 登记设备 ---------- */
const registerDialog = ref(false)
const registerForm = ref({ deviceNo: '', name: '', role: 'BACKUP', trackConfig: 'FULL' })
function openRegister() {
  registerForm.value = {
    deviceNo: 'TD-' + String(Date.now()).slice(-4),
    name: '',
    role: 'BACKUP',
    trackConfig: consoleData.value.currentSource ? consoleData.value.currentSource.trackConfig : 'FULL'
  }
  registerDialog.value = true
}
async function submitRegister() {
  const f = registerForm.value
  if (!f.deviceNo.trim() || !f.name.trim()) {
    ElMessage.warning('设备编号与名称都要填写')
    return
  }
  await timingApi.register({
    deviceNo: f.deviceNo.trim(),
    name: f.name.trim(),
    role: f.role,
    trackConfig: f.trackConfig
  })
  registerDialog.value = false
  await load()
}

/* ---------- 设备模拟上报 ---------- */
const simDialog = ref(false)
const simDevice = ref('')
const simSeq = ref(18)
const simUid = ref('')
const simDriver = ref(1)
const simKart = ref(2)
const simLap = ref(45000)
const simOccurredOffset = ref(0)
const simReplaySeqs = ref('15,16,17')
const simResults = ref([])

function openSimulator() {
  simDevice.value = (devices.value.find((d) => d.role === 'PRIMARY') || devices.value[0] || {}).deviceNo || 'TD-M1'
  simDialog.value = true
}

function nowIso(offsetSec) {
  const d = new Date(Date.now() + (offsetSec || 0) * 1000)
  return d.toISOString().slice(0, 19)
}

async function sendHeartbeat() {
  const r = await timingApi.heartbeat(simDevice.value, simSeq.value)
  simResults.value.unshift({ type: '心跳', text: r.accepted ? `已受理（${r.heartbeatStatusName}）` : `被拒：${r.rejectReasonName}` })
  await load()
}

async function sendEvent() {
  const uid = simUid.value || `${simDevice.value}-${String(simSeq.value).padStart(4, '0')}`
  const r = await timingApi.ingest(simDevice.value, {
    eventUid: uid,
    seqNo: simSeq.value,
    occurredAt: nowIso(simOccurredOffset.value),
    driverId: simDriver.value,
    kartId: simKart.value,
    lapTimeMs: simLap.value,
    valid: 'VALID'
  })
  simResults.value.unshift({ type: `圈速 #${simSeq.value}`, text: r.message })
  await load()
}

async function sendReplay() {
  const seqs = simReplaySeqs.value.split(',').map((x) => Number(x.trim())).filter((x) => x > 0)
  const events = seqs.map((seq) => ({
    eventUid: `${simDevice.value}-${String(seq).padStart(4, '0')}`,
    seqNo: seq,
    occurredAt: nowIso(simOccurredOffset.value - 30),
    driverId: simDriver.value,
    kartId: simKart.value,
    lapTimeMs: simLap.value,
    valid: 'VALID'
  }))
  const list = await timingApi.replay(simDevice.value, events)
  list.forEach((r, i) => simResults.value.unshift({
    type: `重放 #${seqs[i]}`,
    text: `${r.statusName}${r.quarantineReasonName ? '（' + r.quarantineReasonName + '）' : ''} · 重放${r.replayCount}次 · ${r.message}`
  }))
  await load()
  await track.refreshOverview()
}

/* ---------- 展示辅助 ---------- */
function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}
function rangeText(g) {
  return g.fromSeq === g.toSeq ? `#${g.fromSeq}` : `#${g.fromSeq}..${g.toSeq}`
}
function hbClass(s) {
  if (s === 'ONLINE') return 'ok'
  if (s === 'FLAPPING') return 'warn'
  if (s === 'ISOLATED') return 'danger'
  if (s === 'UNBOUND') return 'mute'
  return 'mute'
}
function roleClass(r) { return r === 'PRIMARY' ? 'running' : 'ok' }
function deviceCardClass(d) {
  if (d.quarantined) return 'is-isolated'
  if (d.heartbeatStatus === 'OFFLINE') return 'is-offline'
  if (d.heartbeatStatus === 'FLAPPING') return 'is-flapping'
  if (d.currentSource) return 'is-source'
  return ''
}
function alertClass(t) {
  if (t === 'OFFLINE' || t === 'GAP' || t === 'QUARANTINED' || t === 'JUMP') return 'danger'
  if (t === 'TAKEOVER_REQ' || t === 'FLAPPING') return 'warn'
  return 'mute'
}
function takeoverClass(s) {
  if (s === 'APPROVED') return 'ok'
  if (s === 'REJECTED') return 'danger'
  if (s === 'CANCELED') return 'mute'
  return 'warn'
}
function timelineType(tone) {
  if (tone === 'danger') return 'danger'
  if (tone === 'warn') return 'warning'
  if (tone === 'ok') return 'success'
  return 'info'
}
function timelineTagClass(kind) {
  if (kind === 'TAKEOVER') return 'ok'
  if (kind === 'GAP') return 'warn'
  if (kind === 'HEARTBEAT') return 'mute'
  return 'danger'
}

watch(() => track.sessionId.value, load)
onMounted(() => {
  load()
  pollId = setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(pollId))
</script>

<style scoped>
.tm-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 20px;
  border-radius: 12px;
  background: linear-gradient(120deg, #0d2c54 0%, #1565c0 65%, #42a5f5 100%);
  color: #fff;
  margin-bottom: 16px;
}
.tm-hero-label { font-size: 12px; opacity: 0.85; margin-bottom: 6px; }
.tm-hero-row { display: flex; align-items: center; gap: 10px; font-size: 16px; flex-wrap: wrap; }
.tm-source { font-size: 14px; }
.tm-source b { color: #ffe082; }
.tm-source-none { color: #ffab91 !important; }
.tm-hero-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; flex-shrink: 0; }
.tm-hint { font-size: 12px; opacity: 0.85; }
.tm-flapping { color: var(--kt-warn); }
.tm-offline { color: #78909c; }
.tm-isolated { color: var(--kt-danger); }
.tm-warn { color: var(--kt-warn); }
.tm-danger { color: var(--kt-danger); }

.tm-device-list { display: flex; flex-direction: column; gap: 10px; }
.tm-device-card {
  border: 1px solid var(--kt-border);
  border-left-width: 4px;
  border-radius: 10px;
  padding: 10px 12px;
  background: #fbfcfd;
}
.tm-device-card.is-source { border-color: var(--kt-primary); border-left-color: var(--kt-primary); background: var(--kt-primary-soft); }
.tm-device-card.is-flapping { border-left-color: var(--kt-warn); }
.tm-device-card.is-offline { border-left-color: #90a4ae; opacity: 0.85; }
.tm-device-card.is-isolated { border-left-color: var(--kt-danger); background: #fff5f5; }
.tm-device-card.is-unbound { border-left-color: #90a4ae; }
.tm-device-head { display: flex; align-items: center; gap: 8px; }
.tm-device-no { font-size: 16px; font-weight: 700; }
.tm-device-age { margin-left: auto; font-size: 12px; color: var(--kt-text-sub); }
.tm-device-name { font-size: 12px; color: var(--kt-text-sub); margin: 4px 0 6px; }
.tm-quar-reason { font-size: 12px; color: var(--kt-danger); margin-bottom: 6px; }
.tm-device-metrics { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 12px; color: var(--kt-text-sub); }
.tm-device-metrics b { color: var(--kt-text); font-family: 'SFMono-Regular', Menlo, Consolas, monospace; }
.tm-gap-line { margin-top: 6px; font-size: 12px; color: var(--kt-warn); }
.tm-device-ops { margin-top: 6px; display: flex; gap: 6px; }
.tm-op-hint { font-size: 11px; color: #b0bec5; }

.tm-alert-list { display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow-y: auto; }
.tm-alert { border: 1px solid var(--kt-border); border-radius: 8px; padding: 8px 10px; background: #fff; }
.tm-alert-main { display: flex; align-items: center; gap: 8px; font-size: 13px; flex-wrap: wrap; }
.tm-alert-device { font-weight: 700; }
.tm-alert-content { color: var(--kt-text); }
.tm-alert-foot { display: flex; justify-content: space-between; align-items: center; margin-top: 4px; font-size: 11px; color: var(--kt-text-sub); }
.tm-alert.is-ack { opacity: 0.6; background: #f7f9fb; }
.tm-ack { color: var(--kt-ok); }

.tm-quar-panel { border-color: #ffcdd2; }
.tm-timeline { padding-left: 4px; max-height: 520px; overflow-y: auto; }
.tm-sim-results { margin-top: 8px; border-top: 1px dashed var(--kt-border); padding-top: 8px; max-height: 180px; overflow-y: auto; }
.tm-sim-line { font-size: 12px; padding: 3px 0; display: flex; gap: 8px; align-items: center; }
</style>
