<script setup lang="ts">
/** 统计看板卡片 */
defineProps<{
  label: string
  value: string | number
  /** CSS 渐变背景 */
  background: string
  icon?: string
  suffix?: string
}>()
</script>

<template>
  <div class="stat-card" :style="{ background }">
    <div style="display: flex; justify-content: space-between; align-items: flex-start">
      <div>
        <div class="stat-value">{{ value }}<span v-if="suffix" style="font-size: 14px">{{ suffix }}</span></div>
        <div class="stat-label">{{ label }}</div>
      </div>
      <el-icon v-if="icon" :size="30" style="opacity: 0.5"><component :is="icon" /></el-icon>
    </div>
  </div>
</template>
