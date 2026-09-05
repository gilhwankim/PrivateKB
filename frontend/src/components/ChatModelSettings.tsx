import { useEffect, useState } from 'react'
import type { ChatModelProfile, LocalAiStatus } from '../types/localAi'
import { activeInstall, installStatusLabel } from '../hooks/useModelInstall'
import type { ModelInstallController } from '../hooks/useModelInstall'
import { CheckIcon, ChatIcon, RefreshIcon } from './icons'
import { formatBytes } from '../utils/formatBytes'
import { currentUploadBytes, PROFILE_UPLOAD_BYTES } from '../utils/documentFormats'

interface ChatModelSettingsProps {
  status: LocalAiStatus
  onSelect: (profile: ChatModelProfile) => Promise<void>
  install: ModelInstallController
}

const PROFILE_OPTIONS: Array<{
  profile: ChatModelProfile
  title: string
  summary: string
  model: string
  specification: string
  recommended?: boolean
}> = [
  {
    profile: 'DISABLED',
    title: '미사용',
    summary: '문서 관리와 의미 검색만 사용합니다.',
    model: '대화 모델을 실행하지 않음',
    specification: '추가 저장 공간과 대화 모델 메모리 사용 없음',
    recommended: true,
  },
  {
    profile: 'LOW_SPEC',
    title: '저사양',
    summary: '사무용 PC에서 짧고 빠른 답변에 적합합니다.',
    model: 'qwen3.5:2b-q4_K_M · 약 1.9GB',
    specification: 'RAM 8GB 이상 · 문맥 4,096 · 최대 출력 512 · 근거 3개',
  },
  {
    profile: 'STANDARD',
    title: '일반',
    summary: '일반 PC에서 답변 품질과 속도의 균형을 맞춥니다.',
    model: 'qwen3.5:4b · 약 3.4GB',
    specification: 'RAM 16GB 이상 · 문맥 8,192 · 최대 출력 1,024 · 근거 5개',
  },
  {
    profile: 'HIGH_SPEC',
    title: '고사양',
    summary: '여러 문서를 종합하는 더 정교한 답변에 적합합니다.',
    model: 'qwen3.5:9b · 약 6.6GB',
    specification: 'RAM 32GB 이상 · GPU VRAM 8GB 권장 · 문맥 8,192 · 최대 출력 1,536 · 근거 7개',
  },
]

export function ChatModelSettings({ status, onSelect, install }: ChatModelSettingsProps) {
  const [saving, setSaving] = useState<ChatModelProfile | null>(null)
  const [selectionError, setSelectionError] = useState('')
  const [confirmInstall, setConfirmInstall] = useState(false)
  const installJob = install.job?.role === 'CHAT' && install.job.model === status.chatProfile.model ? install.job : null
  const selected = status.chatProfile.profile
  const selectedOption = PROFILE_OPTIONS.find((option) => option.profile === selected) ?? PROFILE_OPTIONS[0]
  const installAvailable = selected !== 'DISABLED'
    && status.ollama.status === 'CONNECTED'
    && status.chat.status === 'NOT_INSTALLED'
  const selectedDescription = selected === 'DISABLED'
    ? 'AI 검색은 꺼져 있고 문서 관리와 의미 검색은 계속 사용할 수 있습니다.'
    : install.startingRole === 'CHAT'
      ? '모델 설치를 요청하고 있습니다.'
      : install.canRetry && installJob
        ? '설치 진행 상태를 다시 확인해 주세요. 새 다운로드를 시작하지 않고 기존 작업을 확인합니다.'
        : activeInstall(installJob)
          ? `${status.chatProfile.model} 모델을 설치하고 있습니다. 다른 메뉴에서도 설치는 계속됩니다.`
          : install.refreshing && installJob?.status === 'COMPLETED'
            ? '설치가 완료되어 AI 사용 가능 상태를 확인하고 있습니다.'
            : status.chat.status === 'READY'
              ? `${status.chatProfile.model} 모델을 사용할 준비가 되었습니다.`
              : `${status.chatProfile.model} 모델 설치가 필요합니다.`

  useEffect(() => { setConfirmInstall(false) }, [selected, install.busy, installAvailable])

  const select = async (profile: ChatModelProfile) => {
    if (profile === selected || saving || install.busy) return
    setSaving(profile)
    setSelectionError('')
    setConfirmInstall(false)
    try {
      await onSelect(profile)
    } catch (error) {
      setSelectionError(error instanceof Error ? error.message : '대화 모델 설정을 저장할 수 없습니다.')
    } finally {
      setSaving(null)
    }
  }

  const beginInstall = () => {
    if (!installAvailable || install.busy || saving) return
    setConfirmInstall(false)
    void install.start('CHAT')
  }

  return (
    <section className="settings-page" aria-labelledby="chat-model-settings-title">
      <div className="settings-heading">
        <div className="soft-icon"><ChatIcon /></div>
        <div>
          <p className="eyebrow">로컬 AI 설정</p>
          <h2 id="chat-model-settings-title">대화 모델 사용 방식</h2>
          <p>선택한 사양에 따라 새 문서의 파일 크기 제한도 바뀝니다. 임베딩 모델과 기존 문서는 그대로 유지됩니다.</p>
        </div>
      </div>

      <div className="chat-profile-grid" role="radiogroup" aria-label="대화 모델 사용 방식">
        {PROFILE_OPTIONS.map((option) => {
          const checked = selected === option.profile
          return (
            <button
              key={option.profile}
              type="button"
              className={`chat-profile-card ${checked ? 'selected' : ''}`}
              role="radio"
              aria-checked={checked}
              disabled={Boolean(saving) || install.busy}
              onClick={() => void select(option.profile)}
            >
              <span className="chat-profile-card-heading">
                <span><strong>{option.title}</strong>{option.recommended && <small>최초 실행 권장</small>}</span>
                <i>{checked ? <CheckIcon width={15} height={15} /> : null}</i>
              </span>
              <span className="chat-profile-summary">{option.summary}</span>
              <span className="chat-profile-model">{option.model}</span>
              <span className="chat-profile-specification">{option.specification}</span>
              <span className="chat-profile-upload-limit">파일당 최대 {formatBytes(PROFILE_UPLOAD_BYTES[option.profile])}</span>
              {saving === option.profile && <span className="chat-profile-saving"><RefreshIcon className="spin" width={14} height={14} /> 저장 중</span>}
            </button>
          )
        })}
      </div>

      <div className="selected-chat-profile" aria-live="polite">
        <div>
          <strong>현재 설정 · {selectedOption.title}</strong>
          <p>{selectedDescription}</p>
          <p className="selected-upload-limit">현재 업로드 제한 · 파일당 최대 {formatBytes(currentUploadBytes(status.chatProfile))}</p>
        </div>
        {(installAvailable || install.startingRole === 'CHAT' || activeInstall(installJob)) && <button type="button" className="primary-button" disabled={install.busy || Boolean(saving)} onClick={() => { if (!install.busy) setConfirmInstall(true) }}>{install.startingRole === 'CHAT' ? '설치 요청 중' : activeInstall(installJob) ? '설치 중' : install.refreshing ? '상태 확인 중' : '선택한 모델 설치'}</button>}
      </div>

      {confirmInstall && installAvailable && !install.busy && (
        <section className="settings-install-confirmation" aria-label="대화 모델 설치 확인">
          <div><strong>{status.chatProfile.displayName} 모델을 다운로드할까요?</strong><p>{status.chatProfile.model} 약 {formatEstimatedBytes(status.chatProfile.estimatedDownloadBytes)}를 Ollama 모델 저장소에 다운로드합니다. 문서·질문·답변은 전송하지 않습니다.</p></div>
          <div><button type="button" onClick={() => setConfirmInstall(false)}>취소</button><button type="button" className="primary-button" onClick={beginInstall}>설치 시작</button></div>
        </section>
      )}
      {installJob && installJob.status !== 'COMPLETED' && (
        <div className={`settings-install-progress ${installJob.status === 'FAILED' ? 'failed' : ''}`} role="status">
          <div><strong>{installStatusLabel(installJob.status)}</strong><span>{installJob.model}</span></div>
          <div className="progress-track"><i style={{ width: `${installJob.progressPercent}%` }} /></div>
          <div><span>{installJob.progressPercent}%{installJob.totalBytes > 0 ? ` · ${formatBytes(installJob.completedBytes)} / ${formatBytes(installJob.totalBytes)}` : ''}</span>{activeInstall(installJob) && <button type="button" disabled={install.cancelling || installJob.status === 'CANCEL_REQUESTED'} onClick={() => void install.cancel()}>{install.cancelling || installJob.status === 'CANCEL_REQUESTED' ? '취소 요청 중' : '취소'}</button>}</div>
        </div>
      )}
      {install.busy && install.job?.role === 'EMBEDDING' && <p className="settings-install-notice">임베딩 모델을 설치하고 있습니다. AI 상태에서 진행 상황을 확인할 수 있습니다.</p>}
      {(selectionError || install.error) && <p className="settings-error" role="alert">{selectionError || install.error}</p>}
      {install.canRetry && <button type="button" className="secondary-button" onClick={() => void install.retry()}>설치 상태 다시 확인</button>}

      <div className="settings-note">
        <strong>동작 원칙</strong>
        <p>프로필을 선택해도 다운로드는 자동으로 시작되지 않습니다. 이미 설치된 다른 Ollama 모델은 삭제하지 않으며, 선택한 정확한 모델이 준비되지 않으면 다른 모델로 자동 전환하지 않습니다.</p>
        <p>파일 제한은 실제 PC 사양을 자동 측정한 값이 아니라 선택한 옵션의 상한입니다. 미사용은 25MB를 적용합니다. OCR은 최대 20쪽, 추출문은 최대 500만 자이며 파일 형식과 내용에 따라 처리가 제한될 수 있습니다.</p>
      </div>
    </section>
  )
}

function formatEstimatedBytes(bytes: number) {
  if (bytes <= 0) return '0B'
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)}GB`
  return `${Math.round(bytes / 1_000_000)}MB`
}
