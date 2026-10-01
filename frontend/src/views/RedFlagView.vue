<template>
  <div>
    <!-- 处置台头部：场次状态 + 红旗发起入口 -->
    <div class="rf-hero" :class="{ 'is-active': consoleData.redFlagActive }">
      <div class="rf-hero-main">
        <div class="rf-hero-label">
          <span v-if="consoleData.redFlagActive" class="rf-flag">🚩 红旗处理中</span>
          <span v-else>当前场次 · {{ consoleData.sessionStatusName || '—' }}</span>
        </div>
        <div class="rf-hero-session">
          {{ consoleData.sessionNo || '暂无场次' }}
          <el-tag v-if="consoleData.redFlagActive" type="danger" effect="dark" size="small" style="margin-left: 8px">
            正常圈速已暂停入榜
          </el-tag>
        </div>
      </div>
      <div class="rf-hero-actions">
        <template v-if="consoleData.redFlagActive">
          <el-tag type="danger" effect="plain">生效事件 {{ active.eventNo }}</el-tag>
          <el-button type="primary" :disabled="!isSupervisor" @click="goResume">
            恢复比赛
          </el-button>
          <el-popconfirm
            title="强制终止将立即结束本场、释放车手与可用车辆（退出/送修车保留状态），确定？"
            confirm-button-text="强制终止"
            cancel-button-text="再想想"
            @confirm="goTerminate"
          >
            <template #reference>
              <el-button type="danger" plain :disabled="!isSupervisor">强制终止</el-button>
            </template>
          </el-popconfirm>
          <span v-if="!isSupervisor" class="rf-hint">恢复 / 终止仅主管可执行</span>
        </template>
        <template v-else>
          <el-button type="danger" size="large" :disabled="consoleData.sessionStatus !== 'RUNNING'"
                     @click="openRaise">
            🚩 发起红旗
          </el-button>
          <span v-if="consoleData.sessionStatus !== 'RUNNING'" class="rf-hint">
            仅「进行中」的场次可以发起红旗
          </span>
        </template>
      </div>
    </div>

    <!-- 生效红旗事件一屏概览 -->
    <template v-if="active">
      <div class="panel rf-panel">
        <div class="panel-head">
          <span class="panel-title">红旗事件 {{ active.eventNo }}</span>
          <span class="panel-extra">每 3 秒自动刷新 · 操作人：{{ operatorDisplay }}</span>
        </div>

        <div class="rf-meta">
          <div class="rf-meta-item rf-meta-reason">
            <span class="rf-meta-label">红旗原因</span>
            <b>{{ active.reason }}</b>
          </div>
          <div class="rf-meta-item">
            <span class="rf-meta-label">发生时刻</span>
            <b>{{ fmtTime(active.occurredAt) }}</b>
          </div>
          <div class="rf-meta-item">
            <span class="rf-meta-label">持续时间</span>
            <b class="rf-duration">{{ durationText }}</b>
          </div>
          <div class="rf-meta-item">
            <span class="rf-meta-label">发起人</span>
            <b>{{ active.raisedBy }}</b>
          </div>
        </div>

        <!-- 处置进度：四张进度卡 -->
        <div class="sum-grid" style="margin-top: 12px">
          <div class="sum-card accent">
            <div class="label">车辆核查</div>
            <div class="value">{{ active.checkedCount }}/{{ active.totalChecks }}</div>
          </div>
          <div class="sum-card">
            <div class="label">可继续 · 退出 · 送修</div>
            <div class="value" style="font-size: 18px">
              {{ active.continueCount }} · {{ active.retireCount }} · {{ active.repairCount }}
            </div>
          </div>
          <div class="sum-card" :class="{ accent: active.pendingLapCount > 0 }">
            <div class="label">待裁定圈速</div>
            <div class="value">{{ active.pendingLapCount }}</div>
          </div>
          <div class="sum-card">
            <div class="label">已确认有效 · 已作废</div>
            <div class="value" style="font-size: 18px">
              {{ active.confirmedLapCount }} · {{ active.voidLapCount }}
            </div>
          </div>
        </div>

        <el-alert
          style="margin-top: 12px"
          :closable="false"
          :type="active.resumable ? 'success' : 'info'"
          :title="resumeHint"
          show-icon
        />
      </div>

      <div class="cols">
        <!-- 车辆核查清单 -->
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">受影响车辆核查</span>
            <span class="panel-extra">以红旗发起时仍在赛的车辆生成，每车独立结论、提交即冻结</span>
          </div>
          <el-table :data="active.checks" size="small" stripe>
            <el-table-column label="车号" width="80">
              <template #default="{ row }"><b>{{ row.kartNo }}</b></template>
            </el-table-column>
            <el-table-column prop="kartModelName" label="车型" width="84" />
            <el-table-column prop="driverName" label="车手" width="92">
              <template #default="{ row }">{{ row.driverName || '—' }}</template>
            </el-table-column>
            <el-table-column label="当前车况" width="92">
              <template #default="{ row }">
                <span class="tag" :class="kartStatusClass(row.kartStatus)">{{ row.kartStatusName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="核查结论" width="100">
              <template #default="{ row }">
                <span v-if="row.checked" class="tag" :class="conclusionClass(row.conclusion)">
                  {{ row.conclusionName }}
                </span>
                <span v-else class="tag warn">待核查</span>
              </template>
            </el-table-column>
            <el-table-column label="维修单 / 备注" min-width="150">
              <template #default="{ row }">
                <div v-if="row.maintOrderNo">
                  <el-link type="warning" @click="goMaintenance">🔧 {{ row.maintOrderNo }}（待修）</el-link>
                </div>
                <div style="color: var(--kt-text-sub); font-size: 12px">{{ row.remark || '—' }}</div>
                <div v-if="row.checked" style="font-size: 11px; color: #90a4ae">
                  {{ row.checkedBy }} · {{ fmtTime(row.checkedAt) }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <template v-if="!row.checked">
                  <el-button-group>
                    <el-button size="small" type="success" :disabled="!isStaff"
                               @click="openCheck(row, 'CONTINUE')">可继续</el-button>
                    <el-button size="small" type="info" :disabled="!isStaff"
                               @click="openCheck(row, 'RETIRE')">退出</el-button>
                    <el-button size="small" type="warning" :disabled="!isStaff"
                               @click="openCheck(row, 'REPAIR')">送修</el-button>
                  </el-button-group>
                  <div v-if="!isStaff" class="rf-op-hint">仅值班人员可核查</div>
                </template>
                <span v-else class="tag mute">结论已锁定</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 红旗期间圈速裁定 -->
        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">红旗期间圈速（计时隔离）</span>
            <span class="panel-extra">不丢弃、不入榜；恢复前由主管逐条裁定</span>
          </div>
          <el-table :data="active.laps" size="small" stripe max-height="420">
            <el-table-column prop="lapIndex" label="圈" width="46" />
            <el-table-column prop="driverName" label="车手" width="84" />
            <el-table-column prop="kartNo" label="车号" width="64" />
            <el-table-column label="圈速" width="100">
              <template #default="{ row }"><b>{{ row.lapTimeText }}</b></template>
            </el-table-column>
            <el-table-column label="设备判定" width="88">
              <template #default="{ row }">
                <span class="tag" :class="row.valid === 'VALID' ? 'ok' : 'warn'">{{ row.validName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="裁定" width="92">
              <template #default="{ row }">
                <span class="tag" :class="rulingClass(row.ruling)">{{ row.rulingName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="主管操作" width="150">
              <template #default="{ row }">
                <template v-if="row.ruling === 'PENDING'">
                  <el-button link type="success" :disabled="!isSupervisor"
                             @click="rule(row, 'CONFIRMED')">确认有效</el-button>
                  <el-button link type="danger" :disabled="!isSupervisor"
                             @click="rule(row, 'VOID')">作废</el-button>
                  <div v-if="!isSupervisor" class="rf-op-hint">仅主管可裁定</div>
                </template>
                <span v-else style="font-size: 11px; color: #90a4ae">
                  {{ row.ruledBy }} · {{ fmtTime(row.ruledAt) }}
                </span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!active.laps.length" class="empty">本次红旗期间暂无计时设备上报圈速</div>
        </div>
      </div>
    </template>

    <!-- 非红旗状态：空态提示 + 本场历史 -->
    <div v-else class="panel">
      <div class="panel-head"><span class="panel-title">处置台</span></div>
      <div class="empty">
        本场当前无生效中的红旗事件。<br />
        进行中场次可由值班人员点击右上角「发起红旗」；发起后系统会暂停正常圈速入榜并自动生成待核查车辆清单。
      </div>
    </div>

    <!-- 本场历史红旗事件（场次详情回看） -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">本场历史红旗事件</span>
        <span class="panel-extra">共 {{ history.length }} 次</span>
      </div>
      <el-table :data="history" size="small" stripe>
        <el-table-column prop="eventNo" label="事件编号" width="170" />
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="tag" :class="row.active ? 'danger' : (row.status === 'RESUMED' ? 'ok' : 'mute')">
              {{ row.statusName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="raisedBy" label="发起人" width="90" />
        <el-table-column label="发生时刻" width="170">
          <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="持续" width="80">
          <template #default="{ row }">{{ row.durationText }}</template>
        </el-table-column>
        <el-table-column label="核查" width="90">
          <template #default="{ row }">{{ row.checkedCount }}/{{ row.totalChecks }}</template>
        </el-table-column>
        <el-table-column label="圈速裁定" width="120">
          <template #default="{ row }">
            确认 {{ row.confirmedLapCount }} / 作废 {{ row.voidLapCount }}
            <span v-if="row.pendingLapCount"> / 待裁 {{ row.pendingLapCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="最终处置" width="150">
          <template #default="{ row }">
            <template v-if="row.finalAction">
              <b :class="row.finalAction === '恢复比赛' ? 'rf-text-ok' : 'rf-text-danger'">
                {{ row.finalAction }}
              </b>
              <div style="font-size: 11px; color: #90a4ae">
                {{ row.finalActionBy }} · {{ fmtTime(row.finalActionAt) }}
              </div>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 发起红旗 -->
    <el-dialog v-model="raiseDialog" title="🚩 发起红旗" width="460px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px"
        title="发起后：场次立即标为红旗处理中、正常圈速暂停入榜，并以当前仍在赛车辆生成待核查清单。同场次重复发起将返回同一事件。" />
      <el-form label-width="80px">
        <el-form-item label="场次">
          <el-tag>{{ consoleData.sessionNo }}</el-tag>
        </el-form-item>
        <el-form-item label="红旗原因" required>
          <el-input v-model="raiseReason" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="例如：3 号车在 6 号弯碰撞护墙，赛道有碎片，需要清场" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="raiseDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitRaise">确认发起红旗</el-button>
      </template>
    </el-dialog>

    <!-- 车辆核查 -->
    <el-dialog v-model="checkDialog" :title="`车辆核查 · ${checkForm.kartNo}（${checkForm.conclusionName}）`" width="460px">
      <el-alert :type="checkForm.conclusion === 'REPAIR' ? 'warning' : 'info'" :closable="false" show-icon
                style="margin-bottom: 12px" :title="checkAlert" />
      <el-form label-width="80px">
        <el-form-item label="车手">
          <el-tag>{{ checkForm.driverName || '—' }}</el-tag>
        </el-form-item>
        <el-form-item :label="checkForm.conclusion === 'REPAIR' ? '故障描述' : '备注'">
          <el-input v-model="checkForm.remark" type="textarea" :rows="3" maxlength="200" show-word-limit
                    :placeholder="checkForm.conclusion === 'REPAIR'
                      ? '说明故障现象，将自动生成待修维修单，车辆立即转入维修状态'
                      : '可选：记录现场情况'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="checkDialog = false">取消</el-button>
        <el-button :type="checkBtnType" :loading="submitting" @click="submitCheck">提交核查结论</el-button>
      </template>
    </el-dialog>

    <!-- 历史事件详情（只读回看） -->
    <el-dialog v-model="detailDialog" :title="`红旗事件 ${detail.eventNo}`" width="860px" top="6vh">
      <div class="rf-meta">
        <div class="rf-meta-item rf-meta-reason">
          <span class="rf-meta-label">原因</span><b>{{ detail.reason }}</b>
        </div>
        <div class="rf-meta-item">
          <span class="rf-meta-label">发生时刻</span><b>{{ fmtTime(detail.occurredAt) }}</b>
        </div>
        <div class="rf-meta-item">
          <span class="rf-meta-label">持续</span><b>{{ detail.durationText }}</b>
        </div>
        <div class="rf-meta-item">
          <span class="rf-meta-label">最终处置</span><b>{{ detail.finalAction || '—' }}</b>
        </div>
      </div>
      <el-descriptions :column="4" border size="small" style="margin-top: 10px">
        <el-descriptions-item label="发起人">{{ detail.raisedBy }}</el-descriptions-item>
        <el-descriptions-item label="可继续/退出/送修">
          {{ detail.continueCount }} / {{ detail.retireCount }} / {{ detail.repairCount }}
        </el-descriptions-item>
        <el-descriptions-item label="确认有效">{{ detail.confirmedLapCount }}</el-descriptions-item>
        <el-descriptions-item label="作废">{{ detail.voidLapCount }}</el-descriptions-item>
      </el-descriptions>
      <h4 style="margin: 12px 0 6px">车辆核查记录</h4>
      <el-table :data="detail.checks" size="small" border>
        <el-table-column prop="kartNo" label="车号" width="72" />
        <el-table-column prop="driverName" label="车手" width="90" />
        <el-table-column label="结论" width="90">
          <template #default="{ row }">
            <span class="tag" :class="conclusionClass(row.conclusion)">{{ row.conclusionName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="维修单" width="150">
          <template #default="{ row }">{{ row.maintOrderNo || '—' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" />
        <el-table-column label="核查人/时间" width="170">
          <template #default="{ row }">
            {{ row.checkedBy || '—' }}<br />{{ row.checkedAt ? fmtTime(row.checkedAt) : '' }}
          </template>
        </el-table-column>
      </el-table>
      <h4 style="margin: 12px 0 6px">红旗期间圈速裁定</h4>
      <el-table :data="detail.laps" size="small" border max-height="260">
        <el-table-column prop="lapIndex" label="圈" width="48" />
        <el-table-column prop="driverName" label="车手" width="90" />
        <el-table-column prop="kartNo" label="车号" width="70" />
        <el-table-column prop="lapTimeText" label="圈速" width="100" />
        <el-table-column label="设备判定" width="92">
          <template #default="{ row }">{{ row.validName }}</template>
        </el-table-column>
        <el-table-column label="裁定" width="92">
          <template #default="{ row }">
            <span class="tag" :class="rulingClass(row.ruling)">{{ row.rulingName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="裁定人/时间" min-width="170">
          <template #default="{ row }">
            {{ row.ruledBy || '—' }}<br />{{ row.ruledAt ? fmtTime(row.ruledAt) : '' }}
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="detailDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { redFlagApi } from '../api'

const nav = useRouter()
const track = inject('track')

const emptyConsole = { sessionId: null, sessionNo: '', sessionStatus: '', sessionStatusName: '',
  redFlagActive: false, activeEvent: null, history: [] }
const consoleData = ref({ ...emptyConsole })
const raiseDialog = ref(false)
const raiseReason = ref('')
const checkDialog = ref(false)
const checkForm = ref({})
const detailDialog = ref(false)
const detail = ref({ checks: [], laps: {} })
const submitting = ref(false)
const nowTs = ref(Date.now())
let tickId = null
let pollId = null

const active = computed(() => consoleData.value.activeEvent)
const history = computed(() => consoleData.value.history || [])
const isSupervisor = computed(() => track.operatorRole.value === 'SUPERVISOR')
const isStaff = computed(() => track.operatorRole.value === 'STAFF' || isSupervisor.value)
const operatorDisplay = computed(() =>
  `${track.operatorId.value || '未选择'}（${isSupervisor.value ? '主管' : '值班人员'}）`)

const durationText = computed(() => {
  if (!active.value) return '00:00'
  const base = active.value.durationSec || 0
  const extra = active.value.active ? Math.floor((nowTs.value - Date.parse(active.value.occurredAt)) / 1000) - base : 0
  return fmtDuration(Math.max(0, base + Math.max(0, extra)))
})

const resumeHint = computed(() => {
  if (!active.value) return ''
  const miss = []
  if (!active.value.allChecked) {
    miss.push(`还有 ${active.value.totalChecks - active.value.checkedCount} 台车未核查`)
  }
  if (!active.value.allRuled) {
    miss.push(`还有 ${active.value.pendingLapCount} 条圈速未裁定`)
  }
  if (!miss.length) {
    return isSupervisor.value
      ? '全部车辆已核查、红旗期间圈速已裁定，主管可以恢复比赛（恢复后继续记圈，旧记录不会重复入榜）。'
      : '已满足恢复条件，请主管执行恢复比赛。'
  }
  return '恢复比赛还差：' + miss.join('；') + '。'
})

const checkAlert = computed(() => {
  const c = checkForm.value.conclusion
  if (c === 'REPAIR') return '需要维修：车辆立即进入维修状态，并生成一张可追踪的待修维修单。'
  if (c === 'RETIRE') return '退出比赛：该车立即离赛回可用池、车手本场结束；不影响其它车辆的核查结果。'
  return '可继续：车辆留在赛道，恢复比赛后继续记圈。'
})
const checkBtnType = computed(() => {
  const c = checkForm.value.conclusion
  return c === 'REPAIR' ? 'warning' : (c === 'RETIRE' ? 'info' : 'success')
})

/* ---------- 数据 ---------- */
async function load() {
  const data = await redFlagApi.console(track.sessionId.value)
  consoleData.value = data || { ...emptyConsole }
}

function openRaise() {
  if (!isStaff.value) {
    ElMessage.warning('只有值班人员/主管可以发起红旗')
    return
  }
  raiseReason.value = ''
  raiseDialog.value = true
}

async function submitRaise() {
  if (!raiseReason.value.trim()) {
    ElMessage.warning('请填写红旗原因')
    return
  }
  submitting.value = true
  try {
    await redFlagApi.raise(track.sessionId.value, raiseReason.value.trim())
    raiseDialog.value = false
    await load()
    await track.refreshOverview()
    await track.reloadSessions()
  } finally {
    submitting.value = false
  }
}

function openCheck(row, conclusion) {
  checkForm.value = {
    checkId: row.id,
    eventId: row.eventId,
    kartNo: row.kartNo,
    driverName: row.driverName,
    conclusion,
    conclusionName: { CONTINUE: '可继续', RETIRE: '退出比赛', REPAIR: '需要维修' }[conclusion],
    remark: row.remark || ''
  }
  checkDialog.value = true
}

async function submitCheck() {
  submitting.value = true
  try {
    await redFlagApi.submitCheck(checkForm.value.eventId, checkForm.value.checkId, {
      conclusion: checkForm.value.conclusion,
      remark: checkForm.value.remark
    })
    checkDialog.value = false
    await load()
    await track.refreshOverview()
  } finally {
    submitting.value = false
  }
}

async function rule(row, ruling) {
  const action = ruling === 'CONFIRMED' ? '确认为有效' : '作废'
  try {
    await ElMessageBox.confirm(
      ruling === 'CONFIRMED'
        ? `确认该圈有效后将计入本场最快圈与车手历史最好成绩，确定？`
        : `作废后该圈永久留底但不再入榜，确定？`,
      `裁定圈速 · ${row.driverName} ${row.lapTimeText} · ${action}`,
      { confirmButtonText: action, cancelButtonText: '取消', type: ruling === 'CONFIRMED' ? 'success' : 'warning' }
    )
  } catch {
    return
  }
  await redFlagApi.ruleLap(active.value.id, row.id, ruling)
  await load()
  await track.refreshOverview()
}

async function goResume() {
  await redFlagApi.resume(active.value.id)
  await load()
  await track.refreshOverview()
  await track.reloadSessions()
}

async function goTerminate() {
  await redFlagApi.terminate(active.value.id)
  await load()
  await track.refreshOverview()
  await track.reloadSessions()
}

async function openDetail(row) {
  detail.value = await redFlagApi.event(row.id)
  detailDialog.value = true
}

function goMaintenance() {
  nav.push('/maintenance')
}

/* ---------- 展示辅助 ---------- */
function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}
function fmtDuration(sec) {
  const s = Math.max(0, sec || 0)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const r = s % 60
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
    : `${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}`
}
function kartStatusClass(s) {
  if (s === 'ON_TRACK') return 'running'
  if (s === 'MAINTENANCE') return 'warn'
  if (s === 'DISABLED') return 'mute'
  return 'ok'
}
function conclusionClass(c) {
  if (c === 'CONTINUE') return 'ok'
  if (c === 'RETIRE') return 'mute'
  if (c === 'REPAIR') return 'warn'
  return 'mute'
}
function rulingClass(r) {
  if (r === 'CONFIRMED') return 'ok'
  if (r === 'VOID') return 'mute'
  return 'warn'
}

watch(() => track.sessionId.value, load)
onMounted(() => {
  load()
  tickId = setInterval(() => { nowTs.value = Date.now() }, 1000)
  pollId = setInterval(load, 3000)
})
onBeforeUnmount(() => {
  clearInterval(tickId)
  clearInterval(pollId)
})
</script>

<style scoped>
.rf-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px;
  border-radius: 12px;
  background: linear-gradient(120deg, #37474f 0%, #546e7a 100%);
  color: #fff;
  margin-bottom: 16px;
  border: 2px solid transparent;
}
.rf-hero.is-active {
  background: linear-gradient(120deg, #7f0000 0%, #c62828 60%, #ef5350 100%);
  border-color: #ff8a80;
  box-shadow: 0 0 22px rgba(198, 40, 40, 0.35);
}
.rf-hero-label { font-size: 12px; opacity: 0.85; margin-bottom: 4px; }
.rf-flag { font-weight: 700; letter-spacing: 2px; animation: pulse 1.2s infinite; }
.rf-hero-session { font-size: 22px; font-weight: 700; }
.rf-hero-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.rf-hint { font-size: 12px; opacity: 0.85; }
.rf-op-hint { font-size: 11px; color: #b0bec5; margin-top: 2px; }

.rf-panel { border-color: #ffcdd2; }
.rf-meta {
  display: grid;
  grid-template-columns: 2fr 1fr 1fr 1fr;
  gap: 12px;
}
.rf-meta-item {
  background: #f7f9fb;
  border: 1px solid var(--kt-border);
  border-radius: 10px;
  padding: 10px 12px;
}
.rf-meta-label { display: block; font-size: 12px; color: var(--kt-text-sub); margin-bottom: 4px; }
.rf-duration { color: var(--kt-primary-dark); font-family: 'SFMono-Regular', Menlo, Consolas, monospace; }
.rf-text-ok { color: var(--kt-ok); }
.rf-text-danger { color: var(--kt-danger); }
@media (max-width: 1180px) {
  .rf-meta { grid-template-columns: 1fr 1fr; }
}
</style>
