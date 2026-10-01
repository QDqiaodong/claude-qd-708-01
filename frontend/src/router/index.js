import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部 Tab 条（赛道实况 / 场次 / 车辆 / 车手 / 维修保养）。
 * 顶栏的场次下拉是全站共享的「本场」上下文，切一场整站跟着变。
 */
export const navItems = [
  { key: 'home', path: '/', icon: '🏁', label: '赛道实况', hint: 'SVG 赛道俯视图 + 大计时器 + 实时圈速榜' },
  { key: 'redflag', path: '/red-flag', icon: '🚩', label: '红旗处置台', hint: '发起红旗 · 车辆核查 · 圈速裁定 · 恢复/终止' },
  { key: 'sessions', path: '/sessions', icon: '📋', label: '场次', hint: '待开赛 → 进行中 → 已结束 / 已取消' },
  { key: 'karts', path: '/karts', icon: '🏎️', label: '车辆', hint: '卡片墙 + 状态切换 + 派车到本场' },
  { key: 'drivers', path: '/drivers', icon: '🧑‍🚀', label: '车手', hint: '会员档案 + 最快圈速榜 + 报名本场' },
  { key: 'maintenance', path: '/maintenance', icon: '🔧', label: '维修保养', hint: '维修单流水 + 费用统计 + 完工' }
]

const routes = [
  { path: '/', name: 'home', component: () => import('../views/HomeView.vue') },
  { path: '/red-flag', name: 'redflag', component: () => import('../views/RedFlagView.vue') },
  { path: '/sessions', name: 'sessions', component: () => import('../views/SessionView.vue') },
  { path: '/karts', name: 'karts', component: () => import('../views/KartView.vue') },
  { path: '/drivers', name: 'drivers', component: () => import('../views/DriverView.vue') },
  { path: '/maintenance', name: 'maintenance', component: () => import('../views/MaintenanceView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
