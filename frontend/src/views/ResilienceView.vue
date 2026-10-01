<template>
  <div>
    <!-- 头部：场次 + 当前数据来源 + 汇总 -->
    <div class="rs-hero">
      <div class="rs-hero-main">
        <div class="rs-hero-label">
          计时设备韧性台 · {{ data.sessionNo || '暂无场次' }}
          <el-tag size="small" effect="dark" style="margin-left: 8px"
                  :type="data.sessionStatus === 'RUNNING' ? 'success' : (data.sessionStatus === 'RED_FLAG' ? 'danger' : 'info')">
            {{ data.sessionStatusName || '—' }}
          </el-tag>
          <el-tag size="small" effect="plain" style="margin-left: 6px">{{ data.trackCodeName }}</el-tag>
        </div>
        <div class="rs-source">
          <span class="rs-source-label">当前数据来源</span>
          <template v-if="data.currentSource">
            <b>📶 {{ data.currentSource.deviceName }}（{{ data.currentSource.deviceCode }} · {{ data.currentSource.roleName }}）</b>
            <span class="rs-source-time">自 {{ fmtTime(data.currentSource.switchedAt) }} 起
              <el-tag v-if="data.currentSource.takeoverId" size="small" type="warning">主备接管后</el-tag>
            </span>
          </template>
          <b v-else class="rs-source-none">未设置</b>
        </div>
      </div>
      <div class="rs-sums">
        <div class="rs-sum" :class="{ ok: data.acceptedCount > 0 }">
          <b>{{ data.acceptedCount }}</b><span>已入账事件</span>
        </div>
        <div class="rs-sum" :class="{ warn: data.pendingCount > 0 }">
          <b>{{ data.pendingCount }}</b><span>待对账</span>
        </div>
        <div class="rs-sum" :class="{ danger: data.quarantinedCount > 0 }">
          <b>{{ data.quarantinedCount }}</b><span>隔离区</span>
        </div>
        <div class="rs-sum" :class="{ warn: data.openGapCount > 0 }">
          <b>{{ data.openGapCount }}</b><span>未闭合缺口</span>
        </div>
      </div>
    </div>

    <!-- 待主管执行的接管请求横幅 -->
    <el-alert
      v-if="data.pendingTakeover"
      class="rs-banner"
      type="warning"
      show-icon
      :closable="false"
      :title="`存在待执行接管请求：${data.pendingTakeover.newDeviceName}（${data.pendingTakeover.newDeviceCode}）拟接管 ${data.pendingTakeover.oldDeviceName}`"
    >
      <template #default>
        <div class="rs-banner-row">
          <span>边界序号 #{{ data.pendingTakeover.boundarySeq }} · 原因：{{ data.pendingTakeover.reason }}
            · {{ data.pendingTakeover.requestedBy }} 发起于 {{ fmtTime(data.pendingTakeover.requestedAt) }}</span>
          <span class="rs-banner-ops">
            <el-button size="small" type="primary" :disabled="!isSupervisor || !data.pendingTakeover.executable"
                       @click="executeTakeover(data.pendingTakeover)">执行接管</el-button>
            <el-button size="small" :disabled="!isSupervisor" @click="openReject(data.pendingTakeover)">驳回</el-button>
            <el-button size="small" :disabled="!isStaff" @click="cancelTakeover(data.pendingTakeover)">撤销请求</el-button>
            <span v-if="!data.pendingTakeover.executable" class="rs-hint">场次状态/来源已变化，请求已过期不可执行</span>
            <span v-else-if="!isSupervisor" class="rs-hint">仅主管可执行/驳回</span>
          </span>
        </div>
      </template>
    </el-alert>

    <!-- 设备卡片墙 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">计时设备（{{ (data.devices || []).length }}）</span>
        <span class="panel-extra">
          每 3 秒自动刷新 ·
          <el-button link type="primary" @click="simDialog = true">🛠 设备模拟器</el-button>
          <el-button link type="primary" @click="regDialog = true">＋ 登记设备</el-button>
        </span>
      </div>
      <div class="rs-device-grid">
        <div v-for="d in data.devices" :key="d.id" class="rs-device" :class="deviceCardClass(d)">
          <div class="rs-device-top">
            <span class="rs-dot" :class="dotClass(d.status)"></span>
            <b class="rs-device-name">{{ d.name }}</b>
            <el-tag size="small" :type="d.role === 'PRIMARY' ? 'danger' : 'info'" effect="plain">
              {{ d.roleName }}
            </el-tag>
            <el-tag v-if="d.currentSource" size="small" type="warning" effect="dark">当前来源</el-tag>
          </div>
          <div class="rs-device-code">{{ d.deviceCode }} · {{ d.trackCodeName }} · {{ d.sessionNo || '未关联本场' }}</div>

          <div class="rs-metrics">
            <div><span>状态</span><b :class="statusTextClass(d.status)">{{ d.statusName }}</b></div>
            <div><span>最后心跳</span><b>{{ fmtTime(d.lastHeartbeatAt) }}（{{ ageText(d.lastAgeMs) }}）</b></div>
            <div><span>最近心跳号</span><b>{{ d.lastHbSeq == null ? '—' : '#' + d.lastHbSeq }}</b></div>
            <div><span>确认序号</span><b>#{{ d.confirmedSeq }}<template v-if="d.boundarySeq != null"> · 边界 #{{ d.boundarySeq }}</template></b></div>
            <div v-if="d.openGapFrom != null" class="rs-gapline">
              <span>缺失区间</span>
              <b class="rs-text-warn">#{{ d.openGapFrom }} ~ #{{ d.openGapTo }}（待对账 {{ d.pendingCount }}）</b>
            </div>
          </div>

          <div v-if="d.quarantined" class="rs-quar-box">
            已隔离：{{ d.quarantineReason }}<br />
            <span class="rs-quar-meta">{{ d.quarantinedBy }} · {{ fmtTime(d.quarantinedAt) }}</span>
          </div>

          <div v-if="d.alerts && d.alerts.length" class="rs-alert-mini">
            <div v-for="a in d.alerts.slice(0, 2)" :key="a.id">⚠️ {{ a.typeName }}：{{ a.message }}
              <el-button link type="primary" :disabled="!isStaff" @click="ack(a)">确认</el-button>
            </div>
          </div>

          <div class="rs-device-ops">
            <el-button size="small" :disabled="!isStaff" @click="openSimFor(d)">上报心跳</el-button>
            <el-button size="small" type="warning" plain :disabled="!isStaff"
                       @click="openTakeoverFor(d)">请求接管</el-button>
            <template v-if="!d.quarantined">
              <el-button size="small" type="danger" plain :disabled="!isSupervisor"
                         @click="openQuarantine(d)">隔离</el-button>
            </template>
            <el-button v-else size="small" type="success" plain :disabled="!isSupervisor"
                       @click="doRelease(d)">解除隔离</el-button>
          </div>
        </div>
      </div>
      <div v-if="!(data.devices || []).length" class="empty">本场暂无关联的计时设备（主管可登记并绑定设备到本场）</div>
    </div>

    <div class="cols">
      <!-- 序号缺口 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">序号缺口与断线补偿</span>
          <span class="panel-extra">缺口之后的事件先待对账，不进实时榜</span>
        </div>
        <el-table :data="data.gaps || []" size="small" stripe>
          <el-table-column prop="deviceCode" label="设备" width="120" />
          <el-table-column label="缺失区间" width="110">
            <template #default="{ row }"><b>#{{ row.missingFrom }} ~ #{{ row.missingTo }}</b></template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span class="tag" :class="row.status === 'OPEN' ? 'warn' : 'ok'">{{ row.statusName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="待对账" width="70">
            <template #default="{ row }">{{ row.pendingCount }}</template>
          </el-table-column>
          <el-table-column label="发现 / 补齐时刻" min-width="220">
            <template #default="{ row }">
              {{ fmtTime(row.openedAt) }}<template v-if="row.filledAt"> → {{ fmtTime(row.filledAt) }}</template>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!(data.gaps || []).length" class="empty">暂无序号缺口，所有上报序号连续</div>
      </div>

      <!-- 待对账事件 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">待对账事件（{{ (data.pendingEvents || []).length }}）</span>
          <span class="panel-extra">补齐后按序自动入账，已确认进度不回退</span>
        </div>
        <el-table :data="data.pendingEvents || []" size="small" stripe max-height="300">
          <el-table-column prop="deviceCode" label="设备" width="110" />
          <el-table-column prop="seq" label="序号" width="60" />
          <el-table-column prop="driverName" label="车手" width="84" />
          <el-table-column prop="kartNo" label="车" width="58" />
          <el-table-column prop="lapTimeText" label="圈速" width="100" />
          <el-table-column label="产生 / 收到" min-width="200">
            <template #default="{ row }">
              {{ fmtTime(row.occurredAt) }}<br />
              <span class="rs-sub">{{ fmtTime(row.receivedAt) }}（重放）</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!(data.pendingEvents || []).length" class="empty">没有挂起的待对账事件</div>
      </div>
    </div>

    <!-- 隔离区 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">隔离区圈速事件（{{ (data.quarantinedEvents || []).length }}）</span>
        <span class="panel-extra">越过接管边界 / 圈速越界的事件留在此处，仅主管可丢弃或重新归段</span>
      </div>
      <el-table :data="data.quarantinedEvents || []" size="small" stripe>
        <el-table-column prop="deviceCode" label="设备" width="120" />
        <el-table-column prop="seq" label="序号" width="60" />
        <el-table-column prop="driverName" label="车手" width="84" />
        <el-table-column prop="kartNo" label="车" width="58" />
        <el-table-column prop="lapTimeText" label="圈速" width="100" />
        <el-table-column label="隔离原因" width="150">
          <template #default="{ row }">
            <el-tag type="danger" size="small">{{ row.quarantineReasonName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="quarantineNote" label="说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="产生时刻" width="170">
          <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="主管操作" width="190">
          <template #default="{ row }">
            <el-button link type="success" :disabled="!isSupervisor" @click="resegment(row)">重新归段</el-button>
            <el-button link type="danger" :disabled="!isSupervisor" @click="discard(row)">丢弃</el-button>
            <div v-if="!isSupervisor" class="rs-op-hint">仅主管可处理隔离区数据</div>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!(data.quarantinedEvents || []).length" class="empty">隔离区为空</div>
    </div>

    <div class="cols">
      <!-- 接管记录 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">主备接管记录</span>
          <span class="panel-extra">边界前只认主设备、边界后只认备用设备</span>
        </div>
        <el-table :data="data.takeovers || []" size="small" stripe>
          <el-table-column prop="takeoverNo" label="编号" width="110" />
          <el-table-column label="主 → 备" min-width="200">
            <template #default="{ row }">
              {{ row.oldDeviceCode }} → <b>{{ row.newDeviceCode }}</b>
            </template>
          </el-table-column>
          <el-table-column label="边界" width="180">
            <template #default="{ row }">
              <template v-if="row.boundaryAt">#{{ row.boundarySeq }} · {{ fmtTime(row.boundaryAt) }}</template>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="tag" :class="takeoverClass(row.status)">{{ row.statusName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="requestedBy" label="发起人" width="84" />
          <el-table-column prop="executedBy" label="主管" width="84" />
        </el-table>
        <div v-if="!(data.takeovers || []).length" class="empty">本场暂无接管记录</div>
      </div>

      <!-- 告警 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">设备告警（{{ (data.alerts || []).length }} 待确认）</span>
          <span class="panel-extra">值班人员可确认；设备恢复后系统自动消除</span>
        </div>
        <el-table :data="data.alerts || []" size="small" stripe max-height="300">
          <el-table-column prop="deviceCode" label="设备" width="110" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="alertType(row.type)">{{ row.typeName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="内容" min-width="220" show-overflow-tooltip />
          <el-table-column label="产生时刻" width="160">
            <template #default="{ row }">{{ fmtTime(row.raisedAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ row }">
              <el-button link type="primary" :disabled="!isStaff" @click="ack(row)">确认</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!(data.alerts || []).length" class="empty">没有未确认告警</div>
      </div>
    </div>

    <!-- 设备时间线（回看一场比赛的完整计时来源变化） -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">设备时间线 · 一场比赛内的完整计时来源变化</span>
        <span class="panel-extra">心跳 / 接管边界 / 缺口开窗补齐 / 圈速事件按时间混排</span>
      </div>
      <el-timeline class="rs-timeline">
        <el-timeline-item
          v-for="(item, idx) in (data.timeline || []).slice(0, 120)"
          :key="idx"
          :type="timelineType(item.tone)"
          :timestamp="fmtTime(item.at) + ' · ' + item.kindName"
          placement="top">
          <b>{{ item.title }}</b>
          <div class="rs-sub">{{ item.detail }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>

    <!-- 接管请求对话框 -->
    <el-dialog v-model="takeoverDialog" title="主备设备接管请求" width="480px">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px"
        title="仅主管能执行接管；执行时在同一事务落定边界时刻与序号、切换当前数据来源。边界之后只认备用设备，旧主晚到事件将进隔离区。" />
      <el-form label-width="92px">
        <el-form-item label="当前主设备">
          <el-tag type="danger">{{ takeoverForm.oldName }}（{{ takeoverForm.oldCode }}）</el-tag>
        </el-form-item>
        <el-form-item label="备用设备" required>
          <el-select v-model="takeoverForm.backupDeviceId" placeholder="选择同赛道备用设备" style="width: 320px">
            <el-option v-for="d in backupChoices" :key="d.id"
                       :label="`${d.deviceCode} · ${d.name}（${d.statusName}）`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="接管原因" required>
          <el-input v-model="takeoverForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="例如：主计时门触发线圈损坏，连续 30 秒无心跳，申请备用门接管" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="takeoverDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitTakeover">提交接管请求</el-button>
      </template>
    </el-dialog>

    <!-- 驳回对话框 -->
    <el-dialog v-model="rejectDialog" title="驳回接管请求" width="420px">
      <el-input v-model="rejectNote" type="textarea" :rows="3" maxlength="200" show-word-limit
                placeholder="驳回原因（值班人员可见）" />
      <template #footer>
        <el-button @click="rejectDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 隔离对话框 -->
    <el-dialog v-model="quarantineDialog" title="隔离计时设备" width="420px">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom: 12px"
        :title="`隔离后 ${quarantineForm.name} 的心跳仍留痕，但其圈速事件一律拒绝，不进入本场成绩`" />
      <el-input v-model="quarantineForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit
                placeholder="隔离原因（必填）" />
      <template #footer>
        <el-button @click="quarantineDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitQuarantine">确认隔离</el-button>
      </template>
    </el-dialog>

    <!-- 登记设备 -->
    <el-dialog v-model="regDialog" title="登记计时设备（主管）" width="460px">
      <el-form label-width="92px">
        <el-form-item label="设备码" required>
          <el-input v-model="regForm.deviceCode" placeholder="设备侧烧录的稳定身份码，如 TD-FULL-F" style="width: 300px" />
        </el-form-item>
        <el-form-item label="设备名称" required>
          <el-input v-model="regForm.name" style="width: 300px" />
        </el-form-item>
        <el-form-item label="所属赛道">
          <el-radio-group v-model="regForm.trackCode">
            <el-radio-button label="HALF">半场</el-radio-button>
            <el-radio-button label="FULL">全场</el-radio-button>
            <el-radio-button label="NIGHT">夜间</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="regForm.role">
            <el-radio-button label="PRIMARY">主设备</el-radio-button>
            <el-radio-button label="STANDBY">备用设备</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="关联本场">
          <el-switch v-model="regForm.bindSession" />
          <span class="rs-sub" style="margin-left:8px">打开则把主设备绑定为 {{ data.sessionNo }} 的当前来源</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="regDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 设备模拟器（真实调用上报接口，用于触发断线补偿/重放/边界规则） -->
    <el-dialog v-model="simDialog" title="🛠 计时设备模拟器" width="760px" top="5vh">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px"
        title="模拟器直接调用设备上报接口：每条圈速带设备侧唯一标识、递增序号和实际产生时刻，可演示断线后乱序/缺口重放。" />
      <div class="sim-box">
        <div class="sim-row">
          <span class="sim-label">设备</span>
          <el-select v-model="sim.deviceCode" style="width: 260px">
            <el-option v-for="d in simDevices" :key="d.id"
                       :label="`${d.deviceCode} · ${d.name}`" :value="d.deviceCode" />
          </el-select>
          <el-button :disabled="!isStaff" @click="sendHeartbeat">发一次心跳</el-button>
          <el-button type="warning" plain :disabled="!isStaff" @click="sendHeartbeat(14000)">模拟波动心跳（隔 14s）</el-button>
        </div>
        <div class="sim-row">
          <span class="sim-label">车手</span>
          <el-select v-model="sim.driverId" style="width: 150px">
            <el-option v-for="d in drivers" :key="d.id" :label="`${d.memberNo} ${d.name}`" :value="d.id" />
          </el-select>
          <span class="sim-label" style="margin-left: 8px">车辆</span>
          <el-select v-model="sim.kartId" style="width: 120px">
            <el-option v-for="k in karts" :key="k.id" :label="k.kartNo" :value="k.id" />
          </el-select>
          <span class="sim-label" style="margin-left: 8px">圈速ms</span>
          <el-input-number v-model="sim.lapTimeMs" :min="10000" :max="320000" :step="10" style="width: 130px" />
          <el-select v-model="sim.valid" style="width: 110px">
            <el-option label="有效" value="VALID" />
            <el-option label="出界" value="OUT" />
            <el-option label="碰撞" value="CRASH" />
          </el-select>
        </div>
        <div class="sim-row">
          <span class="sim-label">事件UID</span>
          <el-input v-model="sim.eventUid" style="width: 260px" placeholder="留空自动生成（同 UID 重放=幂等）" />
          <span class="sim-label" style="margin-left: 8px">序号</span>
          <el-input-number v-model="sim.seq" :min="1" style="width: 120px" />
          <el-button type="primary" :disabled="!isStaff" @click="sendLap(false)">上报圈速</el-button>
          <el-button type="warning" plain :disabled="!isStaff" @click="sendLap(true)">模拟重放（3 分钟前产生）</el-button>
        </div>
        <div class="sim-actions">
          <el-button-group>
            <el-button :disabled="!isStaff" @click="simGap">场景：制造缺口（跳号上报）</el-button>
            <el-button type="success" plain :disabled="!isStaff" @click="simFillGap">场景：按序补齐缺口</el-button>
            <el-button type="danger" plain :disabled="!isStaff" @click="simReplay">场景：重复重放同一条</el-button>
          </el-button-group>
          <el-button text @click="resetSimSeq">序号重置为当前确认+1</el-button>
        </div>
        <div v-if="simResult" class="sim-result">
          上报结果：<el-tag size="small" :type="resultTag(simResult.stage)">{{ simResult.stageName }}</el-tag>
          {{ simResult.resultText }}
          <div v-if="simResult.duplicate" class="rs-sub">duplicate=true，未重复入账</div>
          <div v-if="simResult.advanced && simResult.advanced.length" class="rs-sub">
            补偿按序入账 {{ simResult.advanced.length }} 条：
            {{ simResult.advanced.map((e) => '#' + e.seq).join('、') }}
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resilienceApi, driverApi, kartApi } from '../api'

const track = inject('track')
const isSupervisor = computed(() => track.operatorRole.value === 'SUPERVISOR')
const isStaff = computed(() => track.operatorRole.value === 'STAFF' || isSupervisor.value)

const emptyData = {
  sessionId: null, sessionNo: '', sessionStatus: '', sessionStatusName: '', trackCodeName: '',
  currentSource: null, pendingTakeover: null, devices: [], gaps: [], pendingEvents: [],
  quarantinedEvents: [], takeovers: [], alerts: [], timeline: [],
  acceptedCount: 0, pendingCount: 0, quarantinedCount: 0, openGapCount: 0
}
const data = ref({ ...emptyData })
const submitting = ref(false)
let pollId = null

const takeoverDialog = ref(false)
const takeoverForm = ref({ oldId: null, oldCode: '', oldName: '', backupDeviceId: null, reason: '' })
const rejectDialog = ref(false)
const rejectNote = ref('')
const rejectTarget = ref(null)
const quarantineDialog = ref(false)
const quarantineForm = ref({ id: null, name: '', reason: '' })
const regDialog = ref(false)
const regForm = ref({ deviceCode: '', name: '', trackCode: 'FULL', role: 'PRIMARY', bindSession: true })

const simDialog = ref(false)
const drivers = ref([])
const karts = ref([])
const allDevices = ref([])
const simResult = ref(null)
const sim = ref({ deviceCode: '', driverId: null, kartId: null, lapTimeMs: 44000, valid: 'VALID', eventUid: '', seq: 15 })

const backupChoices = computed(() =>
  (data.value.devices || []).filter((d) => d.role === 'STANDBY' && d.id !== takeoverForm.value.oldId))
const simDevices = computed(() =>
  (allDevices.value.length ? allDevices.value : data.value.devices || []))

async function load() {
  data.value = await resilienceApi.console(track.sessionId.value)
}

/* ---------- 告警 ---------- */
async function ack(row) {
  await resilienceApi.ackAlert(row.id)
  await load()
}

/* ---------- 接管 ---------- */
function openTakeoverFor(d) {
  if (!isStaff.value) return ElMessage.warning('仅值班人员/主管可发起接管请求')
  if (d.role !== 'PRIMARY' || !d.currentSource) {
    return ElMessage.warning('只有当前主设备需要发起备用接管')
  }
  takeoverForm.value = { oldId: d.id, oldCode: d.deviceCode, oldName: d.name, backupDeviceId: null, reason: '' }
  takeoverDialog.value = true
}

async function submitTakeover() {
  if (!takeoverForm.value.backupDeviceId) return ElMessage.warning('请选择备用设备')
  if (!takeoverForm.value.reason.trim()) return ElMessage.warning('请填写接管原因')
  submitting.value = true
  try {
    await resilienceApi.requestTakeover(data.value.sessionId, {
      backupDeviceId: takeoverForm.value.backupDeviceId,
      reason: takeoverForm.value.reason.trim()
    })
    takeoverDialog.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

async function executeTakeover(row) {
  try {
    await ElMessageBox.confirm(
      `确认由 ${row.newDeviceName} 接管 ${row.oldDeviceName}？边界时刻取当前时刻、边界序号 #${row.boundarySeq}；切换在同一事务完成。`,
      '执行主备接管', { confirmButtonText: '执行接管', cancelButtonText: '取消', type: 'warning' })
  } catch { return }
  await resilienceApi.executeTakeover(row.id)
  await load()
}

function openReject(row) {
  rejectTarget.value = row
  rejectNote.value = ''
  rejectDialog.value = true
}
async function submitReject() {
  if (!rejectNote.value.trim()) return ElMessage.warning('请填写驳回原因')
  submitting.value = true
  try {
    await resilienceApi.rejectTakeover(rejectTarget.value.id, rejectNote.value.trim())
    rejectDialog.value = false
    await load()
  } finally { submitting.value = false }
}
async function cancelTakeover(row) {
  await resilienceApi.cancelTakeover(row.id)
  await load()
}

/* ---------- 隔离 / 解除 ---------- */
function openQuarantine(d) {
  quarantineForm.value = { id: d.id, name: d.name, reason: '' }
  quarantineDialog.value = true
}
async function submitQuarantine() {
  if (!quarantineForm.value.reason.trim()) return ElMessage.warning('请填写隔离原因')
  submitting.value = true
  try {
    await resilienceApi.quarantine(quarantineForm.value.id, quarantineForm.value.reason.trim())
    quarantineDialog.value = false
    await load()
  } finally { submitting.value = false }
}
async function doRelease(d) {
  await resilienceApi.release(d.id)
  await load()
}

/* ---------- 隔离区处置 ---------- */
async function discard(row) {
  let note = ''
  try {
    const r = await ElMessageBox.prompt('丢弃后该圈速永不入榜（仍保留留底），可填写说明', '丢弃隔离事件', {
      confirmButtonText: '确认丢弃', cancelButtonText: '取消', inputType: 'textarea', type: 'warning'
    })
    note = r.value || ''
  } catch { return }
  await resilienceApi.discardQuarantined(row.id, note)
  await load()
}
async function resegment(row) {
  let note = ''
  try {
    const r = await ElMessageBox.prompt('重新归段将跳过边界拦截、重走序号链（连续则直接入账，否则继续待对账），可填写主管认定说明', '重新归段', {
      confirmButtonText: '确认归段', cancelButtonText: '取消', inputType: 'textarea', type: 'success'
    })
    note = r.value || ''
  } catch { return }
  const res = await resilienceApi.resegmentQuarantined(row.id, note)
  await load()
}

/* ---------- 登记设备 ---------- */
async function submitRegister() {
  const f = regForm.value
  if (!f.deviceCode.trim() || !f.name.trim()) return ElMessage.warning('设备码与名称必填')
  submitting.value = true
  try {
    const d = await resilienceApi.register({
      deviceCode: f.deviceCode.trim(), name: f.name.trim(), trackCode: f.trackCode, role: f.role
    })
    if (f.bindSession && f.role === 'PRIMARY') {
      await resilienceApi.bind(d.id, { sessionId: data.value.sessionId, role: 'PRIMARY' })
    }
    regDialog.value = false
    await load()
    await loadDevices()
  } finally { submitting.value = false }
}

/* ---------- 模拟器 ---------- */
async function loadDevices() {
  allDevices.value = await resilienceApi.devices()
}
function openSimFor(d) {
  sim.value.deviceCode = d.deviceCode
  sim.value.seq = Number(d.confirmedSeq || 0) + 1
  simDialog.value = true
  ensureSimData()
}
async function ensureSimData() {
  if (!drivers.value.length) {
    drivers.value = await driverApi.list()
    karts.value = await kartApi.list()
    sim.value.driverId = drivers.value[0]?.id
    sim.value.kartId = karts.value.find((k) => k.status === 'ON_TRACK')?.id || karts.value[0]?.id
  }
  if (!allDevices.value.length) await loadDevices()
  if (!sim.value.deviceCode) sim.value.deviceCode = simDevices.value[0]?.deviceCode
}
async function sendHeartbeat(gap) {
  const payload = { deviceCode: sim.value.deviceCode }
  if (gap) payload.occurredAt = new Date(Date.now() - gap).toISOString().slice(0, 19)
  const r = await resilienceApi.heartbeat(payload)
  simResult.value = { stageName: r.statusName, resultText: r.message, stage: r.status, duplicate: r.duplicate, advanced: [] }
  await load()
}
function uid() {
  return sim.value.deviceCode + '-E' + Date.now().toString(36).toUpperCase() + Math.floor(Math.random() * 100)
}
async function sendLap(replay) {
  if (!sim.value.driverId || !sim.value.kartId) return ElMessage.warning('请选择车手与车辆')
  const payload = {
    deviceCode: sim.value.deviceCode,
    eventUid: sim.value.eventUid.trim() || uid(),
    seq: sim.value.seq,
    driverId: sim.value.driverId,
    kartId: sim.value.kartId,
    lapTimeMs: sim.value.lapTimeMs,
    valid: sim.value.valid
  }
  if (replay) payload.occurredAt = new Date(Date.now() - 3 * 60000).toISOString().slice(0, 19)
  const r = await resilienceApi.ingestLap(payload)
  simResult.value = r
  sim.value.eventUid = ''
  if (!replay) sim.value.seq = Number(r.confirmedSeq || sim.value.seq) + 1
  await load()
  await track.refreshOverview()
}
async function simGap() {
  // 直接跳到 +2 号：收到 seq>expected 后开窗，该号先挂起为待对账
  await sendSeq(sim.value.seq + 2)
}
async function simFillGap() {
  // 按缺口区间把每一个缺失序号逐条重放；补齐后服务端自动按序放其后待对账事件
  const d = simDevices.value.find((x) => x.deviceCode === sim.value.deviceCode)
  if (d.openGapFrom == null) {
    ElMessage.info('当前设备没有未闭合缺口')
    return
  }
  for (let s = d.openGapFrom; s <= d.openGapTo; s++) {
    await sendSeq(s)
  }
}
async function simReplay() {
  // 用固定 UID 连发两次，验证只入账一条
  const fixedUid = sim.value.deviceCode + '-REPLAY'
  const payload = () => ({
    deviceCode: sim.value.deviceCode, eventUid: fixedUid, seq: sim.value.seq,
    driverId: sim.value.driverId, kartId: sim.value.kartId,
    lapTimeMs: sim.value.lapTimeMs, valid: sim.value.valid
  })
  const r1 = await resilienceApi.ingestLap(payload())
  const r2 = await resilienceApi.ingestLap(payload())
  simResult.value = r2
  sim.value.seq = Number(r1.confirmedSeq || sim.value.seq) + 1
  await load()
  await track.refreshOverview()
}
async function sendSeq(seq, silentAdvance) {
  const r = await resilienceApi.ingestLap({
    deviceCode: sim.value.deviceCode,
    eventUid: uid(),
    seq,
    driverId: sim.value.driverId,
    kartId: sim.value.kartId,
    lapTimeMs: sim.value.lapTimeMs,
    valid: sim.value.valid
  })
  simResult.value = r
  sim.value.seq = Number(r.confirmedSeq || seq) + 1
  await load()
}
function resetSimSeq() {
  const d = simDevices.value.find((x) => x.deviceCode === sim.value.deviceCode)
  sim.value.seq = Number(d?.confirmedSeq || 0) + 1
}

/* ---------- 展示辅助 ---------- */
function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 19)
}
function ageText(ms) {
  if (ms == null || ms > 86400000) return '从未心跳'
  if (ms < 1000) return '刚刚'
  if (ms < 60000) return Math.floor(ms / 1000) + ' 秒前'
  return Math.floor(ms / 60000) + ' 分前'
}
function deviceCardClass(d) {
  return {
    'is-online': d.status === 'ONLINE',
    'is-flap': d.status === 'FLAPPING',
    'is-offline': d.status === 'OFFLINE',
    'is-isolated': d.status === 'ISOLATED'
  }
}
function dotClass(s) {
  return { online: s === 'ONLINE', flap: s === 'FLAPPING', offline: s === 'OFFLINE', isolated: s === 'ISOLATED' }
}
function statusTextClass(s) {
  return { 'rs-text-ok': s === 'ONLINE', 'rs-text-warn': s === 'FLAPPING',
           'rs-text-danger': s === 'OFFLINE', 'rs-text-muted': s === 'ISOLATED' || s === 'UNBOUND' }
}
function takeoverClass(s) {
  return { ok: s === 'DONE', warn: s === 'REQUESTED', mute: s === 'REJECTED' || s === 'CANCELED' }
}
function alertType(t) {
  return { OFFLINE: 'danger', FLAPPING: 'warning', GAP: 'warning', TAKEOVER_REQ: 'primary', QUARANTINED: 'danger' }[t] || 'info'
}
function timelineType(tone) {
  return { ok: 'success', warn: 'warning', danger: 'danger', mute: 'info' }[tone] || ''
}
function resultTag(stage) {
  return { ACCEPTED: 'success', PENDING: 'warning', QUARANTINED: 'danger', DISCARDED: 'info' }[stage] || ''
}

watch(() => track.sessionId.value, load)
watch(simDialog, (v) => { if (v) ensureSimData() })
onMounted(() => {
  load()
  pollId = setInterval(load, 3000)
})
onBeforeUnmount(() => clearInterval(pollId))
</script>

<style scoped>
.rs-hero {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  gap: 18px;
  padding: 16px 20px;
  border-radius: 12px;
  background: linear-gradient(120deg, #263238 0%, #455a64 60%, #607d8b 100%);
  color: #fff;
  margin-bottom: 14px;
}
.rs-hero-label { font-size: 12px; opacity: 0.85; margin-bottom: 6px; }
.rs-source { margin-top: 8px; display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.rs-source-label { font-size: 12px; opacity: 0.8; }
.rs-source-time { font-size: 12px; opacity: 0.85; }
.rs-source-none { color: #ffab91; }
.rs-sums { display: flex; gap: 10px; align-items: center; }
.rs-sum {
  min-width: 86px; padding: 8px 12px; border-radius: 10px;
  background: rgba(255, 255, 255, 0.12); text-align: center;
}
.rs-sum b { display: block; font-size: 22px; }
.rs-sum span { font-size: 11px; opacity: 0.85; }
.rs-sum.ok { background: rgba(46, 125, 50, 0.55); }
.rs-sum.warn { background: rgba(239, 108, 0, 0.65); }
.rs-sum.danger { background: rgba(198, 40, 40, 0.75); }

.rs-banner { margin-bottom: 14px; }
.rs-banner-row { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; align-items: center; }
.rs-banner-ops { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.rs-hint { font-size: 12px; color: var(--kt-text-sub); }
.rs-op-hint { font-size: 11px; color: #90a4ae; }

.rs-device-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.rs-device {
  border: 1px solid var(--kt-border);
  border-left-width: 4px;
  border-radius: 10px;
  padding: 12px 14px;
  background: #fafbfc;
}
.rs-device.is-online { border-left-color: var(--kt-ok); }
.rs-device.is-flap { border-left-color: var(--kt-warn); background: #fff8f0; }
.rs-device.is-offline { border-left-color: var(--kt-danger); background: #fdf3f3; }
.rs-device.is-isolated { border-left-color: #6a1b9a; background: #f8f0fb; }
.rs-device-top { display: flex; align-items: center; gap: 8px; }
.rs-device-name { font-size: 15px; }
.rs-device-code { font-size: 12px; color: var(--kt-text-sub); margin: 4px 0 8px; }
.rs-dot {
  width: 10px; height: 10px; border-radius: 50%; background: #b0bec5; display: inline-block;
}
.rs-dot.online { background: var(--kt-ok); box-shadow: 0 0 6px rgba(46, 125, 50, 0.6); }
.rs-dot.flap { background: var(--kt-warn); animation: flap-blink 1s infinite; }
.rs-dot.offline { background: var(--kt-danger); }
.rs-dot.isolated { background: #6a1b9a; }
@keyframes flap-blink { 50% { opacity: 0.25; } }

.rs-metrics { display: grid; grid-template-columns: 1fr; gap: 3px; font-size: 12.5px; }
.rs-metrics > div { display: flex; justify-content: space-between; }
.rs-metrics span { color: var(--kt-text-sub); }
.rs-gapline b { font-size: 12.5px; }
.rs-quar-box {
  margin-top: 8px; padding: 6px 8px; border-radius: 8px;
  background: #f3e5f5; color: #6a1b9a; font-size: 12px;
}
.rs-quar-meta { font-size: 11px; opacity: 0.8; }
.rs-alert-mini { margin-top: 6px; font-size: 12px; color: var(--kt-warn); }
.rs-device-ops { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }

.rs-text-ok { color: var(--kt-ok); }
.rs-text-warn { color: var(--kt-warn); }
.rs-text-danger { color: var(--kt-danger); }
.rs-text-muted { color: var(--kt-text-sub); }
.rs-sub { font-size: 11.5px; color: var(--kt-text-sub); }

.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-top: 14px; }
.panel { margin-top: 14px; }
.cols .panel { margin-top: 0; }
@media (max-width: 1200px) { .cols { grid-template-columns: 1fr; } }

.rs-timeline { padding-left: 4px; max-height: 520px; overflow-y: auto; margin-top: 10px; }

.sim-box { display: flex; flex-direction: column; gap: 10px; }
.sim-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sim-label { font-size: 13px; color: var(--kt-text-sub); min-width: 56px; }
.sim-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.sim-result {
  padding: 10px 12px; border-radius: 8px; background: #eef5ff;
  border: 1px solid #c5d9f1; font-size: 13px;
}
</style>
