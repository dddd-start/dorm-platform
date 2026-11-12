<script setup lang="ts">
/**
 * 加载骨架（Skeleton）
 *
 * 为什么用骨架而不是转圈：骨架能预示"即将出现的内容形状"，
 * 让布局在数据到达时不跳动（避免 CLS）；转圈只说明"在忙"。
 * 列表、卡片、详情三种形态覆盖了本项目绝大多数加载场景。
 */
withDefaults(defineProps<{
  /** 形态：列表（行）/ 卡片 / 详情（标题+段落） */
  variant?: 'list' | 'card' | 'detail'
  /** 行数或卡片数 */
  rows?: number
}>(), {
  variant: 'list',
  rows: 5,
})
</script>

<template>
  <div class="state-skeleton" role="status" aria-busy="true" aria-live="polite">
    <span class="sr-only">正在加载</span>

    <template v-if="variant === 'list'">
      <div v-for="i in rows" :key="i" class="sk-row">
        <span class="sk-bar" style="width: 18%" />
        <span class="sk-bar" style="width: 34%" />
        <span class="sk-bar" style="width: 22%" />
        <span class="sk-bar sk-bar--pill" style="width: 10%" />
      </div>
    </template>

    <template v-else-if="variant === 'card'">
      <div v-for="i in rows" :key="i" class="sk-card">
        <span class="sk-bar" style="width: 40%; height: 14px" />
        <span class="sk-bar" style="width: 72%" />
        <span class="sk-bar" style="width: 56%" />
      </div>
    </template>

    <template v-else>
      <span class="sk-bar" style="width: 30%; height: 18px" />
      <span class="sk-bar" style="width: 92%" />
      <span class="sk-bar" style="width: 88%" />
      <span class="sk-bar" style="width: 64%" />
    </template>
  </div>
</template>

<style scoped>
.state-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--d-space-3);
  padding: var(--d-space-4);
}

.sk-row {
  display: flex;
  align-items: center;
  gap: var(--d-space-4);
}

.sk-card {
  display: flex;
  flex-direction: column;
  gap: var(--d-space-2);
  padding: var(--d-space-4);
  border: var(--d-border-width) solid var(--d-border-subtle);
  border-radius: var(--d-radius-lg);
}

/* 骨架条：用令牌的中性底色 + 轻微脉冲，颜色克制以免喧宾夺主 */
.sk-bar {
  display: block;
  height: 12px;
  border-radius: var(--d-radius-sm);
  background: linear-gradient(
    90deg,
    var(--d-surface-sunken) 25%,
    var(--d-border-subtle) 37%,
    var(--d-surface-sunken) 63%
  );
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s var(--d-ease-standard) infinite;
}

.sk-bar--pill {
  height: 20px;
  border-radius: var(--d-radius-pill);
}

@keyframes sk-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* 屏幕阅读器专用文本：视觉不可见但可被读出 */
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
