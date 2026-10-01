<template>
  <div>
    <!-- 大计时器 + 场次信息条 -->
    <div class="timer-wrap" :class="{ 'is-redflag': session && session.status === 'RED_FLAG' }">
      <div>
        <div class="timer-label">
          <span v-if="session && session.status === 'RUNNING'" class="live-dot"></span>
          <span v-if="session && session.status === 'RED_FLAG'" class="rf-blink">🚩 红旗处理中 · 正常圈速已暂停入榜，上报圈速将隔离待裁定</span>
          <span v-else>本场已进行时长 · {{ session ? session.statusName : '暂无场次' }}</span>
        </div>
        <div class="big-timer">{{ timerText }}<span class="ms">.{{ tenths }}</span></div>
      </div>
      <div class="session-bar">
        <span class="kv">场次号<b>{{ session ? session.sessionNo : '—' }}</b></span>
        <span class="kv">赛道<b>{{ session ? session.trackConfigName : '—' }}</b></span>
        <span class="kv">分组<b>{{ session ? session.groupCode + ' 组' : '—' }}</b></span>
        <span class="kv">报名<b>{{ session ? session.signedCount + '/' + session.maxKarts : '—' }}</b></span>
        <span class="kv">已完成圈数<b>{{ lapCount }}</b></span>
        <span class="kv">本场最快<b>{{ fastestText }}</b></span>
      </div>
      <div style="margin-left: auto; display: flex; gap: 8px; align-items: center">
        <el-button v-if="session && session.redFlagActive" type="warning" @click="goRedFlag">前往红旗处置台</el-button>
        <el-button type="danger" @click="openLapDialog">＋ 登记圈速</el-button>
      </div>
    </div>

    <div class="cols">
      <!-- 左：赛道俯视图 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">赛道俯视图</span>
          <span class="panel-extra">{{ dots.length }} 台车在图上 · 按是否在赛着色</span>
        </div>
        <div class="track-box">
          <svg class="track-svg" viewBox="0 0 900 470">
            <!-- 内场底色 -->
            <path :d="TRACK_D" fill="#e9eff4" stroke="none" />
            <!-- 红白路肩（虚线描边露出路面两侧） -->
            <path :d="TRACK_D" fill="none" stroke="#e53935" stroke-width="70"
                  stroke-dasharray="20 20" stroke-linejoin="round" stroke-linecap="butt" />
            <!-- 两条同心路径填色 = 沥青面（外沿深色 + 主路面） -->
            <path :d="TRACK_D" fill="none" stroke="#3b4148" stroke-width="60" stroke-linejoin="round" />
            <path :d="TRACK_D" fill="none" stroke="#5a616b" stroke-width="50" stroke-linejoin="round" />
            <!-- 中心虚线 -->
            <path :d="TRACK_D" fill="none" stroke="#f3f6f8" stroke-width="3"
                  stroke-dasharray="14 16" opacity="0.9" />
            <path ref="pathRef" :d="TRACK_D" fill="none" stroke="none" />

            <!-- 起终点格子旗（10 格） -->
            <g :transform="startFlagTransform">
              <rect v-for="(c, i) in checkerCells" :key="i" :x="c.x" :y="c.y" width="13" height="10" :fill="c.fill" />
            </g>
            <text :x="140" :y="205" font-size="13" font-weight="700" fill="#b71c1c">START / FINISH</text>

            <!-- 小车点位 -->
            <g v-for="d in dotPos" :key="d.kartId">
              <circle :cx="d.x" :cy="d.y" r="11" :fill="dotColor(d.status)" stroke="#fff" stroke-width="2" />
              <text :x="d.x" :y="d.y + 3.5" text-anchor="middle" class="kart-dot-label">{{ shortNo(d.kartNo) }}</text>
              <text :x="d.x" :y="d.y - 15" text-anchor="middle" font-size="10" fill="#37474f">{{ d.driverName }}</text>
            </g>

            <!-- 维修区 -->
            <g>
              <rect x="380" y="215" width="150" height="42" rx="6" fill="#fff" stroke="#c5ced8" />
              <text x="455" y="241" text-anchor="middle" font-size="12" fill="#6b7684">PIT 维修区</text>
            </g>
          </svg>
        </div>
        <div style="margin-top: 8px; font-size: 12px; color: #6b7684">
          <span class="tag running">在赛</span>
          <span class="tag ok" style="margin-left: 6px">可用</span>
          <span class="tag warn" style="margin-left: 6px">维修</span>
          <span class="tag mute" style="margin-left: 6px">停用</span>
        </div>
      </div>

      <!-- 右：实时圈速排行榜 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">实时圈速排行榜</span>
          <span class="panel-extra">每 5 秒自动重排</span>
        </div>
        <div v-if="leaderboard.length" class="board">
          <div v-for="row in leaderboard" :key="row.driverName" class="board-row">
            <span class="board-fill" :class="fillClass(row.rank)" :style="{ width: barWidth(row.rank) }"></span>
            <span class="board-rank" :class="fillClass(row.rank)">{{ row.rank }}</span>
            <span class="board-name">{{ row.driverName }}</span>
            <span class="board-kart">{{ row.kartNo }}</span>
            <span class="board-count">{{ row.lapCount }} 圈</span>
            <span class="board-lap">{{ row.lapText }}</span>
            <span class="board-delta">{{ row.deltaText }}</span>
          </div>
        </div>
        <div v-else class="empty">本场还没有有效圈速，先派车再登记圈速</div>
      </div>
    </div>

    <!-- 圈速流水 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">本场圈速流水</span>
        <span class="panel-extra">写入走 @SQLInsert 手写 SQL</span>
      </div>
      <el-table :data="laps" size="small" stripe>
        <el-table-column prop="lapIndex" label="圈序" width="70" />
        <el-table-column prop="driverName" label="车手" width="100" />
        <el-table-column prop="kartNo" label="车号" width="80" />
        <el-table-column label="圈速" width="110">
          <template #default="{ row }"><b>{{ row.lapTimeText }}</b></template>
        </el-table-column>
        <el-table-column label="判定" width="120">
          <template #default="{ row }">
            <span class="tag" :class="row.valid === 'VALID' ? 'ok' : 'warn'">{{ row.validName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="红旗隔离" width="118">
          <template #default="{ row }">
            <span v-if="row.redFlagLap" class="tag" :class="row.ruling === 'CONFIRMED' ? 'ok' : (row.ruling === 'VOID' ? 'mute' : 'danger')">
              🚩 {{ row.rulingName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="本场最快" width="100">
          <template #default="{ row }">
            <span v-if="row.fastest" class="tag danger">🏁 最快圈</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="记录时间" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <template v-if="row.redFlagLap">
              <el-button link type="warning" @click="goRedFlag">去处置台裁定</el-button>
            </template>
            <template v-else>
              <el-button link type="warning" @click="setValid(row, 'OUT')">判出界</el-button>
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 登记圈速 -->
    <el-dialog v-model="lapDialog" title="登记一个圈速" width="420">
      <el-alert v-if="session && session.redFlagActive" type="error" :closable="false" show-icon
                style="margin-bottom: 12px"
                title="当前红旗处理中：该圈速会保留但不入榜，自动挂为待裁定记录，由主管在处置台确认有效或作废。" />
      <el-form label-width="80px">
        <el-form-item label="车手">
          <el-select v-model="lapForm.driverId" placeholder="选择车手" style="width: 100%">
            <el-option v-for="d in drivers" :key="d.id" :label="d.name + ' · ' + d.licenseName" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="车辆">
          <el-select v-model="lapForm.kartId" placeholder="选择在赛车辆" style="width: 100%">
            <el-option v-for="k in onTrackKarts" :key="k.id" :label="k.kartNo + ' · ' + k.modelName" :value="k.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="圈速">
          <el-input v-model.number="lapForm.lapTimeMs" placeholder="毫秒，例如 43800">
            <template #append>ms</template>
          </el-input>
        </el-form-item>
        <el-form-item label="判定">
          <el-select v-model="lapForm.valid" style="width: 100%">
            <el-option label="有效" value="VALID" />
            <el-option label="无效-出界" value="OUT" />
            <el-option label="无效-碰撞" value="CRASH" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lapDialog = false">取消</el-button>
        <el-button type="danger" @click="submitLap">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { driverApi, kartApi, lapApi, trackApi } from '../api'

const nav = useRouter()
const TRACK_D = 'M140 250 C140 150 215 88 325 88 C435 88 470 130 560 130 C685 130 765 168 765 250 '
  + 'C765 342 692 402 560 402 C458 402 420 372 320 382 C220 392 140 348 140 250 Z'

const track = inject('track')
const live = ref({ session: null, leaderboard: [], dots: [], lapCount: 0, fastestText: '--:--.---' })
const laps = ref([])
const drivers = ref([])
const karts = ref([])
const pathRef = ref(null)
const dotPos = ref([])
const fetchedAt = ref(Date.now())
const now = ref(Date.now())
const lapDialog = ref(false)
const lapForm = ref({ driverId: null, kartId: null, lapTimeMs: 43800, valid: 'VALID' })

let rafId = null
let startTs = 0
let timerId = null
let pollId = null

const session = computed(() => live.value.session)
const leaderboard = computed(() => live.value.leaderboard || [])
const dots = computed(() => live.value.dots || [])
const lapCount = computed(() => live.value.lapCount || 0)
const fastestText = computed(() => live.value.fastestText || '--:--.---')
const onTrackKarts = computed(() => karts.value.filter((k) => k.status === 'ON_TRACK'))

/* ---------- 大计时器：后端给的 elapsedSec + 本地走秒 ---------- */
const elapsedSec = computed(() => {
  if (!session.value) return 0
  if (session.value.status !== 'RUNNING' && session.value.status !== 'RED_FLAG') return session.value.elapsedSec || 0
  return (session.value.elapsedSec || 0) + Math.floor((now.value - fetchedAt.value) / 1000)
})
const timerText = computed(() => {
  const total = elapsedSec.value
  const m = String(Math.floor(total / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `${m}:${s}`
})
const tenths = computed(() => Math.floor((now.value % 1000) / 100))

/* ---------- 起终点格子旗（10 格） ---------- */
const checkerCells = computed(() => {
  const cells = []
  for (let i = 0; i < 10; i += 1) {
    const col = i % 2
    const row = Math.floor(i / 2)
    cells.push({ x: -13 + col * 13, y: -25 + row * 10, fill: (row + col) % 2 === 0 ? '#1b1b1b' : '#ffffff' })
  }
  return cells
})
const startFlagTransform = computed(() => {
  const path = pathRef.value
  let angle = 0
  if (path && typeof path.getPointAtLength === 'function' && path.getTotalLength() > 2) {
    const a = path.getPointAtLength(0)
    const b = path.getPointAtLength(2)
    angle = (Math.atan2(b.y - a.y, b.x - a.x) * 180) / Math.PI
  }
  return `translate(140, 250) rotate(${angle + 90})`
})

/* ---------- 小车点位沿赛道跑 ---------- */
function renderDots(ts) {
  const path = pathRef.value
  if (path && typeof path.getTotalLength === 'function') {
    const total = path.getTotalLength() || 1
    const t = (ts - startTs) / 1000
    dotPos.value = dots.value.map((d) => {
      const p = ((d.offset + t * 0.022 * (d.speed || 1)) % 1 + 1) % 1
      const pt = path.getPointAtLength(p * total)
      return { ...d, x: pt.x, y: pt.y }
    })
  }
  rafId = requestAnimationFrame(renderDots)
}

function dotColor(status) {
  if (status === 'ON_TRACK') return '#e53935'
  if (status === 'MAINTENANCE') return '#ef6c00'
  if (status === 'DISABLED') return '#90a4ae'
  return '#1e88e5'
}

function shortNo(no) {
  return String(no || '').replace('K-', '')
}

function fillClass(rank) {
  if (rank === 1) return 'gold'
  if (rank === 2) return 'silver'
  if (rank === 3) return 'bronze'
  return 'plain'
}

function barWidth(rank) {
  return `${Math.max(30, 100 - (rank - 1) * 7)}%`
}

/* ---------- 数据 ---------- */
async function loadLive() {
  const id = track.sessionId.value
  live.value = await trackApi.live(id)
  laps.value = await lapApi.list(id)
  fetchedAt.value = Date.now()
}

async function loadBase() {
  drivers.value = await driverApi.list()
  karts.value = await kartApi.list()
}

function openLapDialog() {
  lapForm.value = { driverId: null, kartId: null, lapTimeMs: 43800, valid: 'VALID' }
  lapDialog.value = true
}

function goRedFlag() {
  nav.push('/red-flag')
}

async function submitLap() {
  if (!lapForm.value.driverId || !lapForm.value.kartId) {
    return
  }
  await lapApi.create({
    sessionId: track.sessionId.value,
    driverId: lapForm.value.driverId,
    kartId: lapForm.value.kartId,
    lapTimeMs: lapForm.value.lapTimeMs,
    valid: lapForm.value.valid
  })
  lapDialog.value = false
  await loadLive()
  await loadBase()
}

async function setValid(row, valid) {
  await lapApi.setValid(row.id, valid)
  await loadLive()
}

async function remove(row) {
  await lapApi.remove(row.id)
  await loadLive()
}

watch(() => track.sessionId.value, () => {
  loadLive()
})

onMounted(async () => {
  await loadBase()
  await loadLive()
  startTs = performance.now()
  rafId = requestAnimationFrame(renderDots)
  timerId = setInterval(() => { now.value = Date.now() }, 100)
  pollId = setInterval(loadLive, 5000)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  clearInterval(timerId)
  clearInterval(pollId)
})
</script>
