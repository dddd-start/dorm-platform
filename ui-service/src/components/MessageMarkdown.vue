<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/**
 * 模型回答的 Markdown 渲染。
 *
 * 为什么需要它：模型输出的是 Markdown（标题、表格、列表、行内代码），
 * 直接 `{{ content }}` 渲染会把 `##`、`|---|` 原样显示出来，回答看起来像日志。
 *
 * 安全约束：**模型输出一律视为不可信内容**。这里先用 marked 解析，再交给
 * DOMPurify 白名单清洗，然后才 `v-html`。不直接把模型文本塞进 innerHTML，
 * 否则一段 `![](javascript:...)` 或者 `<img onerror>` 就能在浏览器里执行。
 */
const props = defineProps<{
  text: string
  /** 流式进行中：保留光标效果，同时避免在半个表格上做排版抖动 */
  streaming?: boolean
}>()

marked.setOptions({ gfm: true, breaks: true })

/**
 * 模型偶尔会把标题写在句尾（"……看下余量。 ## 一、申请流程"），
 * 按 Markdown 规则行内 `##` 不是标题，会原样显示。这里在渲染前做一次规整：
 * 把"非行首"的标题/表格前补空行，渲染结果才符合模型本意。
 */
function normalizeMarkdown(text: string): string {
  return text
    .replace(/([^\n])\s*(#{1,6}\s)/g, '$1\n\n$2')
    .replace(/([^\n])\s*(\|[^\n]*\|[ \t]*\n[ \t]*\|[ :|-]+\|)/g, '$1\n\n$2')
}

const html = computed(() => {
  const raw = props.text ?? ''
  if (!raw) {
    return ''
  }
  // async:false 明确要同步字符串，避免 marked 返回 Promise
  const parsed = marked.parse(normalizeMarkdown(raw), { async: false }) as string
  return DOMPurify.sanitize(parsed, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel'],
  })
})
</script>

<template>
  <div class="md-body" :class="{ 'md-body--streaming': streaming }" v-html="html" />
</template>

<style scoped>
.md-body :deep(h1),
.md-body :deep(h2),
.md-body :deep(h3),
.md-body :deep(h4) {
  margin: 14px 0 8px;
  font-size: var(--d-font-size-md, 15px);
  font-weight: 600;
  line-height: 1.5;
}

.md-body :deep(h1) {
  font-size: 17px;
}

.md-body :deep(p) {
  margin: 0 0 8px;
}

.md-body :deep(ul),
.md-body :deep(ol) {
  margin: 6px 0 10px;
  padding-left: 22px;
}

.md-body :deep(li) {
  margin: 2px 0;
}

.md-body :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--d-fill-light, #f2f3f5);
  font-family: var(--d-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-size: 0.92em;
}

.md-body :deep(pre) {
  margin: 8px 0 10px;
  padding: 10px 12px;
  overflow-x: auto;
  border-radius: 6px;
  background: var(--d-fill-light, #f2f3f5);
}

.md-body :deep(pre code) {
  padding: 0;
  background: transparent;
}

.md-body :deep(table) {
  margin: 8px 0 12px;
  border-collapse: collapse;
  font-size: 0.94em;
}

.md-body :deep(th),
.md-body :deep(td) {
  padding: 5px 10px;
  border: 1px solid var(--d-border, #dcdfe6);
  text-align: left;
}

.md-body :deep(th) {
  background: var(--d-fill-light, #f2f3f5);
  font-weight: 600;
}

.md-body :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--d-border, #dcdfe6);
  color: var(--d-text-secondary, #606266);
}

.md-body :deep(a) {
  color: var(--d-brand, #409eff);
}

/* 流式进行中：隐藏未闭合表格的横向滚动条抖动，光标沿用外层动画 */
.md-body--streaming :deep(table) {
  opacity: 0.96;
}
</style>
