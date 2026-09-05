import { useCallback, useEffect, useRef, useState } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react'
import { streamGroundedAnswer } from './api/answer'
import { discardDesktopSelection, getDesktopRuntimeStatus, importDesktopSelection, isDesktopShell, selectDesktopFiles, selectDesktopFolder } from './api/desktopBridge'
import type { DesktopImportProgress, DesktopSelectionPreview } from './api/desktopBridge'
import { getDocuments, retryDocumentEmbedding } from './api/documents'
import { getLocalAiStatus, resumeAvailableIndexes, selectChatProfile, triggerLocalAiCheck, uploadDocument } from './api/localAi'
import { searchKnowledge } from './api/search'
import { AiStatusButton } from './components/AiStatusButton'
import { ChatModelSettings } from './components/ChatModelSettings'
import { RevealSourceButton } from './components/RevealSourceButton'
import { SelectionPreviewDialog } from './components/SelectionPreviewDialog'
import { SupportedFormatsGuide } from './components/SupportedFormatsGuide'
import { useModelInstall } from './hooks/useModelInstall'
import { DOCUMENT_ACCEPT, currentUploadBytes } from './utils/documentFormats'
import { formatBytes } from './utils/formatBytes'
import { ChatIcon, CheckIcon, ChevronIcon, DocumentIcon, FolderIcon, HomeIcon, LayersIcon, LockIcon, RefreshIcon, SearchIcon, SettingsIcon, ShieldIcon, SparkleIcon, UploadIcon } from './components/icons'
import type { ChatModelProfile, LocalAiCheckPhase, LocalAiStatus } from './types/localAi'
import type { DocumentPage, DocumentStatusItem } from './types/documents'
import type { GroundedCitation } from './types/answer'
import type { SearchResult } from './types/search'

const initialStatus: LocalAiStatus = {
  ollama: { status: 'CHECKING', version: null, errorCode: null },
  embedding: { model: 'qwen3-embedding:0.6b', status: 'CHECKING', digest: null, sizeBytes: 0, errorCode: null },
  chat: { model: null, status: 'DISABLED', digest: null, sizeBytes: 0, errorCode: null },
  chatProfile: { profile: 'DISABLED', displayName: '미사용', model: null, estimatedDownloadBytes: 0, contextLength: 0, maximumGeneratedTokens: 0, maximumSourceDocuments: 0 },
  capabilities: { documentManagement: false, semanticSearch: false, groundedAnswer: false },
  lastCheckedAt: null,
  checkInProgress: true,
}

type BackendConnectionStatus = 'STARTING' | 'CHECKING' | 'READY' | 'ERROR'
type AppView = 'HOME' | 'DOCUMENTS' | 'SETTINGS'
type FocusedContentArea = 'SEARCH' | 'QUESTION'
const RECENT_SEARCHES_STORAGE_KEY = 'privatekb.recent-searches'
const MAX_RECENT_SEARCHES = 3
const RECENT_DOCUMENT_LIMIT = 10
const DOCUMENT_PAGE_SIZE = 20
const DOCUMENT_STATUS_POLL_INTERVAL_MS = 1500
export const BACKEND_STARTUP_MAX_ATTEMPTS = 30
export const BACKEND_STARTUP_RETRY_INTERVAL_MS = 1000
export const AI_STATUS_MAX_ATTEMPTS = 30
export const AI_STATUS_RETRY_INTERVAL_MS = 1000
export const STARTUP_STATUS_EXIT_DURATION_MS = 360

export default function App() {
  const [aiStatus, setAiStatus] = useState<LocalAiStatus>(initialStatus)
  const [aiCheckPhase, setAiCheckPhase] = useState<LocalAiCheckPhase>('WAITING')
  const [aiProbeAttempt, setAiProbeAttempt] = useState(0)
  const [backendConnection, setBackendConnection] = useState<BackendConnectionStatus>('STARTING')
  const [backendProbeAttempt, setBackendProbeAttempt] = useState(0)
  const [startupStatusPanelEnabled, setStartupStatusPanelEnabled] = useState(true)
  const [startupStatusRendered, setStartupStatusRendered] = useState(true)
  const [startupStatusClosing, setStartupStatusClosing] = useState(false)
  const [activeView, setActiveView] = useState<AppView>('HOME')
  const [focusedContent, setFocusedContent] = useState<{ area: FocusedContentArea, sequence: number } | null>(null)
  const [recentDocuments, setRecentDocuments] = useState<DocumentStatusItem[]>([])
  const [recentDocumentsLoading, setRecentDocumentsLoading] = useState(false)
  const [recentDocumentsError, setRecentDocumentsError] = useState('')
  const [documentPage, setDocumentPage] = useState(0)
  const [allDocuments, setAllDocuments] = useState<DocumentStatusItem[]>([])
  const [allDocumentsTotal, setAllDocumentsTotal] = useState(0)
  const [allDocumentsHasNext, setAllDocumentsHasNext] = useState(false)
  const [allDocumentsLoading, setAllDocumentsLoading] = useState(false)
  const [allDocumentsError, setAllDocumentsError] = useState('')
  const [documentRefreshVersion, setDocumentRefreshVersion] = useState(0)
  const [retryingDocumentId, setRetryingDocumentId] = useState<string | null>(null)
  const [uploadMessage, setUploadMessage] = useState('')
  const [uploading, setUploading] = useState(false)
  const [importProgress, setImportProgress] = useState<DesktopImportProgress | null>(null)
  const [importNotice, setImportNotice] = useState('')
  const [selectingDocuments, setSelectingDocuments] = useState(false)
  const [selectionPreview, setSelectionPreview] = useState<DesktopSelectionPreview | null>(null)
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchResults, setSearchResults] = useState<SearchResult[] | null>(null)
  const [searchError, setSearchError] = useState('')
  const [recentSearches, setRecentSearches] = useState<string[]>(loadRecentSearches)
  const [question, setQuestion] = useState('')
  const [answering, setAnswering] = useState(false)
  const [answerText, setAnswerText] = useState('')
  const [answerCitations, setAnswerCitations] = useState<GroundedCitation[]>([])
  const [answerMessage, setAnswerMessage] = useState('')
  const answerAbortRef = useRef<AbortController | null>(null)
  const statusCheckAbortRef = useRef<AbortController | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const searchCardRef = useRef<HTMLElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)
  const answerCardRef = useRef<HTMLElement>(null)
  const questionInputRef = useRef<HTMLTextAreaElement>(null)
  const settingsNavRef = useRef<HTMLButtonElement>(null)
  const focusSequenceRef = useRef(0)

  const applyBackendStatus = useCallback(async (status: LocalAiStatus, signal: AbortSignal) => {
    setAiStatus(status)
    setAiProbeAttempt(0)
    if (!isAiStatusPending(status)) {
      setAiCheckPhase('COMPLETE')
      if (status.capabilities.semanticSearch) {
        try {
          await resumeAvailableIndexes(signal)
          setDocumentRefreshVersion((version) => version + 1)
        } catch (error) {
          if (isAbortError(error)) throw error
          // 색인 재개 실패는 백엔드 연결 실패로 오인하지 않고 다음 실행이나 수동 점검에서 재시도한다.
        }
      }
      return
    }
    setAiCheckPhase('CHECKING')
    let current = status
    try {
      if (!current.checkInProgress) {
        current = await triggerLocalAiCheck(signal)
        setAiStatus(current)
        if (!isAiStatusPending(current)) {
          setAiCheckPhase('COMPLETE')
          setAiProbeAttempt(0)
          if (current.capabilities.semanticSearch) {
            try {
              await resumeAvailableIndexes(signal)
              setDocumentRefreshVersion((version) => version + 1)
            } catch (error) {
              if (isAbortError(error)) throw error
            }
          }
          return
        }
      }
      for (let attempt = 1; attempt <= AI_STATUS_MAX_ATTEMPTS; attempt += 1) {
        setAiProbeAttempt(attempt)
        await wait(AI_STATUS_RETRY_INTERVAL_MS, signal)
        current = await getLocalAiStatus(signal)
        setAiStatus(current)
        if (!isAiStatusPending(current)) {
          setAiCheckPhase('COMPLETE')
          setAiProbeAttempt(0)
          if (current.capabilities.semanticSearch) {
            try {
              await resumeAvailableIndexes(signal)
              setDocumentRefreshVersion((version) => version + 1)
            } catch (error) {
              if (isAbortError(error)) throw error
            }
          }
          return
        }
      }
      setAiStatus(aiCheckTimeoutStatus(current))
      setAiCheckPhase('ERROR')
      setAiProbeAttempt(0)
    } catch (error) {
      if (isAbortError(error)) throw error
      setAiStatus(aiCheckFailedStatus(current))
      setAiCheckPhase('ERROR')
      setAiProbeAttempt(0)
    }
  }, [])

  const refreshStatus = useCallback(async (showStartupPanel = false) => {
    statusCheckAbortRef.current?.abort()
    setUploadMessage('')
    const controller = new AbortController()
    statusCheckAbortRef.current = controller
    setStartupStatusPanelEnabled(showStartupPanel)
    setBackendConnection('CHECKING')
    setBackendProbeAttempt(0)
    setAiCheckPhase('WAITING')
    setAiProbeAttempt(0)
    try {
      const started = await triggerLocalAiCheck(controller.signal)
      setBackendConnection('READY')
      await applyBackendStatus(started, controller.signal)
    } catch (error) {
      if (isAbortError(error)) return
      setAiStatus(unavailableStatus())
      setBackendConnection('ERROR')
      setAiCheckPhase('WAITING')
    } finally {
      if (statusCheckAbortRef.current === controller) {
        statusCheckAbortRef.current = null
      }
    }
  }, [applyBackendStatus])

  const modelInstall = useModelInstall(refreshStatus)

  const handleChatProfileSelect = useCallback(async (profile: ChatModelProfile) => {
    statusCheckAbortRef.current?.abort()
    const controller = new AbortController()
    statusCheckAbortRef.current = controller
    setAiCheckPhase('CHECKING')
    setAiProbeAttempt(0)
    try {
      const updated = await selectChatProfile(profile, controller.signal)
      setBackendConnection('READY')
      await applyBackendStatus(updated, controller.signal)
    } catch (error) {
      if (isAbortError(error)) return
      setAiCheckPhase('ERROR')
      throw error
    } finally {
      if (statusCheckAbortRef.current === controller) statusCheckAbortRef.current = null
    }
  }, [applyBackendStatus])

  useEffect(() => {
    const controller = new AbortController()
    const connectWhenReady = async () => {
      setStartupStatusPanelEnabled(true)
      setBackendConnection('STARTING')
      setBackendProbeAttempt(0)
      setAiCheckPhase('WAITING')
      setAiProbeAttempt(0)
      for (let attempt = 1; attempt <= BACKEND_STARTUP_MAX_ATTEMPTS; attempt += 1) {
        setBackendProbeAttempt(attempt)
        let status: LocalAiStatus
        try {
          if (isDesktopShell()) {
            const runtime = await getDesktopRuntimeStatus()
            if (controller.signal.aborted) return
            if (runtime.phase === 'ERROR' || runtime.phase === 'STOPPED') {
              setAiStatus(unavailableStatus())
              setBackendConnection('ERROR')
              setAiCheckPhase('WAITING')
              return
            }
            if (runtime.phase === 'STARTING') {
              if (attempt < BACKEND_STARTUP_MAX_ATTEMPTS) {
                await wait(BACKEND_STARTUP_RETRY_INTERVAL_MS, controller.signal)
              }
              continue
            }
          }
          status = await getLocalAiStatus(controller.signal)
        } catch (error) {
          if (isAbortError(error)) return
          if (attempt < BACKEND_STARTUP_MAX_ATTEMPTS) {
            setBackendConnection('STARTING')
            await wait(BACKEND_STARTUP_RETRY_INTERVAL_MS, controller.signal)
          }
          continue
        }
        setBackendConnection('READY')
        setBackendProbeAttempt(0)
        try {
          await applyBackendStatus(status, controller.signal)
        } catch (error) {
          if (!isAbortError(error)) {
            setAiStatus(aiCheckFailedStatus(status))
            setAiCheckPhase('ERROR')
          }
        }
        return
      }
      if (!controller.signal.aborted) {
        setAiStatus(unavailableStatus())
        setBackendConnection('ERROR')
        setAiCheckPhase('WAITING')
      }
    }
    void connectWhenReady()
    return () => {
      controller.abort()
      statusCheckAbortRef.current?.abort()
      answerAbortRef.current?.abort()
    }
  }, [applyBackendStatus])

  const handleFile = async (file?: File) => {
    if (!file || activeView !== 'DOCUMENTS' || !documentReady || uploading || selectingDocuments) return
    const maximumBytes = currentUploadBytes(aiStatus.chatProfile)
    if (file.size > maximumBytes) {
      setUploadMessage(`현재 설정에서는 파일당 최대 ${formatBytes(maximumBytes)}까지 추가할 수 있습니다. 설정에서 사양을 확인해 주세요.`)
      return
    }
    setUploading(true)
    setUploadMessage('')
    try {
      await uploadDocument(file)
      setUploadMessage(`${file.name} 문서를 안전하게 접수했습니다.`)
      setDocumentRefreshVersion((version) => version + 1)
    } catch (error) {
      setUploadMessage(error instanceof Error ? error.message : '문서를 업로드할 수 없습니다.')
    } finally {
      setUploading(false)
    }
  }

  const handleAddFiles = async () => {
    if (activeView !== 'DOCUMENTS' || !documentReady || uploading || selectingDocuments) return
    if (!isDesktopShell()) {
      fileInputRef.current?.click()
      return
    }
    await openDesktopSelection('FILES')
  }

  const openDesktopSelection = async (mode: 'FILES' | 'FOLDER') => {
    if (activeView !== 'DOCUMENTS' || !documentReady || uploading || selectingDocuments) return
    setSelectingDocuments(true)
    setUploadMessage('')
    try {
      const preview = mode === 'FILES' ? await selectDesktopFiles() : await selectDesktopFolder()
      if (preview) setSelectionPreview(preview)
    } catch (error) {
      setUploadMessage(error instanceof Error ? error.message : '선택한 문서 범위를 확인할 수 없습니다.')
    } finally {
      setSelectingDocuments(false)
    }
  }

  const closeSelectionPreview = async () => {
    const current = selectionPreview
    setSelectionPreview(null)
    setImportNotice('')
    if (current) {
      try {
        await discardDesktopSelection(current.selectionId)
      } catch {
        // 선택 정보는 프로세스 안에만 있고 제한 수를 넘으면 자동 폐기된다.
      }
    }
  }

  const confirmDesktopImport = async () => {
    if (!selectionPreview || selectionPreview.candidateCount === 0 || uploading) return
    setUploading(true)
    setImportNotice('')
    setImportProgress({
      requestedCount: selectionPreview.candidateCount,
      completedCount: 0,
      acceptedCount: 0,
      duplicateCount: 0,
      failedCount: 0,
      stoppedCount: 0,
      stopReason: null,
    })
    setUploadMessage('')
    try {
      const result = await importDesktopSelection(selectionPreview.selectionId, setImportProgress)
      const registeredCount = result.acceptedCount + result.duplicateCount
      if (result.stopReason === 'INSUFFICIENT_STORAGE') {
        const unregisteredCount = result.failedCount + result.stoppedCount
        setImportNotice(`저장 공간이 부족하여 문서 등록을 중단했습니다. ${registeredCount.toLocaleString()}개는 등록되었고 ${unregisteredCount.toLocaleString()}개는 등록되지 않았습니다. 저장 드라이브에 파일 크기 외 최소 512MB의 여유 공간을 확보한 뒤 다시 시도해 주세요.`)
      } else {
        setUploadMessage(`${registeredCount}개 문서를 등록했습니다.`)
      }
      setDocumentRefreshVersion((version) => version + 1)
      if (result.failedCount === 0) setSelectionPreview(null)
    } catch (error) {
      setUploadMessage(error instanceof Error ? error.message : '선택한 문서를 추가할 수 없습니다.')
    } finally {
      setUploading(false)
      setImportProgress(null)
    }
  }

  const semanticReady = aiStatus.capabilities.semanticSearch
  const answerReady = aiStatus.capabilities.groundedAnswer
  const documentReady = aiStatus.capabilities.documentManagement
  const capabilityStates = [documentReady, semanticReady, answerReady]
  const availableCapabilityCount = capabilityStates.filter(Boolean).length
  const backendStarting = backendConnection === 'STARTING'
  const aiChecking = aiCheckPhase === 'WAITING' || aiCheckPhase === 'CHECKING'
  const readinessChecking = backendStarting || backendConnection === 'CHECKING' || (backendConnection !== 'ERROR' && aiChecking)
  const startupStatusVisible = startupStatusPanelEnabled && (backendConnection !== 'READY' || aiCheckPhase !== 'COMPLETE')
  const startupStatusError = backendConnection === 'ERROR' || aiCheckPhase === 'ERROR'

  useEffect(() => {
    if (startupStatusVisible) {
      setStartupStatusRendered(true)
      setStartupStatusClosing(false)
      return
    }
    if (!startupStatusRendered) return
    setStartupStatusClosing(true)
    const timeout = window.setTimeout(() => {
      setStartupStatusRendered(false)
      setStartupStatusClosing(false)
    }, STARTUP_STATUS_EXIT_DURATION_MS)
    return () => window.clearTimeout(timeout)
  }, [startupStatusRendered, startupStatusVisible])

  useEffect(() => {
    if (backendConnection !== 'READY') return
    const controller = new AbortController()
    let pollTimeout: number | undefined
    const home = activeView === 'HOME'

    const applyPage = (response: DocumentPage) => {
      if (home) {
        setRecentDocuments(response.documents)
        setRecentDocumentsError('')
      } else {
        const lastPage = Math.max(0, Math.ceil(response.totalElements / DOCUMENT_PAGE_SIZE) - 1)
        if (documentPage > lastPage) {
          setDocumentPage(lastPage)
          return
        }
        setAllDocuments(response.documents)
        setAllDocumentsTotal(response.totalElements)
        setAllDocumentsHasNext(response.hasNext)
        setAllDocumentsError('')
      }
      if (response.documents.some(isDocumentStillProcessing)) {
        pollTimeout = window.setTimeout(() => void load(), DOCUMENT_STATUS_POLL_INTERVAL_MS)
      }
    }

    const load = async () => {
      if (home) setRecentDocumentsLoading(true)
      else setAllDocumentsLoading(true)
      try {
        const response = await getDocuments(
          home ? 0 : documentPage,
          home ? RECENT_DOCUMENT_LIMIT : DOCUMENT_PAGE_SIZE,
          controller.signal,
        )
        applyPage(response)
      } catch (error) {
        if (isAbortError(error)) return
        const message = error instanceof Error ? error.message : '문서 목록을 불러올 수 없습니다.'
        if (home) setRecentDocumentsError(message)
        else setAllDocumentsError(message)
      } finally {
        if (!controller.signal.aborted) {
          if (home) setRecentDocumentsLoading(false)
          else setAllDocumentsLoading(false)
        }
      }
    }

    void load()
    return () => {
      controller.abort()
      if (pollTimeout !== undefined) window.clearTimeout(pollTimeout)
    }
  }, [activeView, backendConnection, documentPage, documentRefreshVersion])

  const handleDocumentRetry = async (documentVersionId: string) => {
    if (retryingDocumentId) return
    setRetryingDocumentId(documentVersionId)
    if (activeView === 'HOME') setRecentDocumentsError('')
    else setAllDocumentsError('')
    try {
      await retryDocumentEmbedding(documentVersionId)
      setDocumentRefreshVersion((version) => version + 1)
    } catch (error) {
      const message = error instanceof Error ? error.message : '문서 재처리를 시작할 수 없습니다.'
      if (activeView === 'HOME') setRecentDocumentsError(message)
      else setAllDocumentsError(message)
    } finally {
      setRetryingDocumentId(null)
    }
  }

  const handleSearch = async (requestedQuery = query) => {
    const normalizedQuery = requestedQuery.trim()
    if (normalizedQuery.length < 2 || searching || !semanticReady) return
    setSearching(true)
    setSearchError('')
    try {
      const response = await searchKnowledge(normalizedQuery)
      setSearchResults(response.results)
      rememberRecentSearch(normalizedQuery)
    } catch (error) {
      setSearchResults(null)
      setSearchError(error instanceof Error ? error.message : '문서를 검색할 수 없습니다.')
    } finally {
      setSearching(false)
    }
  }

  const rememberRecentSearch = (search: string) => {
    setRecentSearches((current) => {
      const next = [search, ...current.filter((item) => item !== search)].slice(0, MAX_RECENT_SEARCHES)
      try {
        window.localStorage.setItem(RECENT_SEARCHES_STORAGE_KEY, JSON.stringify(next))
      } catch {
        // 저장 공간을 사용할 수 없어도 현재 실행 중인 최근 검색 기록은 유지한다.
      }
      return next
    })
  }

  const handleRecentSearch = (recentSearch: string) => {
    setQuery(recentSearch)
    void handleSearch(recentSearch)
  }

  const handleAnswer = async () => {
    const normalizedQuestion = question.trim()
    if (normalizedQuestion.length < 2 || answering || !answerReady) return
    const controller = new AbortController()
    answerAbortRef.current = controller
    setAnswering(true)
    setAnswerText('')
    setAnswerCitations([])
    setAnswerMessage('')
    try {
      await streamGroundedAnswer(normalizedQuestion, {
        onCitations: setAnswerCitations,
        onToken: (text) => setAnswerText((current) => current + text),
        onRefusal: setAnswerMessage,
      }, controller.signal)
    } catch (error) {
      if (!controller.signal.aborted) {
        setAnswerMessage(error instanceof Error ? error.message : '답변을 완료할 수 없습니다.')
      }
    } finally {
      if (answerAbortRef.current === controller) {
        answerAbortRef.current = null
        setAnswering(false)
      }
    }
  }

  const handleQuestionKeyDown = (event: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey || event.nativeEvent.isComposing) return
    event.preventDefault()
    event.currentTarget.form?.requestSubmit()
  }

  const navigateTo = (view: AppView, afterNavigation?: () => void) => {
    if (view !== activeView) setUploadMessage('')
    setActiveView(view)
    window.scrollTo({ top: 0, left: 0, behavior: 'auto' })
    if (afterNavigation) window.setTimeout(afterNavigation, 0)
  }

  const changeDocumentPage = (page: number) => {
    setUploadMessage('')
    setDocumentPage(page)
  }

  const openAiSettings = () => {
    navigateTo('SETTINGS', () => settingsNavRef.current?.focus({ preventScroll: true }))
  }

  const focusContentArea = (area: FocusedContentArea) => {
    navigateTo('HOME', () => {
      const target = area === 'SEARCH' ? searchCardRef.current : answerCardRef.current
      const input = area === 'SEARCH' ? searchInputRef.current : questionInputRef.current
      const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
      target?.scrollIntoView?.({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' })
      input?.focus({ preventScroll: true })
      focusSequenceRef.current += 1
      setFocusedContent({ area, sequence: focusSequenceRef.current })
    })
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-mark"><SparkleIcon width={22} height={22} /></div>
          <strong>PrivateKB</strong>
        </div>

        <nav aria-label="주 메뉴">
          <p className="nav-label">작업공간</p>
          <button type="button" className={`nav-item ${activeView === 'HOME' ? 'active' : ''}`} onClick={() => navigateTo('HOME')}><HomeIcon /> 홈</button>
          <button type="button" className={`nav-item ${activeView === 'DOCUMENTS' ? 'active' : ''}`} onClick={() => navigateTo('DOCUMENTS')}><DocumentIcon /> 문서</button>
          <button type="button" className="nav-item" disabled={!semanticReady} title={semanticReady ? undefined : '임베딩 모델 준비 후 사용할 수 있습니다.'} onClick={() => focusContentArea('SEARCH')}><SearchIcon /> 검색</button>
          <button type="button" className="nav-item" disabled={!answerReady} title={answerReady ? undefined : '임베딩과 대화 모델 준비 후 사용할 수 있습니다.'} onClick={() => focusContentArea('QUESTION')}><ChatIcon /> 질문</button>
          <p className="nav-label lower">관리</p>
          <button ref={settingsNavRef} type="button" className={`nav-item ${activeView === 'SETTINGS' ? 'active' : ''}`} onClick={() => navigateTo('SETTINGS')}><SettingsIcon /> 설정</button>
        </nav>

      </aside>

      <main className="main-area">
        <header className="topbar">
          <div><p className="breadcrumb">내 작업공간</p><h1>{viewTitle(activeView)}</h1></div>
          <div className="topbar-actions">
            <span className="privacy-pill"><ShieldIcon width={15} height={15} /> 로컬 전용 · 외부 전송 없음</span>
            <span className={`backend-status-pill ${backendStatusTone(backendConnection)}`} aria-label={`앱 상태: ${backendStatusLabel(backendConnection)}`}><i /> 앱 상태 · {backendStatusLabel(backendConnection)}</span>
            <AiStatusButton status={aiStatus} checkPhase={aiCheckPhase} onRefresh={() => void refreshStatus()} onOpenSettings={openAiSettings} install={modelInstall} />
          </div>
        </header>

        <section className="content">
          {startupStatusRendered && (
            <div
              className={`startup-readiness-collapse ${startupStatusClosing ? 'closing' : ''}`}
              aria-hidden={startupStatusClosing || undefined}
            >
              <div className={`startup-readiness ${startupStatusError ? 'error' : 'checking'}`} role={startupStatusClosing ? undefined : startupStatusError ? 'alert' : 'status'}>
                <div className={`startup-status-row ${backendStatusTone(backendConnection)}`}>
                  <span className="startup-status-indicator" aria-hidden="true" />
                  <div><strong>프로그램 준비 · {backendStatusLabel(backendConnection)}</strong><p>{backendStatusDescription(backendConnection)}</p></div>
                  {backendStarting && <StartupProgress label="프로그램 준비 자동 확인" attempt={backendProbeAttempt} maximum={BACKEND_STARTUP_MAX_ATTEMPTS} />}
                </div>
                <div className={`startup-status-row ${aiStatusTone(aiCheckPhase)}`}>
                  <span className="startup-status-indicator" aria-hidden="true" />
                  <div><strong>로컬 AI · {aiCheckPhaseLabel(aiCheckPhase, backendConnection)}</strong><p>{aiCheckPhaseDescription(aiCheckPhase, backendConnection)}</p></div>
                  {aiCheckPhase === 'CHECKING' && <StartupProgress label="AI 자동 확인" attempt={aiProbeAttempt} maximum={AI_STATUS_MAX_ATTEMPTS} />}
                </div>
                {startupStatusError && <button type="button" onClick={() => void refreshStatus(true)}>두 상태 다시 확인</button>}
              </div>
            </div>
          )}
          <div hidden={activeView !== 'HOME'}>
          <div className="welcome-row">
            <p className="eyebrow"><SparkleIcon width={15} height={15} /> PRIVATE KNOWLEDGE BASE</p>
          </div>

          <div className="workspace-grid">
            <section ref={searchCardRef} className="search-card card">
              {focusedContent?.area === 'SEARCH' && <span key={focusedContent.sequence} className="content-focus-ring" aria-hidden="true" />}
              <div className="card-heading"><div className="soft-icon"><SearchIcon /></div><div><h3>파일 검색</h3><p>문서 속 핵심 내용을 의미 기반으로 빠르게 찾습니다.</p></div></div>
              <form className={`search-box ${!semanticReady ? 'disabled' : ''}`} onSubmit={(event) => { event.preventDefault(); void handleSearch() }}>
                <SearchIcon />
                <input ref={searchInputRef} aria-label="지식 검색" disabled={!semanticReady} value={query} onChange={(event) => setQuery(event.target.value)} placeholder={semanticReady ? '예: 최근 장애 대응에서 결정한 내용은?' : '임베딩 모델 준비 후 검색할 수 있습니다.'} />
                <button type="submit" aria-label="지식 검색 실행" disabled={!semanticReady || searching || query.trim().length < 2}>{searching ? '검색 중' : '검색'}</button>
              </form>
              {backendConnection === 'READY' && !aiStatus.checkInProgress && !semanticReady && <CapabilityNotice title="파일 검색 준비가 필요합니다" body="qwen3-embedding:0.6b 설치 상태를 확인해 주세요." onRefresh={refreshStatus} />}
              {searchError && <p className="search-error" role="alert">{searchError}</p>}
              {searchResults && <SearchResults results={searchResults} />}
              <div className="quick-prompts" aria-label="최근 질문">
                <span>최근 질문</span>
                {recentSearches.length > 0
                  ? recentSearches.map((recentSearch) => (
                    <button key={recentSearch} type="button" title={recentSearch} disabled={!semanticReady || searching} onClick={() => handleRecentSearch(recentSearch)}>
                      {summarizeRecentSearch(recentSearch)}
                    </button>
                  ))
                  : <small>아직 검색 기록이 없습니다.</small>}
              </div>
            </section>

            <section className="status-card card">
              <div className="section-title"><div><p className="eyebrow">준비 상태</p><h3>사용 가능한 기능</h3></div><span className={`mini-count ${readinessChecking ? 'checking' : ''}`} aria-label={readinessChecking ? `${capabilityStates.length}개 기능 상태 확인 중` : `${capabilityStates.length}개 기능 중 ${availableCapabilityCount}개 사용 가능`}>{readinessChecking ? '확인 중' : `${availableCapabilityCount}/${capabilityStates.length}`}</span></div>
              <CapabilityItem icon={<LayersIcon />} title="문서 보관과 파싱" ready={documentReady} checking={!documentReady && readinessChecking} />
              <CapabilityItem icon={<SearchIcon />} title="파일 검색" ready={semanticReady} checking={!semanticReady && readinessChecking} />
              <CapabilityItem icon={<LockIcon />} title="AI 검색" ready={answerReady} checking={!answerReady && readinessChecking} onStatusClick={openAiSettings} />
            </section>

            <section ref={answerCardRef} className="answer-card card">
              {focusedContent?.area === 'QUESTION' && <span key={focusedContent.sequence} className="content-focus-ring" aria-hidden="true" />}
              <div className="answer-compose">
                <div className="card-heading"><div className="soft-icon"><ChatIcon /></div><div><h3>AI 검색</h3><p>AI가 사용자 요청을 분석해 관련 파일을 찾고 결과를 요약합니다.</p></div></div>
                <form className={`answer-form ${!answerReady ? 'disabled' : ''}`} onSubmit={(event) => { event.preventDefault(); void handleAnswer() }}>
                  <textarea ref={questionInputRef} aria-label="문서에 질문" disabled={!answerReady || answering} value={question} onChange={(event) => setQuestion(event.target.value)} onKeyDown={handleQuestionKeyDown} maxLength={1000} placeholder={answerReady ? '예: 최근 장애 대응에서 결정한 후속 조치는?' : '임베딩과 대화 모델 준비 후 질문할 수 있습니다.'} />
                  <div className="answer-actions">
                    <small>{question.length}/1000</small>
                    {answering ? <button type="button" className="stop-button" onClick={() => answerAbortRef.current?.abort()}>생성 중지</button> : <button type="submit" className="primary-button" disabled={!answerReady || question.trim().length < 2}>답변 생성</button>}
                  </div>
                </form>
                {backendConnection === 'READY' && !aiStatus.checkInProgress && !answerReady && <CapabilityNotice title="AI 검색 준비가 필요합니다" body={answerCapabilityGuidance(aiStatus)} onRefresh={refreshStatus} onOpenSettings={openAiSettings} />}
              </div>
              <div className="answer-output" aria-live="polite" aria-busy={answering}>
                <div className="answer-output-heading"><strong>답변</strong>{answering && <span><i /> 생성 중</span>}</div>
                <div className="answer-response-scroll" role="region" aria-label="생성된 답변" tabIndex={0}>
                  {answerText ? <p className="answer-copy">{answerText}</p> : answerMessage ? <p className="answer-message" role="status">{answerMessage}</p> : <p className="answer-placeholder">질문을 입력하면 답변과 참고한 문서 구간이 여기에 표시됩니다.</p>}
                </div>
                {answerCitations.length > 0 && (
                  <div className="answer-citations">
                    <strong>참고한 근거</strong>
                    <div className="answer-citation-list" role="region" aria-label="참고한 근거 목록" tabIndex={0}>
                      {answerCitations.map((citation) => <article key={citation.chunkId}><div><b>[{citation.sourceNumber}] {citation.originalFilename}</b><span>관련도 {Math.round(citation.score * 100)}%</span></div><p>{citation.excerpt}</p><small>문서 버전 {citation.versionNumber} · 구간 {citation.chunkIndex + 1}</small>{citation.sourceFolderPath && <small className="citation-folder-path" title={citation.sourceFolderPath}>원본 폴더 · {citation.sourceFolderPath}</small>}<RevealSourceButton documentVersionId={citation.documentVersionId} filename={citation.originalFilename} /></article>)}
                    </div>
                  </div>
                )}
              </div>
            </section>

            <section className="documents-card card">
              <div className="section-title"><div><p className="eyebrow">내 문서</p><h3>최근 임베딩 문서</h3></div><button type="button" className="text-button" onClick={() => navigateTo('DOCUMENTS')}>전체 보기 <ChevronIcon width={16} height={16} /></button></div>
              <DocumentStatusList
                documents={recentDocuments}
                loading={recentDocumentsLoading}
                error={recentDocumentsError}
                emptyTitle="최근 처리된 문서가 없습니다"
                retryingDocumentId={retryingDocumentId}
                onRetry={handleDocumentRetry}
              />
            </section>
          </div>
          </div>
          {activeView === 'DOCUMENTS' && (
            <>
            {!isDesktopShell() && <input
              ref={fileInputRef}
              type="file"
              accept={DOCUMENT_ACCEPT}
              hidden
              onChange={(event) => {
                const file = event.target.files?.[0]
                event.target.value = ''
                void handleFile(file)
              }}
            />}
            <DocumentsPage
              documents={allDocuments}
              total={allDocumentsTotal}
              page={documentPage}
              hasNext={allDocumentsHasNext}
              loading={allDocumentsLoading}
              error={allDocumentsError}
              documentReady={documentReady}
              maxFileBytes={currentUploadBytes(aiStatus.chatProfile)}
              importing={uploading || selectingDocuments}
              uploadMessage={uploadMessage}
              onAddFiles={() => void handleAddFiles()}
              onAddFolder={() => void openDesktopSelection('FOLDER')}
              onPageChange={changeDocumentPage}
              retryingDocumentId={retryingDocumentId}
              onRetry={handleDocumentRetry}
            />
            </>
          )}
          {activeView === 'SETTINGS' && (
            <ChatModelSettings
              status={aiStatus}
              onSelect={handleChatProfileSelect}
              install={modelInstall}
            />
          )}
        </section>
      </main>
      {selectionPreview && (
        <SelectionPreviewDialog
          key={selectionPreview.selectionId}
          preview={selectionPreview}
          importing={uploading}
          progress={importProgress}
          notice={importNotice}
          onCancel={() => void closeSelectionPreview()}
          onConfirm={() => void confirmDesktopImport()}
        />
      )}
    </div>
  )
}

function DocumentsPage({
  documents,
  total,
  page,
  hasNext,
  loading,
  error,
  documentReady,
  maxFileBytes,
  importing,
  uploadMessage,
  onAddFiles,
  onAddFolder,
  onPageChange,
  retryingDocumentId,
  onRetry,
}: {
  documents: DocumentStatusItem[]
  total: number
  page: number
  hasNext: boolean
  loading: boolean
  error: string
  documentReady: boolean
  maxFileBytes: number
  importing: boolean
  uploadMessage: string
  onAddFiles: () => void
  onAddFolder: () => void
  onPageChange: (page: number) => void
  retryingDocumentId: string | null
  onRetry: (documentVersionId: string) => void
}) {
  const totalPages = Math.max(1, Math.ceil(total / DOCUMENT_PAGE_SIZE))
  const paginationItems = documentPaginationItems(page, totalPages)
  return (
    <div className="documents-page">
      <div className="documents-page-heading">
        <div><p className="eyebrow">내 문서</p><h2>임베딩 문서</h2><span>처리 상태와 최근 변경 시간을 확인할 수 있습니다.</span></div>
        <div className="document-add-actions">
          {isDesktopShell() && <button type="button" className="secondary-button" onClick={onAddFolder} disabled={!documentReady || importing}><FolderIcon width={17} height={17} /> 폴더 추가</button>}
          <button type="button" className="primary-button" onClick={onAddFiles} disabled={!documentReady || importing}><UploadIcon width={18} height={18} /> {importing ? '처리 중' : '문서 추가'}</button>
        </div>
      </div>
      <SupportedFormatsGuide maxFileBytes={maxFileBytes} />
      {uploadMessage && <div className="toast" role="status">{uploadMessage}</div>}
      <section className="document-library card" aria-label="전체 임베딩 문서">
        <div className="document-library-summary"><strong>전체 {total.toLocaleString()}개</strong><span>최신 문서부터 표시합니다.</span></div>
        <DocumentStatusList
          documents={documents}
          loading={loading}
          error={error}
          retryingDocumentId={retryingDocumentId}
          onRetry={onRetry}
        />
        {total > 0 && (
          <div className="document-pagination" aria-label="문서 목록 페이지">
            <button type="button" className="document-page-move" aria-label="문서 목록 이전 페이지" disabled={page === 0 || loading} onClick={() => onPageChange(page - 1)}>이전</button>
            <div className="document-page-numbers">
              {paginationItems.map((item) => item.kind === 'ellipsis'
                ? <span className="document-page-ellipsis" aria-hidden="true" key={item.key}>…</span>
                : <button
                    type="button"
                    className="document-page-number"
                    aria-current={item.page === page ? 'page' : undefined}
                    aria-label={`문서 목록 ${item.page + 1}페이지`}
                    disabled={loading}
                    key={item.page}
                    onClick={() => onPageChange(item.page)}
                  >{item.page + 1}</button>)}
            </div>
            <button type="button" className="document-page-move" aria-label="문서 목록 다음 페이지" disabled={!hasNext || loading} onClick={() => onPageChange(page + 1)}>다음</button>
          </div>
        )}
      </section>
    </div>
  )
}

type DocumentPaginationItem =
  | { kind: 'page', page: number }
  | { kind: 'ellipsis', key: string }

function documentPaginationItems(currentPage: number, totalPages: number): DocumentPaginationItem[] {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, page) => ({ kind: 'page' as const, page }))
  }

  const visiblePages = new Set<number>([0, totalPages - 1])
  const rangeStart = currentPage <= 2
    ? 0
    : currentPage >= totalPages - 3
      ? totalPages - 5
      : currentPage - 2
  const rangeEnd = currentPage <= 2
    ? 4
    : currentPage >= totalPages - 3
      ? totalPages - 1
      : currentPage + 2
  for (let page = rangeStart; page <= rangeEnd; page += 1) {
    if (page >= 0 && page < totalPages) visiblePages.add(page)
  }

  const sortedPages = [...visiblePages].sort((left, right) => left - right)
  const items: DocumentPaginationItem[] = []
  sortedPages.forEach((page, index) => {
    const previous = sortedPages[index - 1]
    if (index > 0 && page - previous > 1) {
      items.push({ kind: 'ellipsis', key: `ellipsis-${previous}-${page}` })
    }
    items.push({ kind: 'page', page })
  })
  return items
}

function DocumentStatusList({
  documents,
  loading,
  error,
  emptyTitle = '처리된 문서가 없습니다',
  retryingDocumentId,
  onRetry,
}: {
  documents: DocumentStatusItem[]
  loading: boolean
  error: string
  emptyTitle?: string
  retryingDocumentId?: string | null
  onRetry?: (documentVersionId: string) => void
}) {
  if (loading && documents.length === 0) {
    return <div className="document-list-message" role="status"><RefreshIcon className="spin" width={18} height={18} /> 문서 상태를 불러오고 있습니다.</div>
  }
  if (error && documents.length === 0) {
    return <div className="document-list-message error" role="alert">{error}</div>
  }
  if (documents.length === 0) {
    return (
      <div className="empty-state">
        <div className="document-stack"><DocumentIcon width={27} height={27} /></div>
        <h4>{emptyTitle}</h4>
        <p>임베딩된 문서가 생기면 최신 순으로 여기에 표시됩니다.</p>
      </div>
    )
  }

  return (
    <div className="document-status-list" aria-live="polite" aria-busy={loading}>
      {error && <p className="document-inline-error" role="alert">{error}</p>}
      {documents.map((document) => (
        <article className="document-status-item" key={document.documentVersionId}>
          <div className="document-file-icon"><DocumentIcon width={21} height={21} /></div>
          <div className="document-status-copy">
            <strong title={document.originalFilename}>{document.originalFilename}</strong>
            <span>{formatDocumentStatusDetail(document)} · {formatBytes(document.byteSize)} · {formatDocumentDate(document.updatedAt)}</span>
          </div>
          <div className="document-status-actions">
            <span className={`document-status-badge ${document.status.toLowerCase().replace(/_/g, '-')}`}><i />{documentStatusLabel(document.status)}</span>
            {document.status === 'FAILED' && onRetry && (
              <button
                type="button"
                className="document-retry-button"
                aria-label={`${document.originalFilename} 다시 처리`}
                disabled={retryingDocumentId !== null && retryingDocumentId !== undefined}
                onClick={() => onRetry(document.documentVersionId)}
              >
                <RefreshIcon className={retryingDocumentId === document.documentVersionId ? 'spin' : ''} width={13} height={13} />
                {retryingDocumentId === document.documentVersionId ? '재처리 중' : '다시 처리'}
              </button>
            )}
          </div>
        </article>
      ))}
    </div>
  )
}

function isDocumentStillProcessing(document: DocumentStatusItem) {
  return document.status === 'PROCESSING'
}

function documentStatusLabel(status: DocumentStatusItem['status']) {
  return ({
    PROCESSING: '처리 중',
    WAITING_FOR_MODEL: '모델 대기',
    REINDEX_REQUIRED: '재임베딩 필요',
    COMPLETED: '완료',
    FAILED: '실패',
  } as const)[status]
}

function formatDocumentStatusDetail(document: DocumentStatusItem) {
  if (document.status === 'FAILED') return documentFailureDetail(document.errorCode)
  if (document.status === 'WAITING_FOR_MODEL') return '임베딩 모델 대기 중'
  if (document.status === 'REINDEX_REQUIRED') return '새 임베딩이 필요함'
  if (document.status === 'COMPLETED') return `임베딩 완료 · 버전 ${document.versionNumber}`
  if (document.ingestionStatus === 'RECEIVED' || document.ingestionStatus === 'STORED') return '문서 처리 대기 중'
  if (document.ingestionStatus === 'PARSING') return '문서 내용 분석 중'
  if (document.ingestionStatus === 'OCR_PENDING') return 'OCR 처리 대기 중'
  if (document.ingestionStatus === 'OCR_RUNNING') return 'PDF 문자 인식 중'
  if (document.indexingStatus === 'INDEXING') return '임베딩 생성 중'
  if (document.indexingStatus === 'PAUSED') return '중단된 지점부터 재개 대기 중'
  return '임베딩 대기 중'
}

function documentFailureDetail(errorCode: string | null) {
  return ({
    OCR_PAGE_LIMIT_EXCEEDED: 'OCR 지원 쪽수를 초과했습니다',
    OCR_PIXEL_LIMIT_EXCEEDED: '스캔 화면의 크기가 OCR 안전 한도를 초과했습니다',
    OCR_TEMP_STORAGE_INSUFFICIENT: 'OCR 임시 저장 공간이 부족합니다',
    OCR_TIMEOUT: 'PDF 문자 인식 시간을 초과했습니다',
    OCR_NO_TEXT: 'PDF 이미지에서 인식할 문자를 찾지 못했습니다',
    CHECKPOINT_EXPIRED: '오래된 중간 결과가 정리되어 처음부터 다시 처리해야 합니다',
  } as Record<string, string>)[errorCode ?? ''] ?? '처리 실패'
}

function formatDocumentDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '시간 확인 불가'
  return new Intl.DateTimeFormat('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function loadRecentSearches(): string[] {
  if (typeof window === 'undefined') return []
  try {
    const stored = JSON.parse(window.localStorage.getItem(RECENT_SEARCHES_STORAGE_KEY) ?? '[]')
    if (!Array.isArray(stored)) return []
    return stored
      .filter((item): item is string => typeof item === 'string')
      .map((item) => item.trim())
      .filter(Boolean)
      .slice(0, MAX_RECENT_SEARCHES)
  } catch {
    return []
  }
}

function summarizeRecentSearch(search: string) {
  const normalized = search.replace(/\s+/g, ' ').trim()
  return normalized.length > 24 ? `${normalized.slice(0, 24)}…` : normalized
}

function CapabilityNotice({ title, body, onRefresh, onOpenSettings }: { title: string; body: string; onRefresh: () => Promise<void>; onOpenSettings?: () => void }) {
  return (
    <div className="capability-notice">
      <div className="capability-notice-copy"><strong>{title}</strong><span>{body}</span></div>
      <div className="capability-notice-actions">
        <button type="button" onClick={() => void onRefresh()}>다시 확인</button>
        {onOpenSettings && <button type="button" className="capability-settings-button" title="AI 모델 설정으로 이동" onClick={onOpenSettings}>설정</button>}
      </div>
    </div>
  )
}

function StartupProgress({ label, attempt, maximum }: { label: string; attempt: number; maximum: number }) {
  return (
    <div className="startup-progress" aria-label={`${label} ${attempt}/${maximum}`}>
      <span><i style={{ width: `${Math.max(2, (attempt / maximum) * 100)}%` }} /></span>
      <small>{attempt}/{maximum}</small>
    </div>
  )
}

function CapabilityItem({ icon, title, ready, checking = false, onStatusClick }: { icon: ReactNode; title: string; ready: boolean; checking?: boolean; onStatusClick?: () => void }) {
  const state = ready ? 'ready' : checking ? 'checking' : 'unavailable'
  const stateLabel = ready ? '사용 가능' : checking ? '확인 중' : '사용 불가'
  const statusContent = <>
    <span className="capability-state">{stateLabel}</span>
    <span className="capability-state-icon" aria-hidden="true">
      {ready ? <CheckIcon width={12} height={12} /> : checking ? <RefreshIcon className="spin" width={12} height={12} /> : <LockIcon width={12} height={12} />}
    </span>
  </>
  return (
    <div className={`capability-item ${state}`}>
      <div className="capability-icon">{icon}</div>
      <strong>{title}</strong>
      {onStatusClick ? (
        <button type="button" className="capability-status settings-status-link" aria-label={`${title} ${stateLabel}, 설정으로 이동`} title="AI 모델 설정으로 이동" onClick={onStatusClick}>{statusContent}</button>
      ) : (
        <div className="capability-status">{statusContent}</div>
      )}
    </div>
  )
}

function SearchResults({ results }: { results: SearchResult[] }) {
  if (results.length === 0) {
    return <div className="search-empty" role="status">관련 문서 내용을 찾지 못했습니다.</div>
  }

  return (
    <div className="search-results" aria-label="검색 결과">
      {results.map((result) => (
        <article key={result.chunkId} className="search-result">
          <div className="search-result-heading">
            <strong>{result.originalFilename}</strong>
            <span>관련도 {Math.round(result.score * 100)}%</span>
          </div>
          <p>{result.content}</p>
          <small>문서 버전 {result.versionNumber} · 구간 {result.chunkIndex + 1}</small>
          <RevealSourceButton documentVersionId={result.documentVersionId} filename={result.originalFilename} />
        </article>
      ))}
    </div>
  )
}

function unavailableStatus(): LocalAiStatus {
  return {
    ...initialStatus,
    ollama: { status: 'CHECK_FAILED', version: null, errorCode: 'BACKEND_UNAVAILABLE' },
    embedding: { ...initialStatus.embedding, status: 'ERROR', errorCode: 'MODEL_STATUS_UNAVAILABLE' },
    chat: { ...initialStatus.chat, status: 'DISABLED', errorCode: null },
    capabilities: { documentManagement: false, semanticSearch: false, groundedAnswer: false },
    checkInProgress: false,
  }
}

function isAiStatusPending(status: LocalAiStatus) {
  return status.checkInProgress
    || status.ollama.status === 'CHECKING'
    || status.embedding.status === 'CHECKING'
    || status.chat.status === 'CHECKING'
}

function aiCheckTimeoutStatus(status: LocalAiStatus): LocalAiStatus {
  return {
    ...status,
    ollama: { status: 'CHECK_FAILED', version: null, errorCode: 'AI_CHECK_TIMEOUT' },
    embedding: { ...status.embedding, status: 'ERROR', digest: null, sizeBytes: 0, errorCode: 'MODEL_STATUS_UNAVAILABLE' },
    chat: status.chatProfile.profile === 'DISABLED'
      ? { ...status.chat, status: 'DISABLED', digest: null, sizeBytes: 0, errorCode: null }
      : { ...status.chat, status: 'ERROR', digest: null, sizeBytes: 0, errorCode: 'MODEL_STATUS_UNAVAILABLE' },
    capabilities: { documentManagement: true, semanticSearch: false, groundedAnswer: false },
    checkInProgress: false,
  }
}

function aiCheckFailedStatus(status: LocalAiStatus): LocalAiStatus {
  return {
    ...aiCheckTimeoutStatus(status),
    ollama: { status: 'CHECK_FAILED', version: null, errorCode: 'OLLAMA_CHECK_FAILED' },
  }
}

function backendStatusLabel(status: BackendConnectionStatus) {
  return ({ STARTING: '시작 중', CHECKING: '확인 중', READY: '준비됨', ERROR: '연결 실패' } as const)[status]
}

function backendStatusTone(status: BackendConnectionStatus) {
  if (status === 'READY') return 'ready'
  if (status === 'ERROR') return 'error'
  return 'checking'
}

function backendStatusDescription(status: BackendConnectionStatus) {
  return ({
    STARTING: 'PrivateKB 로컬 서비스를 시작하고 있습니다.',
    CHECKING: 'PrivateKB 로컬 서비스 응답을 다시 확인하고 있습니다.',
    READY: '문서 관리 API를 사용할 수 있습니다.',
    ERROR: `PrivateKB 로컬 서비스를 시작하지 못했습니다. ${BACKEND_STARTUP_MAX_ATTEMPTS}초 동안 자동 확인했지만 응답이 없습니다.`,
  } as const)[status]
}

function aiCheckPhaseLabel(phase: LocalAiCheckPhase, backend: BackendConnectionStatus) {
  if (phase === 'WAITING') return backend === 'ERROR' ? '프로그램 준비 필요' : '확인 대기'
  return ({ CHECKING: '확인 중', COMPLETE: '확인 완료', ERROR: '확인 실패' } as const)[phase]
}

function aiCheckPhaseDescription(phase: LocalAiCheckPhase, backend: BackendConnectionStatus) {
  if (phase === 'WAITING') {
    return backend === 'ERROR'
      ? 'AI 상태 확인을 시작할 수 없습니다. 잠시 후 다시 확인해 주세요.'
      : 'Ollama와 모델 상태 확인을 준비하고 있습니다.'
  }
  return ({
    CHECKING: 'Ollama와 임베딩·대화 모델 상태를 계속 확인하고 있습니다.',
    COMPLETE: 'Ollama와 모델 상태 확인을 마쳤습니다.',
    ERROR: `AI 상태를 ${AI_STATUS_MAX_ATTEMPTS}초 동안 확인하지 못했습니다. 다시 확인해 주세요.`,
  } as const)[phase]
}

function aiStatusTone(phase: LocalAiCheckPhase) {
  if (phase === 'COMPLETE') return 'ready'
  if (phase === 'ERROR') return 'error'
  return 'checking'
}

function viewTitle(view: AppView) {
  return ({ HOME: '홈', DOCUMENTS: '문서', SETTINGS: '설정' } as const)[view]
}

function answerCapabilityGuidance(status: LocalAiStatus) {
  if (!status.capabilities.semanticSearch) {
    return '먼저 임베딩 모델 상태를 확인해 주세요.'
  }
  if (status.chatProfile.profile === 'DISABLED') {
    return '설정에서 저사양, 일반 또는 고사양 대화 모델을 선택해 주세요.'
  }
  return `${status.chatProfile.model} 모델을 설치하거나 상태를 다시 확인해 주세요.`
}

function wait(milliseconds: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('작업이 취소되었습니다.', 'AbortError'))
      return
    }
    const timeout = window.setTimeout(() => {
      signal.removeEventListener('abort', abort)
      resolve()
    }, milliseconds)
    const abort = () => {
      window.clearTimeout(timeout)
      reject(new DOMException('작업이 취소되었습니다.', 'AbortError'))
    }
    signal.addEventListener('abort', abort, { once: true })
  })
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === 'AbortError'
}
