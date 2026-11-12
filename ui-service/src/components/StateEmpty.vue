<script setup lang="ts">
/**
 * 空状态（Empty State）
 *
 * 为什么要有统一组件：空态是最容易被忽略、却最影响"专业感"的一环。
 * 零散实现的结果是同一个系统里出现 5 种空态（有的只有一行灰字、有的带插画、
 * 有的干脆什么都不显示）—— 用户无法区分"没有数据"与"加载失败"。
 *
 * 三态（空/加载/错误）必须**视觉可区分**，否则用户只能靠刷新页面来猜。
 * 本组件负责"确实没有数据"，加载中用 StateSkeleton，出错用 StateError。
 */
withDefaults(defineProps<{
  /** 主标题：说清"这里为什么是空的" */
  title?: string
  /** 补充说明：告诉用户下一步能做什么 */
  description?: string
  /** 紧凑模式：用于弹窗/侧栏等空间受限处 */
  compact?: boolean
}>(), {
  title: '暂无数据',
  description: '',
  compact: false,
})
</script>

<template>
  <div class="state-empty" :class="{ 'is-compact': compact }">
    <div class="state-empty__icon" aria-hidden="true">
      <slot name="icon">
        <svg viewBox="0 0 64 64" width="100%" height="100%" fill="none">
          <rect x="10" y="18" width="44" height="30" rx="4"
                stroke="currentColor" stroke-width="2" opacity="0.5" />
          <path d="M10 30h44M24 18v30" stroke="currentColor" stroke-width="2" opacity="0.35" />
        </svg>
      </slot>
    </div>
    <p class="state-empty__title">{{ title }}</p>
    <p v-if="description" class="state-empty__desc">{{ description }}</p>
    <div v-if="$slots.action" class="state-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.state-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--d-space-2);
  padding: var(--d-space-10) var(--d-space-4);
  text-align: center;
}

.state-empty.is-compact {
  padding: var(--d-space-5) var(--d-space-3);
  gap: var(--d-space-1);
}

.state-empty__icon {
  width: 56px;
  height: 56px;
  color: var(--d-text-placeholder);
  margin-bottom: var(--d-space-1);
}

.is-compact .state-empty__icon {
  width: 36px;
  height: 36px;
}

.state-empty__title {
  margin: 0;
  font-size: var(--d-font-size-md);
  font-weight: var(--d-font-weight-medium);
  color: var(--d-text-secondary);
  line-height: var(--d-line-normal);
}

.state-empty__desc {
  margin: 0;
  max-width: 42ch;
  font-size: var(--d-font-size-sm);
  color: var(--d-text-tertiary);
  line-height: var(--d-line-relaxed);
}

.state-empty__action {
  margin-top: var(--d-space-2);
}
</style>
