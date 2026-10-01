<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">场次列表</span>
        <span class="panel-extra">待开赛 → 进行中 → 已结束 / 已取消</span>
        <el-button type="danger" size="small" style="margin-left: auto" @click="openCreate">＋ 新建场次</el-button>
      </div>
      <el-table :data="rows" size="small" stripe>
        <el-table-column prop="sessionNo" label="场次号" width="130" />
        <el-table-column label="赛道配置" width="100">
          <template #default="{ row }">{{ row.trackConfigName }}</template>
        </el-table-column>
        <el-table-column label="分组" width="70">
          <template #default="{ row }">{{ row.groupCode }} 组</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column label="车辆" width="90">
          <template #default="{ row }">{{ row.signedCount }}/{{ row.maxKarts }}</template>
        </el-table-column>
        <el-table-column label="时长" width="90">
          <template #default="{ row }">{{ fmtDuration(row.elapsedSec) }}</template>
        </el-table-column>
        <el-table-column label="圈数" width="70" prop="lapCount" />
        <el-table-column label="最快圈" width="110" prop="fastestText" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="tag" :class="statusClass(row.status)">{{ row.statusName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="success" @click="act(row, 'start')">开赛</el-button>
            <el-button v-if="row.status === 'RUNNING'" link type="warning" @click="act(row, 'finish')">结束</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="danger" @click="act(row, 'cancel')">取消</el-button>
            <el-button v-if="row.redFlagActive" link type="danger" @click="openConsole(row)">🚩 红旗处置台</el-button>
            <el-button v-if="row.redFlagCount" link @click="openConsole(row)">红旗记录 {{ row.redFlagCount }}</el-button>
            <el-button link @click="focus(row)">设为本场</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialog" title="新建场次" width="420">
      <el-form label-width="90px">
        <el-form-item label="场次号">
          <el-input v-model="form.sessionNo" placeholder="例如 S2026092403" />
        </el-form-item>
        <el-form-item label="赛道配置">
          <el-select v-model="form.trackConfig" style="width: 100%">
            <el-option label="半场（最多 8 台）" value="HALF" />
            <el-option label="全场（最多 12 台）" value="FULL" />
            <el-option label="夜间（最多 10 台）" value="NIGHT" />
          </el-select>
        </el-form-item>
        <el-form-item label="分组">
          <el-select v-model="form.groupCode" style="width: 100%">
            <el-option label="A 组" value="A" />
            <el-option label="B 组" value="B" />
            <el-option label="C 组" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="最大车辆">
          <el-input v-model.number="form.maxKarts" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="danger" @click="submit">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { sessionApi } from '../api'

const nav = useRouter()
const track = inject('track')
const rows = ref([])
const dialog = ref(false)
const form = ref({ sessionNo: '', trackConfig: 'FULL', groupCode: 'A', maxKarts: 12 })

async function load() {
  rows.value = await sessionApi.list()
}

function statusClass(status) {
  if (status === 'RUNNING') return 'running'
  if (status === 'RED_FLAG') return 'danger'
  if (status === 'FINISHED') return 'ok'
  if (status === 'CANCELED') return 'mute'
  return 'warn'
}

function fmtDuration(sec) {
  const s = Math.max(0, sec || 0)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

function openCreate() {
  const stamp = new Date()
  const date = `${stamp.getFullYear()}${String(stamp.getMonth() + 1).padStart(2, '0')}${String(stamp.getDate()).padStart(2, '0')}`
  form.value = { sessionNo: `S${date}03`, trackConfig: 'FULL', groupCode: 'A', maxKarts: 12 }
  dialog.value = true
}

async function submit() {
  await sessionApi.create(form.value)
  dialog.value = false
  await load()
  await track.reloadSessions()
}

async function act(row, type) {
  if (type === 'start') await sessionApi.start(row.id)
  if (type === 'finish') await sessionApi.finish(row.id)
  if (type === 'cancel') await sessionApi.cancel(row.id)
  await load()
  await track.reloadSessions()
  await track.refreshOverview()
}

function focus(row) {
  track.sessionId.value = row.id
}

function openConsole(row) {
  track.sessionId.value = row.id
  nav.push('/red-flag')
}

watch(() => track.sessionId.value, () => load())
onMounted(load)
</script>
