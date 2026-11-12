<script setup lang="ts">
import { computed } from 'vue'
import { useMetaStore } from '@/stores/meta'
import type { EnumKey } from '@/api/types'

/**
 * 状态标签：颜色与文案都取自 `/api/meta/enums`，不硬编码。
 * 后端在业务对象上也会直接下发 statusColor（如 ApplyVO.statusColor），可用 overrideColor 优先使用。
 */
const props = defineProps<{
  /** 枚举字典的键，例如 applyStatus / orderStatus / roomStatus / bedStatus */
  enumKey: EnumKey
  value?: string | number | null
  /** 后端直接下发的颜色，优先级高于字典 */
  overrideColor?: string
  size?: 'large' | 'default' | 'small'
  effect?: 'dark' | 'light' | 'plain'
}>()

const meta = useMetaStore()

const label = computed(() => meta.label(props.enumKey, props.value))
const color = computed(() => props.overrideColor || meta.color(props.enumKey, props.value))
const isValid = computed(() => Boolean(props.value))

/** light 效果：背景用同色 10% 透明度（hex alpha 0x1A），文字保留原色，避免"同色叠同色"看不见文字。 */
const lightBackground = computed(() =>
  color.value ? `${color.value}1A` : undefined,
)
</script>

<template>
  <el-tag
    v-if="isValid"
    :size="size ?? 'small'"
    :effect="effect ?? 'light'"
    :color="(effect ?? 'light') === 'dark' ? color || undefined : lightBackground"
    :style="color && (effect ?? 'light') === 'dark' ? { borderColor: color, color: '#fff' } : undefined"
    disable-transitions
  >
    <span :style="color && (effect ?? 'light') === 'light' ? { color } : undefined">{{ label }}</span>
  </el-tag>
  <span v-else class="muted">-</span>
</template>
