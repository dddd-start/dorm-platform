<script setup lang="ts">
import StateEmpty from './StateEmpty.vue'
import StateError from './StateError.vue'
import StateSkeleton from './StateSkeleton.vue'

/**
 * 三态边界（Async State Boundary）
 *
 * 把「空 / 加载中 / 出错 / 有数据」四选一的渲染优先级收敛到一个地方，
 * 强制每个使用点**显式声明自己属于哪一种语义**：
 *
 *   · loading  → StateSkeleton   （骨架，预示内容形状，避免 CLS）
 *   · error    → StateError      （危险色 + 重试入口 + traceId）
 *   · empty    → StateEmpty      （中性灰 + 告诉用户下一步做什么）
 *   · 其余     → 默认插槽（真正的内容）
 *
 * 为什么需要一个边界组件而不是在每个页面手写 v-if 链：
 *   1. 手写时最容易漏掉的就是"错误"分支 —— 页面 catch 里写 `list = []`，
 *      于是接口 500 和"确实没数据"渲染成同一个空态，用户只能靠刷新来猜。
 *      边界组件让 `error` 成为一个**必须传入的参数**，漏了它类型检查就报错。
 *   2. 优先级顺序（加载 > 错误 > 空）在同一个地方定义，不会在不同页面排错。
 *
 * 注意：**不要把 `error` 和空态混用**。同一个 el-empty 在不同页面
 * 该是"空"还是"错"必须逐页确认，这正是三态存在的理由。
 */
withDefaults(
  defineProps<{
    loading?: boolean
    /** 出错信息；非空即渲染错误态 */
    error?: string
    /** 服务端追踪号（可选，报障时直接给这个号） */
    traceId?: string
    /** 是否为空（仅在非加载、非错误时生效） */
    empty?: boolean
    /** 骨架形态：列表 / 卡片 / 详情 */
    skeleton?: 'list' | 'card' | 'detail'
    skeletonRows?: number
    emptyTitle?: string
    emptyDescription?: string
    errorTitle?: string
    /** 紧凑模式：用于弹窗 / 抽屉 / 侧栏 */
    compact?: boolean
    /** 错误态是否显示重试按钮 */
    retryable?: boolean
  }>(),
  {
    loading: false,
    error: '',
    traceId: '',
    empty: false,
    skeleton: 'list',
    skeletonRows: 5,
    emptyTitle: '暂无数据',
    emptyDescription: '',
    errorTitle: '加载失败',
    compact: false,
    retryable: true,
  },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <!-- data-state 是给自动化测试与调试用的"语义锚点"：
       断言三态是否真的可区分，必须能读到当前到底处于哪一态，
       而不是靠猜某个类名在不在。 -->
  <StateSkeleton
    v-if="loading"
    data-state="loading"
    :variant="skeleton"
    :rows="skeletonRows"
  />

  <StateError
    v-else-if="error"
    data-state="error"
    :title="errorTitle"
    :description="error"
    :trace-id="traceId"
    :compact="compact"
    :retryable="retryable"
    @retry="$emit('retry')"
  />

  <StateEmpty
    v-else-if="empty"
    data-state="empty"
    :title="emptyTitle"
    :description="emptyDescription"
    :compact="compact"
  >
    <template v-if="$slots['empty-action']" #action>
      <slot name="empty-action" />
    </template>
  </StateEmpty>

  <slot v-else />
</template>
