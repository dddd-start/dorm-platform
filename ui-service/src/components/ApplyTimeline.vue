<script setup lang="ts">
import StateEmpty from './StateEmpty.vue'
import type { TimelineItem } from '@/api/types'

/**
 * 申请状态时间轴。颜色优先使用后端返回的 `color`/`statusColor`，
 * 缺失时回退到设计令牌的品牌色（不再写死 #409eff）。
 */
defineProps<{
  items?: TimelineItem[]
}>()

function colorOf(item: TimelineItem): string {
  return item.color || 'var(--d-brand)'
}
</script>

<template>
  <div>
    <el-timeline v-if="items && items.length > 0">
      <el-timeline-item
        v-for="(item, idx) in items"
        :key="`${item.node}-${idx}`"
        :color="colorOf(item)"
        :timestamp="item.time ?? ''"
        placement="top"
      >
        <div class="tl-label">{{ item.label }}</div>
        <div v-if="item.operator" class="muted">操作人：{{ item.operator }}</div>
        <div v-if="item.remark" class="muted">备注：{{ item.remark }}</div>
      </el-timeline-item>
    </el-timeline>
    <!-- "暂无状态记录"是真的空（时间轴本身没有节点），不是加载失败 -->
    <StateEmpty
      v-else
      data-state="empty"
      compact
      title="暂无状态记录"
      description="该申请还没有产生状态流转节点。"
    />
  </div>
</template>

<style scoped>
.tl-label {
  font-weight: 600;
  font-size: 14px;
}
</style>
