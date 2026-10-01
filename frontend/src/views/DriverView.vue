<template>
  <div>
    <div class="cols">
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">车手档案</span>
          <span class="panel-extra">报名本场 / 退赛 · 历史最快圈速随有效圈自动刷新</span>
          <el-button type="danger" size="small" style="margin-left: auto" @click="dialog = true">＋ 新车手</el-button>
        </div>
        <el-table :data="rows" size="small" stripe>
          <el-table-column prop="memberNo" label="会员号" width="110" />
          <el-table-column prop="name" label="姓名" width="90" />
          <el-table-column prop="phone" label="手机号" width="120" />
          <el-table-column label="年龄分组" width="90">
            <template #default="{ row }">{{ row.ageGroupName }}</template>
          </el-table-column>
          <el-table-column label="驾照等级" width="90">
            <template #default="{ row }">
              <span class="tag" :class="row.licenseLevel === 'NONE' ? 'mute' : 'ok'">{{ row.licenseName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="totalSessions" label="累计场次" width="90" />
          <el-table-column label="历史最快" width="110">
            <template #default="{ row }"><b>{{ row.bestLapText }}</b></template>
          </el-table-column>
          <el-table-column prop="totalSpend" label="总消费" width="100" />
          <el-table-column prop="registerDate" label="注册日期" width="110" />
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button v-if="!row.sessionId" link type="danger" @click="enroll(row)">报名本场</el-button>
              <el-button v-else link type="warning" @click="quit(row)">退赛</el-button>
              <span v-if="row.sessionId" class="tag running" style="margin-left: 6px">{{ row.sessionNo }}</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">最快圈速榜</span>
          <span class="panel-extra">只统计有成绩的车手</span>
        </div>
        <div class="mini-board">
          <div v-for="r in board" :key="r.driverName" class="mini-row" :class="'rank' + (r.rank <= 3 ? r.rank : '')">
            <span class="board-rank" :class="rankClass(r.rank)">{{ r.rank }}</span>
            <b>{{ r.driverName }}</b>
            <span class="tag">累计 {{ r.lapCount }} 场</span>
            <span class="lap">{{ r.lapText }}</span>
          </div>
        </div>
        <div v-if="!board.length" class="empty">还没有车手跑出成绩</div>
      </div>
    </div>

    <el-dialog v-model="dialog" title="注册车手" width="420">
      <el-form label-width="90px">
        <el-form-item label="会员号"><el-input v-model="form.memberNo" placeholder="例如 M2026013" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model="form.phone" placeholder="11 位手机号" /></el-form-item>
        <el-form-item label="年龄分组">
          <el-select v-model="form.ageGroup" style="width: 100%">
            <el-option label="儿童" value="KID" />
            <el-option label="青少年" value="TEEN" />
            <el-option label="成人" value="ADULT" />
          </el-select>
        </el-form-item>
        <el-form-item label="驾照等级">
          <el-select v-model="form.licenseLevel" style="width: 100%">
            <el-option label="无证" value="NONE" />
            <el-option label="初级" value="BASIC" />
            <el-option label="中级" value="MID" />
            <el-option label="高级" value="ADVANCED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="danger" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { driverApi } from '../api'

const track = inject('track')
const rows = ref([])
const board = ref([])
const dialog = ref(false)
const form = ref({ memberNo: '', name: '', phone: '', ageGroup: 'ADULT', licenseLevel: 'BASIC' })

async function load() {
  rows.value = await driverApi.list()
  board.value = await driverApi.leaderboard()
}

function rankClass(rank) {
  if (rank === 1) return 'gold'
  if (rank === 2) return 'silver'
  if (rank === 3) return 'bronze'
  return ''
}

async function enroll(row) {
  await driverApi.enroll(row.id, track.sessionId.value)
  await load()
  await track.reloadSessions()
}

async function quit(row) {
  await driverApi.quit(row.id)
  await load()
  await track.reloadSessions()
}

async function submit() {
  await driverApi.create(form.value)
  dialog.value = false
  await load()
  await track.refreshOverview()
}

watch(() => track.sessionId.value, () => load())
onMounted(load)
</script>
