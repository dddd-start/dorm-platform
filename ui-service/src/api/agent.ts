import { api, cleanParams } from './http'
import type {
  AgentMeta,
  AgentMemory,
  AgentReplay,
  ChatMessageVO,
  ConversationVO,
  LongId,
  PageResult,
} from './types'

export const agentApi = {
  meta: () => api.get<AgentMeta>('/agent/meta'),

  createConversation: (title?: string) =>
    api.post<ConversationVO>('/agent/conversations', undefined, { params: cleanParams({ title }) }),
  conversations: (params: { current?: number; size?: number }) =>
    api.get<PageResult<ConversationVO>>('/agent/conversations', cleanParams({ ...params })),
  messages: (id: LongId, limit = 100) =>
    api.get<ChatMessageVO[]>(`/agent/conversations/${id}/messages`, { limit }),
  deleteConversation: (id: LongId) => api.delete<void>(`/agent/conversations/${id}`),

  /** 停止生成 */
  stop: (messageId: LongId) => api.post<void>(`/agent/chat/${messageId}/stop`),
  /** 点赞 / 点踩 */
  feedback: (messageId: LongId, rating: 'LIKE' | 'DISLIKE', reason?: string) =>
    api.post<void>(`/agent/messages/${messageId}/feedback`, undefined, {
      params: cleanParams({ rating, reason }),
    }),

  memories: () => api.get<AgentMemory[]>('/agent/memories'),
  clearMemories: () => api.delete<void>('/agent/memories'),

  replay: (conversationId: LongId, messageId?: LongId) =>
    api.get<AgentReplay>(`/agent/replay/${conversationId}`, cleanParams({ messageId })),

  /* ---------------- 管理员：Agent 运营看板 ---------------- */
  adminModels: () => api.get<import('./types').AgentModelHealth[]>('/agent/admin/models'),
  adminUsage: (days = 14) => api.get<import('./types').AgentUsageResult>('/agent/admin/usage', { days }),
  adminKnowledge: () => api.get<import('./types').AgentKnowledgeStatus>('/agent/admin/knowledge'),
  adminRagQuality: (days = 30) =>
    api.get<import('./types').RagQuality>('/agent/admin/rag-quality', { days }),
  adminTools: () => api.get<import('./types').AgentToolsResult>('/agent/admin/tools'),
}

/** SSE 流地址（开发期走 vite 代理，生产期同源） */
export const AGENT_STREAM_PATH = '/api/agent/chat/stream'
export function agentStreamResumePath(messageId: LongId): string {
  return `/api/agent/chat/stream/${messageId}`
}
