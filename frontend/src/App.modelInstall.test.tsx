import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { LocalAiStatus, ModelInstallJob } from './types/localAi'

const missing: LocalAiStatus = {
  ollama: { status: 'CONNECTED', version: '검증용', errorCode: null },
  embedding: { model: 'qwen3-embedding:0.6b', status: 'READY', digest: 'embedding', sizeBytes: 639150858, errorCode: null },
  chat: { model: 'qwen3.5:9b', status: 'NOT_INSTALLED', digest: null, sizeBytes: 0, errorCode: null },
  chatProfile: { profile: 'HIGH_SPEC', displayName: '고사양', model: 'qwen3.5:9b', estimatedDownloadBytes: 6600000000, contextLength: 8192, maximumGeneratedTokens: 1536, maximumSourceDocuments: 7 },
  capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: false },
  lastCheckedAt: '2026-09-01T01:00:00Z', checkInProgress: false,
}
const downloading: ModelInstallJob = {
  jobId: 'install-9b', role: 'CHAT', model: 'qwen3.5:9b', status: 'DOWNLOADING',
  progressPercent: 18, completedBytes: 1200000000, totalBytes: 6600000000,
  errorCode: null, createdAt: '2026-09-01T01:00:00Z', updatedAt: '2026-09-01T01:00:01Z',
}
const ready: LocalAiStatus = { ...missing, chat: { ...missing.chat, status: 'READY' }, capabilities: { ...missing.capabilities, groundedAnswer: true } }
const response = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })

describe('모델 설치 중 메뉴 전환', () => {
  beforeEach(() => {
    window.localStorage.clear()
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it.each(['설정', 'AI 팝업'])('%s에서 시작한 설치는 메뉴 이동 중 유지되며 완료 상태도 자동 반영한다', async entry => {
    let currentJob = downloading
    let resolveStart!: (value: Response) => void
    const pendingStart = new Promise<Response>(resolve => { resolveStart = resolve })
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/models/chat/install')) return pendingStart
      if (url.endsWith('/model-installs/install-9b')) return response(currentJob)
      if (url.includes('/documents?')) return response({ documents: [], totalElements: 0, page: 0, size: 20, hasNext: false })
      return response(currentJob.status === 'COMPLETED' ? ready : missing)
    })
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<App />)
    const settingsButton = within(screen.getByRole('navigation', { name: '주 메뉴' })).getByRole('button', { name: '설정' })
    await screen.findByRole('button', { name: 'AI 상태' })
    if (entry === '설정') {
      await user.click(settingsButton)
      await user.click(screen.getByRole('button', { name: '선택한 모델 설치' }))
    } else {
      await user.click(screen.getByRole('button', { name: 'AI 상태' }))
      await user.click(screen.getByRole('button', { name: 'AI 모델 설치' }))
    }
    await user.dblClick(screen.getByRole('button', { name: '설치 시작' }))
    const starts = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/models/chat/install')).length
    const polls = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/model-installs/install-9b')).length
    expect(starts()).toBe(1)
    expect(screen.queryByRole('button', { name: '설치 시작' })).not.toBeInTheDocument()
    if (entry === 'AI 팝업') await user.click(screen.getByRole('button', { name: '닫기' }))
    await user.click(screen.getByRole('button', { name: '홈' }))
    await user.click(screen.getByRole('button', { name: '문서' }))
    await user.click(settingsButton)
    expect(screen.getByRole('button', { name: '설치 요청 중' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: /일반 PC/ })).toBeDisabled()

    await act(async () => { resolveStart(response(downloading)) })
    expect(await screen.findByText('다운로드 중')).toBeInTheDocument()
    const installButton = screen.getByRole('button', { name: '설치 중' })
    expect(installButton).toBeDisabled()
    await user.click(installButton)
    expect(screen.queryByLabelText('대화 모델 설치 확인')).not.toBeInTheDocument()
    expect(starts()).toBe(1)
    expect(screen.getByText(/18% · 1.2GB \/ 6.6GB/)).toBeInTheDocument()

    // AI 상태 팝업도 동일 작업을 사용하고 새 설치 진입점을 열지 않는다.
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    expect(screen.queryByRole('button', { name: 'AI 모델 설치' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    await user.click(screen.getByRole('button', { name: '홈' }))
    currentJob = { ...downloading, progressPercent: 35 }
    await waitFor(() => expect(polls()).toBeGreaterThanOrEqual(1), { timeout: 2000 })
    await user.click(settingsButton)
    expect(screen.getByText(/35% ·/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '설치 중' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '문서' }))
    currentJob = { ...downloading, status: 'COMPLETED', progressPercent: 100, completedBytes: 6600000000 }
    await waitFor(() => expect(screen.getByRole('button', { name: '질문' })).toBeEnabled(), { timeout: 2000 })
    await user.click(settingsButton)
    expect(screen.queryByText('설치 완료')).not.toBeInTheDocument()
    expect(document.querySelector('.settings-install-progress')).not.toBeInTheDocument()
    expect(screen.getByText('qwen3.5:9b 모델을 사용할 준비가 되었습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '선택한 모델 설치' })).not.toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /일반 PC/ })).toBeEnabled()
    expect(starts()).toBe(1)
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    const popup = screen.getByRole('dialog', { name: 'AI 연결 상태' })
    expect(popup.querySelector('.install-progress')).not.toBeInTheDocument()
    expect(within(popup).queryByText('설치 완료')).not.toBeInTheDocument()
    expect(within(popup).getByRole('button', { name: 'AI 모델 상태: 준비됨, 설정으로 이동' })).toBeInTheDocument()
  })
})
