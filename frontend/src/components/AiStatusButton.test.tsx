import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ComponentProps } from 'react'
import { AiStatusButton as AiStatusButtonView } from './AiStatusButton'
import { useModelInstall } from '../hooks/useModelInstall'
import type { LocalAiStatus } from '../types/localAi'

const readyStatus: LocalAiStatus = {
  ollama: { status: 'CONNECTED', version: '0.32.15', errorCode: null },
  embedding: { model: 'qwen3-embedding:0.6b', status: 'READY', digest: 'embedding', sizeBytes: 639150858, errorCode: null },
  chat: { model: 'qwen3.5:4b', status: 'READY', digest: 'chat', sizeBytes: 3400000000, errorCode: null },
  chatProfile: { profile: 'STANDARD', displayName: '일반', model: 'qwen3.5:4b', estimatedDownloadBytes: 3400000000, contextLength: 8192, maximumGeneratedTokens: 1024, maximumSourceDocuments: 5 },
  capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: true },
  lastCheckedAt: null,
  checkInProgress: false,
}

function AiStatusButton(props: Omit<ComponentProps<typeof AiStatusButtonView>, 'install'>) {
  const install = useModelInstall(props.onRefresh)
  return <AiStatusButtonView {...props} install={install} />
}

describe('AI 상태의 설정 이동', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it.each([
    { status: 'DISABLED', label: '미사용' },
    { status: 'READY', label: '준비됨' },
    { status: 'NOT_INSTALLED', label: '설치되지 않음' },
    { status: 'CHECKING', label: '확인 중' },
    { status: 'INCOMPATIBLE', label: '호환되지 않음' },
    { status: 'ERROR', label: '확인 불가' },
  ] as const)('$label 상태에서도 상태 버튼을 눌러 설정으로 이동한다', async ({ status, label }) => {
    const onOpenSettings = vi.fn()
    const onRefresh = vi.fn()
    const user = userEvent.setup()
    render(<AiStatusButton status={{ ...readyStatus, chat: { ...readyStatus.chat, status } }} checkPhase={status === 'CHECKING' ? 'CHECKING' : 'COMPLETE'} onRefresh={onRefresh} onOpenSettings={onOpenSettings} />)

    await user.click(screen.getByRole('button', { name: status === 'CHECKING' ? 'AI 확인 중' : 'AI 상태' }))
    const dialog = screen.getByRole('dialog', { name: 'AI 연결 상태' })
    expect(within(dialog).getByRole('heading', { name: 'AI 연결 상태' })).toBeInTheDocument()
    expect(within(dialog).queryByText('로컬 AI')).not.toBeInTheDocument()
    expect(dialog.querySelector('.panel-heading .eyebrow')).not.toBeInTheDocument()
    expect(within(dialog).getByText('AI · 일반')).toBeInTheDocument()
    expect(within(dialog).queryByText('대화 · 일반')).not.toBeInTheDocument()
    const button = within(dialog).getByRole('button', { name: `AI 모델 상태: ${label}, 설정으로 이동` })
    expect(button).toBeEnabled()
    expect(button).toHaveAttribute('title', 'AI 모델 설정으로 이동')
    await user.click(button)

    expect(onOpenSettings).toHaveBeenCalledTimes(1)
    expect(onRefresh).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: status === 'CHECKING' ? 'AI 확인 중' : 'AI 상태' })).toHaveAttribute('aria-expanded', 'false')
  })

  it.each(['{Enter}', ' '])('키보드 %s로도 설정 이동을 실행한다', async (key) => {
    const onOpenSettings = vi.fn()
    const user = userEvent.setup()
    render(<AiStatusButton status={readyStatus} checkPhase="COMPLETE" onRefresh={vi.fn()} onOpenSettings={onOpenSettings} />)
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    screen.getByRole('button', { name: 'AI 모델 상태: 준비됨, 설정으로 이동' }).focus()
    await user.keyboard(key)
    expect(onOpenSettings).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('임베딩 설치 버튼은 기존 설치 확인을 열며 설정으로 이동하지 않는다', async () => {
    const onOpenSettings = vi.fn()
    const user = userEvent.setup()
    render(<AiStatusButton status={{ ...readyStatus, embedding: { ...readyStatus.embedding, status: 'NOT_INSTALLED' } }} checkPhase="COMPLETE" onRefresh={vi.fn()} onOpenSettings={onOpenSettings} />)
    await user.click(screen.getByRole('button', { name: 'AI 상태' }))
    await user.click(screen.getByRole('button', { name: '임베딩 모델 설치' }))
    expect(screen.getByLabelText('모델 설치 확인')).toHaveTextContent('임베딩 모델 qwen3-embedding:0.6b')
    expect(onOpenSettings).not.toHaveBeenCalled()
    expect(fetch).not.toHaveBeenCalled()
  })
})
