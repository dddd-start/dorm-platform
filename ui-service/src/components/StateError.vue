<script setup lang="ts">
/**
 * 错误状态（Error State）
 *
 * 与空状态必须视觉可区分：空态用中性灰，错误态用危险色 + **明确的下一步动作**。
 * 只显示"加载失败"而不给重试入口，等于把用户丢在原地。
 *
 * `traceId` 是可选的：本项目服务端错误会带 traceId，把它显示出来
 * 能让用户报障时直接提供追踪号，而不是"我这边报错了"。
 */
withDefaults(defineProps<{
  title?: string
  description?: string
  traceId?: string
  /** 是否显示重试按钮（父组件监听 retry 事件） */
  retryable?: boolean
  compact?: boolean
}>(), {
  title: '加载失败',
  description: '请检查网络后重试；若反复失败，可联系管理员。',
  traceId: '',
  retryable: true,
  compact: false,
})

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="state-error" :class="{ 'is-compact': compact }" role="alert">
    <div class="state-error__icon" aria-hidden="true">
      <svg viewBox="0 0 64 64" width="100%" height="100%" fill="none">
        <circle cx="32" cy="32" r="22" stroke="currentColor" stroke-width="2" opacity="0.5" />
        <path d="M32 20v16M32 43v.5" stroke="currentColor" stroke-width="3"
              stroke-linecap="round" />
      </svg>
    </div>
    <p class="state-error__title">{{ title }}</p>
    <p v-if="description" class="state-error__desc">{{ description }}</p>
    <p v-if="traceId" class="state-error__trace">追踪号：{{ traceId }}</p>
    <div v-if="retryable" class="state-error__action">
      <el-button size="small" type="primary" plain @click="$emit('retry')">重试</el-button>
    </div>
  </div>
</template>

<style scoped>
.state-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--d-space-2);
  padding: var(--d-space-10) var(--d-space-4);
  text-align: center;
}

.state-error.is-compact {
  padding: var(--d-space-5) var(--d-space-3);
}

.state-error__icon {
  width: 56px;
  height: 56px;
  color: var(--d-danger);
}

.is-compact .state-error__icon {
  width: 36px;
  height: 36px;
}

.state-error__title {
  margin: 0;
  font-size: var(--d-font-size-md);
  font-weight: var(--d-font-weight-medium);
  color: var(--d-text-primary);
}

.state-error__desc {
  margin: 0;
  max-width: 42ch;
  font-size: var(--d-font-size-sm);
  color: var(--d-text-tertiary);
  line-height: var(--d-line-relaxed);
}

.state-error__trace {
  margin: 0;
  font-family: var(--d-font-mono);
  font-size: var(--d-font-size-xs);
  color: var(--d-text-placeholder);
}

.state-error__action {
  margin-top: var(--d-space-2);
}
</style>
