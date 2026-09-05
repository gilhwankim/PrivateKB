import type { ChatModelProfile, LocalAiStatus, ModelInstallJob, ModelInstallRole } from '../types/localAi'
import { apiUrl } from './base'

const JSON_HEADERS = { Accept: 'application/json' }

export async function getLocalAiStatus(signal?: AbortSignal): Promise<LocalAiStatus> {
  const response = await fetch(apiUrl('/api/local-ai/status'), {
    headers: JSON_HEADERS,
    signal,
  })
  return readStatus(response)
}

export async function triggerLocalAiCheck(signal?: AbortSignal): Promise<LocalAiStatus> {
  const response = await fetch(apiUrl('/api/local-ai/checks'), {
    method: 'POST',
    headers: JSON_HEADERS,
    signal,
  })
  return readStatus(response)
}

export async function selectChatProfile(
  profile: ChatModelProfile,
  signal?: AbortSignal,
): Promise<LocalAiStatus> {
  const response = await fetch(apiUrl('/api/local-ai/chat-profile'), {
    method: 'PUT',
    headers: { ...JSON_HEADERS, 'Content-Type': 'application/json' },
    body: JSON.stringify({ profile }),
    signal,
  })
  return readStatus(response)
}

export async function uploadDocument(file: File): Promise<void> {
  const body = new FormData()
  body.append('file', file)
  const response = await fetch(
    apiUrl('/api/workspaces/00000000-0000-0000-0000-000000000001/documents'),
    { method: 'POST', body },
  )
  if (!response.ok) {
    throw new Error('문서 업로드 요청이 거부되었습니다.')
  }
}

export async function resumeAvailableIndexes(signal?: AbortSignal): Promise<void> {
  const response = await fetch(apiUrl('/api/indexing/resume'), {
    method: 'POST',
    headers: JSON_HEADERS,
    signal,
  })
  if (!response.ok) {
    throw new Error('대기 중인 문서 색인을 재개할 수 없습니다.')
  }
}

export async function startModelInstall(
  role: ModelInstallRole,
  signal?: AbortSignal,
): Promise<ModelInstallJob> {
  const endpoint = role === 'EMBEDDING' ? 'embedding' : 'chat'
  const response = await fetch(apiUrl(`/api/local-ai/models/${endpoint}/install`), {
    method: 'POST',
    headers: { ...JSON_HEADERS, 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirmed: true }),
    signal,
  })
  return readInstallJob(response)
}

export async function getModelInstall(
  jobId: string,
  signal?: AbortSignal,
): Promise<ModelInstallJob> {
  const response = await fetch(apiUrl(`/api/local-ai/model-installs/${jobId}`), {
    headers: JSON_HEADERS,
    signal,
  })
  return readInstallJob(response)
}

export async function cancelModelInstall(jobId: string, signal?: AbortSignal): Promise<ModelInstallJob> {
  const response = await fetch(apiUrl(`/api/local-ai/model-installs/${jobId}/cancel`), {
    method: 'POST',
    headers: JSON_HEADERS,
    signal,
  })
  return readInstallJob(response)
}

async function readStatus(response: Response): Promise<LocalAiStatus> {
  if (!response.ok) {
    throw new Error('AI 상태를 확인할 수 없습니다.')
  }
  return (await response.json()) as LocalAiStatus
}

async function readInstallJob(response: Response): Promise<ModelInstallJob> {
  if (!response.ok) {
    try {
      const problem = (await response.json()) as { detail?: unknown }
      if (typeof problem.detail === 'string') throw new Error(problem.detail)
    } catch (error) {
      if (error instanceof Error) throw error
    }
    throw new Error('모델 설치 작업을 처리할 수 없습니다.')
  }
  return (await response.json()) as ModelInstallJob
}
