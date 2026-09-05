import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { activeInstall, installStatusLabel } from '../hooks/useModelInstall'
import type { ModelInstallController } from '../hooks/useModelInstall'
import type { ChatProfileDetails, LocalAiCheckPhase, LocalAiStatus, LocalAiModelStatus, ModelInstallJob, ModelInstallRole, OllamaConnectionStatus } from '../types/localAi'
import { CloseIcon, RefreshIcon } from './icons'
import { formatBytes } from '../utils/formatBytes'

interface AiStatusButtonProps {
  status: LocalAiStatus
  checkPhase: LocalAiCheckPhase
  onRefresh: () => void | Promise<void>
  onOpenSettings: () => void
  install: ModelInstallController
}

export function AiStatusButton({ status, checkPhase, onRefresh, onOpenSettings, install }: AiStatusButtonProps) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [installChoice, setInstallChoice] = useState<ModelInstallRole | null>(null)
  const readyCount = [status.embedding, status.chat].filter((model) => model.status === 'READY').length
  const targetCount = status.chatProfile.profile === 'DISABLED' ? 1 : 2

  useEffect(() => {
    if (!open) return
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
        buttonRef.current?.focus()
      }
    }
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (
        panelRef.current &&
        !panelRef.current.contains(event.target as Node) &&
        !buttonRef.current?.contains(event.target as Node)
      ) {
        setOpen(false)
      }
    }
    document.addEventListener('keydown', closeOnEscape)
    document.addEventListener('mousedown', closeOnOutsideClick)
    return () => {
      document.removeEventListener('keydown', closeOnEscape)
      document.removeEventListener('mousedown', closeOnOutsideClick)
    }
  }, [open])

  useEffect(() => { setInstallChoice(null) }, [install.busy, status.chatProfile.model])

  const beginInstall = () => {
    if (!installChoice || install.busy) return
    void install.start(installChoice)
    setInstallChoice(null)
  }

  const waiting = checkPhase === 'WAITING'
  const checking = checkPhase === 'CHECKING'
  const hasError = checkPhase === 'ERROR' || (!waiting && !checking && status.ollama.status !== 'CONNECTED')
  const buttonLabel = waiting ? 'AI 확인 대기' : checking ? 'AI 확인 중' : checkPhase === 'ERROR' ? 'AI 확인 실패' : 'AI 상태'

  const openAiSettings = () => {
    setOpen(false)
    setInstallChoice(null)
    onOpenSettings()
  }

  return (
    <div className="ai-status-wrap">
      <button
        ref={buttonRef}
        type="button"
        className={`ai-status-button ${hasError ? 'has-error' : ''}`}
        aria-haspopup="dialog"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span className={`status-dot ${checking ? 'checking' : hasError ? 'error' : ''}`} />
        <span>{buttonLabel}</span>
        <RefreshIcon className={checking ? 'spin' : ''} width={16} height={16} />
      </button>

      {open && (
        <div ref={panelRef} className="ai-status-panel" role="dialog" aria-label="AI 연결 상태">
          <div className="panel-heading">
            <h2>AI 연결 상태</h2>
            <button type="button" className="icon-button" aria-label="닫기" onClick={() => setOpen(false)}>
              <CloseIcon width={18} height={18} />
            </button>
          </div>

          <p className="last-checked">마지막 확인 {formatCheckedAt(status.lastCheckedAt)}</p>
          <div className="status-list">
            <StatusRow label="Ollama" value={ollamaLabel(status.ollama.status)} tone={ollamaTone(status.ollama.status)} />
            <StatusRow label="임베딩" value={modelLabel(status.embedding.status)} tone={modelTone(status.embedding.status)} sublabel={status.embedding.model ?? undefined} action={installAction('EMBEDDING', status.embedding.status, status.ollama.status, install, setInstallChoice)} />
            <StatusRow label={`AI · ${status.chatProfile.displayName}`} value={modelLabel(status.chat.status)} tone={modelTone(status.chat.status)} sublabel={status.chat.model ?? 'AI 모델을 사용하지 않음'} onStatusClick={openAiSettings} action={installAction('CHAT', status.chat.status, status.ollama.status, install, setInstallChoice)} />
          </div>

          {hasError && (
            <p className="status-guidance">{checkPhase === 'ERROR' ? 'AI 상태 확인을 완료하지 못했습니다. 프로그램 준비 상태를 확인한 뒤 다시 시도해 주세요.' : 'Ollama를 실행한 뒤 다시 확인해 주세요. 문서 업로드와 관리는 계속 사용할 수 있습니다.'}</p>
          )}
          {status.ollama.status === 'CONNECTED' && readyCount < targetCount && (
            <p className="status-guidance">누락된 모델은 용량과 외부 다운로드 안내를 확인한 뒤 이 화면에서 설치할 수 있습니다.</p>
          )}

          {installChoice && !install.busy && <InstallConfirmation role={installChoice} chatProfile={status.chatProfile} onCancel={() => setInstallChoice(null)} onConfirm={beginInstall} />}
          {install.startingRole && <p className="status-guidance" role="status">모델 설치를 요청하고 있습니다.</p>}
          {install.job && install.job.status !== 'COMPLETED' && <InstallProgress job={install.job} cancelling={install.cancelling} onCancel={() => void install.cancel()} />}
          {install.error && <p className="install-error" role="alert">{install.error}</p>}
          {install.canRetry && <button type="button" className="secondary-button full" onClick={() => void install.retry()}>설치 상태 다시 확인</button>}

          <button type="button" className="primary-button full" disabled={waiting || checking} onClick={onRefresh}>
            <RefreshIcon className={checking ? 'spin' : ''} width={17} height={17} />
            {waiting ? '프로그램 준비 대기 중' : checking ? '확인하고 있습니다' : '상태 다시 확인'}
          </button>
        </div>
      )}
    </div>
  )
}

function StatusRow({ label, value, tone, sublabel, action, onStatusClick }: { label: string; value: string; tone: string; sublabel?: string; action?: ReactNode; onStatusClick?: () => void }) {
  return (
    <div className="status-row">
      <div><strong>{label}</strong>{sublabel && <small>{sublabel}</small>}</div>
      <div className="status-row-actions">
        {onStatusClick ? (
          <button type="button" className={`status-badge settings-status-link ${tone}`} aria-label={`AI 모델 상태: ${value}, 설정으로 이동`} title="AI 모델 설정으로 이동" onClick={onStatusClick}><i aria-hidden="true" />{value}</button>
        ) : (
          <span className={`status-badge ${tone}`}><i />{value}</span>
        )}
        {action}
      </div>
    </div>
  )
}

function installAction(
  role: ModelInstallRole,
  modelStatus: LocalAiModelStatus,
  ollamaStatus: OllamaConnectionStatus,
  install: ModelInstallController,
  choose: (role: ModelInstallRole) => void,
) {
  if (install.startingRole === role) return <span className="install-inline-progress">설치 요청 중</span>
  if (install.job?.role === role && activeInstall(install.job)) {
    return <span className="install-inline-progress">{install.job.progressPercent}%</span>
  }
  if (install.busy) return undefined
  if (modelStatus !== 'NOT_INSTALLED' || ollamaStatus !== 'CONNECTED') return undefined
  const label = role === 'EMBEDDING' ? '임베딩 모델 설치' : 'AI 모델 설치'
  return <button type="button" className="model-install-button" onClick={() => choose(role)}>{label}</button>
}

function InstallConfirmation({ role, chatProfile, onCancel, onConfirm }: { role: ModelInstallRole; chatProfile: ChatProfileDetails; onCancel: () => void; onConfirm: () => void }) {
  const chat = role === 'CHAT'
  const model = chat ? chatProfile.model : 'qwen3-embedding:0.6b'
  const size = chat ? formatEstimatedBytes(chatProfile.estimatedDownloadBytes) : '639MB'
  return <section className="install-confirmation" aria-label="모델 설치 확인"><strong>{chat ? `AI 모델 ${model}` : `임베딩 모델 ${model}`}</strong><p>약 {size}를 인터넷에서 다운로드합니다. 모델은 PrivateKB가 아닌 Ollama의 모델 저장소에 보관되며 문서·질문·답변은 전송하지 않습니다.</p><div><button type="button" onClick={onCancel}>취소</button><button type="button" className="confirm-install-button" onClick={onConfirm}>설치 시작</button></div></section>
}

function InstallProgress({ job, cancelling, onCancel }: { job: ModelInstallJob; cancelling: boolean; onCancel: () => void }) {
  const cancelPending = cancelling || job.status === 'CANCEL_REQUESTED'
  return <div className={`install-progress ${job.status === 'FAILED' ? 'failed' : ''}`} role="status"><div><strong>{installStatusLabel(job.status)}</strong><span>{job.model}</span></div><div className="progress-track"><i style={{ width: `${job.progressPercent}%` }} /></div><div className="install-progress-footer"><span>{job.progressPercent}%{job.totalBytes > 0 ? ` · 현재 파일 ${formatBytes(job.completedBytes)} / ${formatBytes(job.totalBytes)}` : ''}</span>{activeInstall(job) && <button type="button" disabled={cancelPending} onClick={onCancel}>{cancelPending ? '취소 요청 중' : '취소'}</button>}</div></div>
}

function ollamaLabel(status: OllamaConnectionStatus) {
  return ({ CHECKING: '확인 중', CONNECTED: '연결됨', NOT_RUNNING: '실행 안 됨', INCOMPATIBLE: '호환되지 않음', CHECK_FAILED: '확인 실패' })[status]
}

function modelLabel(status: LocalAiModelStatus) {
  return ({ CHECKING: '확인 중', DISABLED: '미사용', NOT_INSTALLED: '설치되지 않음', READY: '준비됨', INCOMPATIBLE: '호환되지 않음', ERROR: '확인 불가' })[status]
}

function ollamaTone(status: OllamaConnectionStatus) {
  if (status === 'CONNECTED') return 'success'
  if (status === 'CHECKING') return 'neutral'
  return 'danger'
}

function modelTone(status: LocalAiModelStatus) {
  if (status === 'READY') return 'success'
  if (status === 'CHECKING' || status === 'DISABLED') return 'neutral'
  return status === 'NOT_INSTALLED' ? 'warning' : 'danger'
}

function formatCheckedAt(value: string | null) {
  if (!value) return '기록 없음'
  return new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function formatEstimatedBytes(bytes: number) {
  if (bytes <= 0) return '0B'
  if (bytes >= 1_000_000_000) return `${(bytes / 1_000_000_000).toFixed(1)}GB`
  return `${Math.round(bytes / 1_000_000)}MB`
}
