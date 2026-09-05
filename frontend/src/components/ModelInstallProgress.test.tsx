import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AiStatusButton } from './AiStatusButton'
import { ChatModelSettings } from './ChatModelSettings'
import { activeInstall } from '../hooks/useModelInstall'
import type { ModelInstallController } from '../hooks/useModelInstall'
import type { LocalAiStatus, ModelInstallJob } from '../types/localAi'

const ready: LocalAiStatus = {
  ollama: { status: 'CONNECTED', version: '검증용', errorCode: null },
  embedding: { model: 'qwen3-embedding:0.6b', status: 'READY', digest: 'embedding', sizeBytes: 639150858, errorCode: null },
  chat: { model: 'qwen3.5:9b', status: 'READY', digest: 'chat', sizeBytes: 6600000000, errorCode: null },
  chatProfile: { profile: 'HIGH_SPEC', displayName: '고사양', model: 'qwen3.5:9b', estimatedDownloadBytes: 6600000000, contextLength: 8192, maximumGeneratedTokens: 1536, maximumSourceDocuments: 7 },
  capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: true },
  lastCheckedAt: null, checkInProgress: false,
}
const completed: ModelInstallJob = {
  jobId: 'install-1', role: 'CHAT', model: 'qwen3.5:9b', status: 'COMPLETED',
  progressPercent: 100, completedBytes: 6600000000, totalBytes: 6600000000,
  errorCode: null, createdAt: '2026-09-01T01:00:00Z', updatedAt: '2026-09-01T01:00:01Z',
}

function controller(job: ModelInstallJob, refreshing = false): ModelInstallController {
  return { job, refreshing, startingRole: null, tracking: activeInstall(job) || refreshing,
    cancelling: false, error: '', busy: activeInstall(job) || refreshing, canRetry: false,
    start: vi.fn(async () => {}), cancel: vi.fn(async () => {}), retry: vi.fn(async () => {}) }
}

function renderSurfaces(install: ModelInstallController) {
  return render(<>
    <ChatModelSettings status={ready} onSelect={vi.fn(async () => {})} install={install} />
    <AiStatusButton status={ready} checkPhase="COMPLETE" onRefresh={vi.fn()} onOpenSettings={vi.fn()} install={install} />
  </>)
}

afterEach(cleanup)

describe('설치 진행 영역의 완료 시점', () => {
  it.each([false, true])('완료되면 AI 준비 상태 갱신 중(%s)이어도 두 화면에서 진행 영역을 제거한다', async refreshing => {
    const user = userEvent.setup()
    renderSurfaces(controller(completed, refreshing))
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    expect(document.querySelector('.settings-install-progress')).not.toBeInTheDocument()
    expect(document.querySelector('.install-progress')).not.toBeInTheDocument()
    expect(screen.queryByText('설치 완료')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'AI 모델 상태: 준비됨, 설정으로 이동' })).toBeInTheDocument()
  })

  it.each(['QUEUED', 'DOWNLOADING', 'VERIFYING', 'CANCEL_REQUESTED', 'FAILED', 'CANCELLED'] as const)('%s 진행·오류·취소 안내는 계속 표시한다', async status => {
    const user = userEvent.setup()
    renderSurfaces(controller({ ...completed, status, progressPercent: 18 }))
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    expect(document.querySelector('.settings-install-progress')).toBeVisible()
    expect(document.querySelector('.install-progress')).toBeVisible()
  })

  it('임베딩 모델 설치 완료도 AI 상태 팝업에 남기지 않는다', async () => {
    const user = userEvent.setup()
    renderSurfaces(controller({ ...completed, role: 'EMBEDDING', model: 'qwen3-embedding:0.6b' }))
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    expect(document.querySelector('.install-progress')).not.toBeInTheDocument()
    expect(screen.queryByText('설치 완료')).not.toBeInTheDocument()
    expect(screen.getAllByText('준비됨')).toHaveLength(2)
  })
})
