import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import App, { AI_STATUS_RETRY_INTERVAL_MS, BACKEND_STARTUP_MAX_ATTEMPTS, BACKEND_STARTUP_RETRY_INTERVAL_MS, STARTUP_STATUS_EXIT_DURATION_MS } from './App'
import type { DesktopSelectionPreview } from './api/desktopBridge'
import type { DocumentPage, DocumentStatusItem } from './types/documents'
import type { LocalAiStatus, ModelInstallJob } from './types/localAi'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  Channel: class { onmessage = () => undefined },
}))

const readyStatus: LocalAiStatus = {
  ollama: { status: 'CONNECTED', version: '0.32.15', errorCode: null },
  embedding: { model: 'qwen3-embedding:0.6b', status: 'READY', digest: 'abc', sizeBytes: 639150858, errorCode: null },
  chat: { model: 'qwen3.5:4b', status: 'READY', digest: 'def', sizeBytes: 3400000000, errorCode: null },
  chatProfile: { profile: 'STANDARD', displayName: '일반', model: 'qwen3.5:4b', estimatedDownloadBytes: 3400000000, contextLength: 8192, maximumGeneratedTokens: 1024, maximumSourceDocuments: 5 },
  capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: true },
  lastCheckedAt: '2026-08-24T01:00:00Z',
  checkInProgress: false,
}

const emptyDocumentPage: DocumentPage = {
  documents: [],
  totalElements: 0,
  page: 0,
  size: 10,
  hasNext: false,
}

describe('PrivateKB 앱 화면', () => {
  beforeEach(() => {
    vi.useRealTimers()
    window.localStorage.clear()
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined)
    vi.mocked(invoke).mockReset()
    vi.mocked(invoke).mockResolvedValue({ phase: 'EXTERNAL', errorCode: null, backendPort: null })
    vi.stubGlobal('fetch', vi.fn(async (input) => jsonApiResponse(input, readyStatus)))
  })

  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    delete (window as Window & { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
  })

  it('두 모델이 준비되면 검색과 질문 영역을 활성화한다', async () => {
    render(<App />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'AI 상태' })).toBeInTheDocument())
    expect(screen.getByRole('heading', { level: 1, name: '홈' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '사용자 메뉴' })).not.toBeInTheDocument()
    expect(screen.queryByText('나만의 로컬 지식')).not.toBeInTheDocument()
    expect(screen.queryByText('이 PC 안에서만')).not.toBeInTheDocument()
    expect(screen.getByLabelText('3개 기능 중 3개 사용 가능')).toHaveTextContent('3/3')
    expect(screen.getAllByText('사용 가능')).toHaveLength(3)
    expect(document.querySelectorAll('.capability-status')).toHaveLength(3)
    expect(screen.queryByText('PrivateKB 사용 순서')).not.toBeInTheDocument()
    expect(screen.getByText('최근 처리된 문서가 없습니다')).toBeInTheDocument()
    expect(screen.getByText('임베딩된 문서가 생기면 최신 순으로 여기에 표시됩니다.')).toBeInTheDocument()
    expect(screen.queryByText(/PDF, Office, HWP 5\.x, Markdown, TXT/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '첫 문서 추가' })).not.toBeInTheDocument()
    expect(document.querySelector('input[type="file"]')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '문서 추가', hidden: true })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '폴더 추가', hidden: true })).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '지식 검색' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '질문' })).toBeEnabled()
  })

  it('빈 문서 메뉴는 조회 안내만 표시하고 상단 버튼으로 파일과 폴더를 추가한다', async () => {
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
    vi.mocked(invoke).mockImplementation(async (command) => (
      command === 'get_runtime_status'
        ? { phase: 'EXTERNAL', errorCode: null, backendPort: null }
        : null
    ))
    const user = userEvent.setup()
    render(<App />)

    await screen.findByText('최근 처리된 문서가 없습니다')
    expect(screen.queryByRole('button', { name: '폴더 추가', hidden: true })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '문서 추가', hidden: true })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '문서' }))

    const library = screen.getByLabelText('전체 임베딩 문서')
    expect(await within(library).findByText('처리된 문서가 없습니다')).toBeInTheDocument()
    expect(within(library).queryByRole('button')).not.toBeInTheDocument()
    expect(within(library).queryByText('문서를 추가해 시작하세요')).not.toBeInTheDocument()
    expect(within(library).queryByText(/PDF, Office, HWP/)).not.toBeInTheDocument()

    const folderButton = screen.getByRole('button', { name: '폴더 추가' })
    const fileButton = screen.getByRole('button', { name: '문서 추가' })
    expect(folderButton).toBeEnabled()
    expect(fileButton).toBeEnabled()
    await user.click(folderButton)
    expect(invoke).toHaveBeenCalledWith('select_document_folder')
    await user.click(fileButton)
    expect(invoke).toHaveBeenCalledWith('select_document_files')

    expect(screen.getByRole('button', { name: '지원 형식 보기' })).toHaveAttribute('aria-expanded', 'false')
    await user.click(screen.getByRole('button', { name: '홈' }))
    expect(screen.queryByRole('button', { name: '문서 추가', hidden: true })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '지원 형식 보기', hidden: true })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '설정' }))
    expect(screen.queryByRole('button', { name: '폴더 추가', hidden: true })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '문서 추가', hidden: true })).not.toBeInTheDocument()
  })

  it('폴더 문서는 등록 수만 안내하고 다른 화면으로 이동하면 안내를 지운다', async () => {
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
    const preview: DesktopSelectionPreview = {
      selectionId: 'document-registration-fixture',
      mode: 'FOLDER',
      rootName: '등록 검증',
      candidateCount: 60,
      totalBytes: 60_000,
      excludedCount: 0,
      exclusions: { unsupportedFormat: 0, emptyFile: 0, tooLarge: 0, hiddenSystemOrTemporary: 0, reparsePoint: 0, inaccessible: 0, depthExceeded: 0 },
      excludedPage: { entries: [], page: 0, pageSize: 50, totalElements: 0, hasNext: false },
      maxFileBytes: 50_000_000,
      entries: [{ displayName: '업무 문서 1.txt', byteSize: 1_000 }],
    }
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_runtime_status') return { phase: 'EXTERNAL', errorCode: null, backendPort: null }
      if (command === 'select_document_folder') return preview
      if (command === 'import_document_selection') return { requestedCount: 60, acceptedCount: 58, duplicateCount: 2, failedCount: 0, stoppedCount: 0, stopReason: null }
      return null
    })
    const user = userEvent.setup()
    render(<App />)

    await screen.findByText('최근 처리된 문서가 없습니다')
    await user.click(screen.getByRole('button', { name: '문서' }))
    await user.click(screen.getByRole('button', { name: '폴더 추가' }))
    await user.click(await screen.findByRole('button', { name: '60개 문서 추가' }))

    expect(await screen.findByText('60개 문서를 등록했습니다.')).toBeInTheDocument()
    expect(screen.queryByText(/문서 처리를 마쳤습니다|새 문서|중복|실패/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '홈' }))
    expect(screen.queryByText(/60개 문서를 등록했습니다/)).not.toBeInTheDocument()
  })

  it('저장 공간이 부족하면 남은 문서 등록을 중단하고 선택 화면에서 안내한다', async () => {
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
    const preview: DesktopSelectionPreview = {
      selectionId: 'storage-stop-fixture', mode: 'FOLDER', rootName: '대량 문서', candidateCount: 10,
      totalBytes: 100_000, excludedCount: 0, maxFileBytes: 50_000_000,
      exclusions: { unsupportedFormat: 0, emptyFile: 0, tooLarge: 0, hiddenSystemOrTemporary: 0, reparsePoint: 0, inaccessible: 0, depthExceeded: 0 },
      excludedPage: { entries: [], page: 0, pageSize: 50, totalElements: 0, hasNext: false },
      entries: [{ displayName: '업무 문서 1.txt', byteSize: 10_000 }],
    }
    vi.mocked(invoke).mockImplementation(async (command) => {
      if (command === 'get_runtime_status') return { phase: 'EXTERNAL', errorCode: null, backendPort: null }
      if (command === 'select_document_folder') return preview
      if (command === 'import_document_selection') return {
        requestedCount: 10, acceptedCount: 3, duplicateCount: 1, failedCount: 1,
        stoppedCount: 5, stopReason: 'INSUFFICIENT_STORAGE',
      }
      return null
    })
    const user = userEvent.setup()
    render(<App />)

    await screen.findByText('최근 처리된 문서가 없습니다')
    await user.click(screen.getByRole('button', { name: '문서' }))
    await user.click(screen.getByRole('button', { name: '폴더 추가' }))
    await user.click(await screen.findByRole('button', { name: '10개 문서 추가' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('저장 공간이 부족하여 문서 등록을 중단했습니다.')
    expect(screen.getByRole('alert')).toHaveTextContent('4개는 등록되었고 6개는 등록되지 않았습니다.')
    expect(screen.getByRole('dialog')).toBeVisible()
    expect(screen.getByRole('button', { name: '10개 문서 추가' })).toBeEnabled()
  })

  it('웹에서도 전체 보기로 문서 화면을 연 뒤 파일을 선택하고 접수 결과를 확인한다', async () => {
    const user = userEvent.setup()
    render(<App />)
    await screen.findByText('최근 처리된 문서가 없습니다')
    await user.click(screen.getByRole('button', { name: '전체 보기' }))
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
    expect(input).toHaveAttribute('accept', '.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.hwp,.md,.markdown,.txt')
    const inputClick = vi.spyOn(input, 'click')
    await user.click(screen.getByRole('button', { name: '문서 추가' }))
    expect(inputClick).toHaveBeenCalledOnce()
    const file = new File(['제품 회의 내용'], '제품 회의록.txt', { type: 'text/plain' })
    await user.upload(input, file)
    const notice = await screen.findByText('제품 회의록.txt 문서를 안전하게 접수했습니다.')
    expect(notice.closest('.documents-page')).not.toBeNull()
    const uploadCall = vi.mocked(fetch).mock.calls.find(([url, init]) => String(url).endsWith('/documents') && init?.method === 'POST')
    expect((uploadCall?.[1]?.body as FormData).get('file')).toBe(file)
    expect(screen.getByRole('button', { name: '문서 추가' })).toBeEnabled()
    expect(input.value).toBe('')

    await user.click(screen.getByRole('button', { name: '홈' }))
    expect(document.querySelector('input[type="file"]')).not.toBeInTheDocument()
    expect(screen.queryByText('제품 회의록.txt 문서를 안전하게 접수했습니다.')).not.toBeInTheDocument()
  })

  it('문서 선택 오류도 홈이 아닌 문서 화면에 표시한다', async () => {
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
    vi.mocked(invoke).mockImplementation(async command => {
      if (command === 'get_runtime_status') return { phase: 'EXTERNAL', errorCode: null, backendPort: null }
      throw new Error('문서 선택을 완료할 수 없습니다.')
    })
    const user = userEvent.setup()
    render(<App />)
    await screen.findByText('최근 처리된 문서가 없습니다')
    await user.click(screen.getByRole('button', { name: '문서' }))
    await user.click(screen.getByRole('button', { name: '폴더 추가' }))
    const notice = await screen.findByText('문서 선택을 완료할 수 없습니다.')
    expect(notice.closest('.documents-page')).not.toBeNull()
    expect(screen.getByRole('button', { name: '문서 추가' })).toBeEnabled()
  })

  it('사양 변경 후 설정과 문서 안내의 제한을 갱신하고 초과 파일의 전송을 막는다', async () => {
    let selected: LocalAiStatus = { ...readyStatus, chatProfile: { ...readyStatus.chatProfile, maximumUploadBytes: 50_000_000 } }
    const limits = { DISABLED: 25_000_000, LOW_SPEC: 25_000_000, STANDARD: 50_000_000, HIGH_SPEC: 100_000_000 }
    vi.stubGlobal('fetch', vi.fn(async (input, init) => {
      if (String(input).endsWith('/chat-profile') && init?.method === 'PUT') {
        const profile = (JSON.parse(String(init.body)) as { profile: keyof typeof limits }).profile
        selected = { ...selected, chatProfile: { ...selected.chatProfile, profile, maximumUploadBytes: limits[profile] } }
        return jsonResponse(selected, 202)
      }
      return jsonApiResponse(input, selected)
    }))
    const user = userEvent.setup()
    render(<App />)
    await screen.findByText('최근 처리된 문서가 없습니다')
    for (const [name, limit] of [['저사양', 25_000_000], ['일반', 50_000_000], ['고사양', 100_000_000], ['미사용', 25_000_000]] as const) {
      await user.click(screen.getByRole('button', { name: '설정' }))
      await user.click(screen.getByRole('radio', { name: new RegExp(`^${name}`) }))
      const label = `${(limit / 1_000_000).toFixed(1)}MB`
      expect(await screen.findByText(`현재 업로드 제한 · 파일당 최대 ${label}`)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: '문서' }))
      await user.click(screen.getByRole('button', { name: '지원 형식 보기' }))
      expect(screen.getByRole('region', { name: '지원 형식 및 제한' })).toHaveTextContent(`파일당 최대 ${label}`)
      const file = new File(['합성 파일'], '크기검증.txt', { type: 'text/plain' })
      Object.defineProperty(file, 'size', { value: limit + 1 })
      const before = vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST' && options.body instanceof FormData).length
      await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]')!, file)
      expect(await screen.findByText(`현재 설정에서는 파일당 최대 ${label}까지 추가할 수 있습니다. 설정에서 사양을 확인해 주세요.`)).toBeInTheDocument()
      expect(vi.mocked(fetch).mock.calls.filter(([, options]) => options?.method === 'POST' && options.body instanceof FormData)).toHaveLength(before)
    }
  })

  it('프로그램 준비 전에는 문서 추가를 막지만 지원 형식은 확인할 수 있다', async () => {
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
    vi.mocked(invoke).mockResolvedValue({ phase: 'ERROR', errorCode: 'RUNTIME_START_FAILED', backendPort: null })
    const user = userEvent.setup()
    render(<App />)
    await user.click(screen.getByRole('button', { name: '문서' }))
    expect(screen.getByRole('button', { name: '문서 추가' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '폴더 추가' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '지원 형식 보기' }))
    expect(screen.getByRole('region', { name: '지원 형식 및 제한' })).toBeVisible()
  })

  it('홈에는 최근 문서 10개와 상태를 표시하고 문서 메뉴에서는 전체 목록을 연다', async () => {
    const statuses: DocumentStatusItem['status'][] = [
      'PROCESSING',
      'WAITING_FOR_MODEL',
      'REINDEX_REQUIRED',
      'COMPLETED',
      'FAILED',
    ]
    const documents = Array.from({ length: 11 }, (_, index) => createDocumentStatus(
      index + 1,
      statuses[index % statuses.length],
    ))
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.includes('/documents?page=0&size=10')) {
        return jsonResponse({ ...emptyDocumentPage, documents: documents.slice(0, 10), totalElements: 11, hasNext: true })
      }
      if (url.includes('/documents?page=0&size=20')) {
        return jsonResponse({ ...emptyDocumentPage, documents, totalElements: 11, size: 20 })
      }
      return jsonResponse(readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    await waitFor(() => expect(document.querySelectorAll('.document-status-item')).toHaveLength(10))
    expect(screen.getByText('최근 문서 1.txt')).toBeInTheDocument()
    expect(screen.queryByText('최근 문서 11.txt')).not.toBeInTheDocument()
    expect(screen.getAllByText('처리 중').length).toBeGreaterThan(0)
    expect(screen.getAllByText('모델 대기').length).toBeGreaterThan(0)
    expect(screen.getAllByText('재임베딩 필요').length).toBeGreaterThan(0)
    expect(screen.getAllByText('완료').length).toBeGreaterThan(0)
    expect(screen.getAllByText('실패').length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '최근 문서 5.txt 다시 처리' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/document-versions/version-5/ingestion/retry'),
      expect.objectContaining({ method: 'POST' }),
    ))

    await user.click(screen.getByRole('button', { name: '문서' }))

    expect(await screen.findByRole('heading', { level: 2, name: '임베딩 문서' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('전체 임베딩 문서').querySelectorAll('.document-status-item')).toHaveLength(11))
    expect(screen.getByText('전체 11개')).toBeInTheDocument()
    expect(screen.getByText('최근 문서 11.txt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '문서' })).toHaveClass('active')
  })

  it('문서 목록을 20개 단위 페이지 번호로 이동한다', async () => {
    const documents = Array.from({ length: 95 }, (_, index) => ({
      ...createDocumentStatus((index % 20) + 1, 'COMPLETED'),
      documentId: `paged-document-${index + 1}`,
      documentVersionId: `paged-version-${index + 1}`,
      originalFilename: `페이지 문서 ${index + 1}.txt`,
    }))
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.includes('/documents?page=0&size=10')) {
        return jsonResponse({ ...emptyDocumentPage, documents: documents.slice(0, 10), totalElements: documents.length, size: 10, hasNext: true })
      }
      const pageMatch = url.match(/\/documents\?page=(\d+)&size=20/)
      if (pageMatch) {
        const page = Number(pageMatch[1])
        const start = page * 20
        return jsonResponse({
          documents: documents.slice(start, start + 20),
          totalElements: documents.length,
          page,
          size: 20,
          hasNext: start + 20 < documents.length,
        } satisfies DocumentPage)
      }
      return jsonResponse(readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    await screen.findByText('페이지 문서 1.txt')
    await user.click(screen.getByRole('button', { name: '문서' }))

    const library = screen.getByLabelText('전체 임베딩 문서')
    await waitFor(() => expect(library.querySelectorAll('.document-status-item')).toHaveLength(20))
    expect(screen.getByRole('button', { name: '문서 목록 1페이지' })).toHaveAttribute('aria-current', 'page')
    for (const page of [1, 2, 3, 4, 5]) {
      expect(screen.getByRole('button', { name: `문서 목록 ${page}페이지` })).toBeInTheDocument()
    }

    await user.click(screen.getByRole('button', { name: '문서 목록 3페이지' }))

    expect(await within(library).findByText('페이지 문서 41.txt')).toBeInTheDocument()
    expect(within(library).getAllByRole('article')).toHaveLength(20)
    expect(screen.getByRole('button', { name: '문서 목록 3페이지' })).toHaveAttribute('aria-current', 'page')
    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/documents?page=2&size=20'),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(screen.getByRole('button', { name: '문서 목록 이전 페이지' })).toBeEnabled()
    expect(screen.getByRole('button', { name: '문서 목록 다음 페이지' })).toBeEnabled()
  })

  it('좌측 메뉴로 화면을 전환할 때 페이지 스크롤을 맨 위로 이동한다', async () => {
    const scrollTo = vi.mocked(window.scrollTo)
    scrollTo.mockClear()
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: '설정' }))

    expect(screen.getByRole('heading', { level: 2, name: '대화 모델 사용 방식' })).toBeInTheDocument()
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'auto' })
  })

  it.each([
    { modelStatus: 'READY', modelLabel: '준비됨', capabilityLabel: '사용 가능' },
    { modelStatus: 'DISABLED', modelLabel: '미사용', capabilityLabel: '사용 불가' },
    { modelStatus: 'NOT_INSTALLED', modelLabel: '설치되지 않음', capabilityLabel: '사용 불가' },
  ] as const)('AI 모델 $modelLabel 상태에서 팝업과 기능 상태 표시로 설정에 이동한다', async ({ modelStatus, modelLabel, capabilityLabel }) => {
    const status: LocalAiStatus = {
      ...readyStatus,
      chat: { ...readyStatus.chat, status: modelStatus },
      chatProfile: modelStatus === 'DISABLED'
        ? { profile: 'DISABLED', displayName: '미사용', model: null, estimatedDownloadBytes: 0, contextLength: 0, maximumGeneratedTokens: 0, maximumSourceDocuments: 0 }
        : readyStatus.chatProfile,
      capabilities: { ...readyStatus.capabilities, groundedAnswer: modelStatus === 'READY' },
    }
    vi.mocked(fetch).mockImplementation(async (input) => jsonApiResponse(input, status))
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'AI 상태' }))
    const dialog = screen.getByRole('dialog', { name: 'AI 연결 상태' })
    const modelButton = within(dialog).getByRole('button', { name: `AI 모델 상태: ${modelLabel}, 설정으로 이동` })
    expect(modelButton).toBeEnabled()
    expect(modelButton).toHaveClass('settings-status-link')
    vi.mocked(window.scrollTo).mockClear()
    await user.click(modelButton)

    expect(screen.queryByRole('dialog', { name: 'AI 연결 상태' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: '설정' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '대화 모델 사용 방식' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '설정' })).toHaveClass('active')
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toHaveFocus())
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'auto' })

    await user.click(screen.getByRole('button', { name: '홈' }))
    const capabilityCard = screen.getByRole('heading', { name: '사용 가능한 기능' }).closest('section')!
    expect(within(capabilityCard).getByText('파일 검색')).toBeInTheDocument()
    expect(within(capabilityCard).getByText('AI 검색')).toBeInTheDocument()
    expect(within(capabilityCard).queryByText('의미 검색')).not.toBeInTheDocument()
    expect(within(capabilityCard).queryByText('근거 기반 답변')).not.toBeInTheDocument()
    const capabilityButton = within(capabilityCard).getByRole('button', { name: `AI 검색 ${capabilityLabel}, 설정으로 이동` })
    expect(capabilityButton).toBeEnabled()
    expect(capabilityButton).toHaveClass('settings-status-link')
    expect(within(capabilityCard).getAllByRole('button')).toHaveLength(1)
    vi.mocked(window.scrollTo).mockClear()
    await user.click(capabilityButton)

    expect(screen.getByRole('heading', { level: 1, name: '설정' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toHaveFocus())
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'auto' })
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes('/install') || String(input).endsWith('/chat-profile'))).toBe(false)
  })

  it.each(['DISABLED', 'NOT_INSTALLED', 'EMBEDDING_NOT_INSTALLED'] as const)('%s 상태의 AI 검색 안내에서 다시 확인과 설정 이동을 구분한다', async (modelState) => {
    const status: LocalAiStatus = {
      ...readyStatus,
      embedding: { ...readyStatus.embedding, status: modelState === 'EMBEDDING_NOT_INSTALLED' ? 'NOT_INSTALLED' : 'READY' },
      chat: { ...readyStatus.chat, status: modelState === 'EMBEDDING_NOT_INSTALLED' ? 'READY' : modelState },
      chatProfile: modelState === 'DISABLED'
        ? { profile: 'DISABLED', displayName: '미사용', model: null, estimatedDownloadBytes: 0, contextLength: 0, maximumGeneratedTokens: 0, maximumSourceDocuments: 0 }
        : readyStatus.chatProfile,
      capabilities: { documentManagement: true, semanticSearch: modelState !== 'EMBEDDING_NOT_INSTALLED', groundedAnswer: false },
    }
    vi.mocked(fetch).mockImplementation(async (input) => jsonApiResponse(input, status))
    const user = userEvent.setup()
    render(<App />)

    const notice = (await screen.findByText('AI 검색 준비가 필요합니다')).closest<HTMLDivElement>('.capability-notice')!
    expect(within(notice).getAllByRole('button').map((button) => button.textContent)).toEqual(['다시 확인', '설정'])
    expect(within(notice).getByRole('button', { name: '설정' })).toHaveAttribute('title', 'AI 모델 설정으로 이동')
    expect(screen.queryByRole('button', { name: '사용자 메뉴' })).not.toBeInTheDocument()
    if (modelState === 'EMBEDDING_NOT_INSTALLED') {
      const searchNotice = screen.getByText('파일 검색 준비가 필요합니다').closest<HTMLDivElement>('.capability-notice')!
      expect(within(searchNotice).getAllByRole('button')).toHaveLength(1)
    }

    vi.mocked(fetch).mockClear()
    await user.click(within(notice).getByRole('button', { name: '다시 확인' }))
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/local-ai/checks'),
      expect.objectContaining({ method: 'POST' }),
    ))
    expect(screen.getByRole('heading', { level: 1, name: '홈' })).toBeInTheDocument()

    const refreshedNotice = (await screen.findByText('AI 검색 준비가 필요합니다')).closest<HTMLDivElement>('.capability-notice')!
    vi.mocked(fetch).mockClear()
    vi.mocked(window.scrollTo).mockClear()
    await user.click(within(refreshedNotice).getByRole('button', { name: '설정' }))

    expect(screen.getByRole('heading', { level: 1, name: '설정' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '대화 모델 사용 방식' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: '설정' })).toHaveFocus())
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0, behavior: 'auto' })
    expect(vi.mocked(fetch).mock.calls.some(([input]) => /\/checks$|\/install|\/chat-profile$/.test(String(input)))).toBe(false)
  })

  it('검색과 질문 메뉴를 누르면 연결된 입력 영역에 초점과 강조 효과를 표시한다', async () => {
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: '검색' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '지식 검색' })).toHaveFocus())
    expect(document.querySelector('.search-card .content-focus-ring')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '질문' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: '문서에 질문' })).toHaveFocus())
    expect(document.querySelector('.answer-card .content-focus-ring')).toBeInTheDocument()
  })

  it('대화 모델이 없으면 질문만 비활성화하고 상세 상태를 보여준다', async () => {
    const missingChat: LocalAiStatus = {
      ...readyStatus,
      chat: { ...readyStatus.chat, status: 'NOT_INSTALLED', digest: null, sizeBytes: 0, errorCode: 'MODEL_NOT_INSTALLED' },
      capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: false },
    }
    vi.mocked(fetch).mockImplementation(async (input) => jsonApiResponse(input, missingChat))
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'AI 상태' }))

    expect(screen.getByRole('button', { name: '질문' })).toBeDisabled()
    expect(screen.getByRole('dialog', { name: 'AI 연결 상태' })).toHaveTextContent('설치되지 않음')
    expect(screen.getByRole('button', { name: 'AI 모델 설치' })).toBeEnabled()
    expect(screen.getByRole('textbox', { name: '지식 검색' })).toBeEnabled()
    expect(screen.getByLabelText('3개 기능 중 2개 사용 가능')).toHaveTextContent('2/3')
    expect(screen.getAllByText('사용 가능')).toHaveLength(2)
    expect(screen.getByText('사용 불가')).toBeInTheDocument()
  })

  it('설정 화면에서 저사양 프로필을 저장하되 모델은 자동 다운로드하지 않는다', async () => {
    const lowSpecStatus: LocalAiStatus = {
      ...readyStatus,
      chat: { model: 'qwen3.5:2b-q4_K_M', status: 'NOT_INSTALLED', digest: null, sizeBytes: 0, errorCode: 'MODEL_NOT_INSTALLED' },
      chatProfile: { profile: 'LOW_SPEC', displayName: '저사양', model: 'qwen3.5:2b-q4_K_M', estimatedDownloadBytes: 1900000000, contextLength: 4096, maximumGeneratedTokens: 512, maximumSourceDocuments: 3 },
      capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: false },
    }
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.endsWith('/chat-profile')) return jsonResponse(lowSpecStatus, 202)
      return jsonApiResponse(input, readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: '설정' }))
    expect(screen.getByRole('heading', { level: 2, name: '대화 모델 사용 방식' })).toBeInTheDocument()
    expect(screen.getByText('선택한 사양에 따라 새 문서의 파일 크기 제한도 바뀝니다. 임베딩 모델과 기존 문서는 그대로 유지됩니다.')).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /저사양/ }))

    await waitFor(() => expect(screen.getByText('현재 설정 · 저사양')).toBeInTheDocument())
    expect(screen.getByText(/qwen3\.5:2b-q4_K_M 모델 설치가 필요합니다/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '선택한 모델 설치' })).toBeEnabled()
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/local-ai/chat-profile', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ profile: 'LOW_SPEC' }),
    }))
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).endsWith('/models/chat/install'))).toBe(false)
  })

  it('설정 화면에서 설치된 9B 고사양 프로필을 선택한다', async () => {
    const highSpecStatus: LocalAiStatus = {
      ...readyStatus,
      chat: { model: 'qwen3.5:9b', status: 'READY', digest: 'high-spec-digest', sizeBytes: 6594474711, errorCode: null },
      chatProfile: { profile: 'HIGH_SPEC', displayName: '고사양', model: 'qwen3.5:9b', estimatedDownloadBytes: 6600000000, contextLength: 8192, maximumGeneratedTokens: 1536, maximumSourceDocuments: 7 },
      capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: true },
    }
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.endsWith('/chat-profile')) return jsonResponse(highSpecStatus, 202)
      return jsonApiResponse(input, readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: '설정' }))
    expect(screen.getByText('qwen3.5:9b · 약 6.6GB')).toBeInTheDocument()
    expect(screen.getByText(/RAM 32GB 이상.*GPU VRAM 8GB 권장/)).toBeInTheDocument()

    await user.click(screen.getByRole('radio', { name: /고사양/ }))

    await waitFor(() => expect(screen.getByText('현재 설정 · 고사양')).toBeInTheDocument())
    expect(screen.getByText('qwen3.5:9b 모델을 사용할 준비가 되었습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '선택한 모델 설치' })).not.toBeInTheDocument()
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/local-ai/chat-profile', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ profile: 'HIGH_SPEC' }),
    }))
  })

  it('대화 미사용 상태에서도 AI 상태 창에 임베딩 준비 상태를 표시한다', async () => {
    const disabledStatus: LocalAiStatus = {
      ...readyStatus,
      chat: { model: null, status: 'DISABLED', digest: null, sizeBytes: 0, errorCode: null },
      chatProfile: { profile: 'DISABLED', displayName: '미사용', model: null, estimatedDownloadBytes: 0, contextLength: 0, maximumGeneratedTokens: 0, maximumSourceDocuments: 0 },
      capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: false },
    }
    vi.mocked(fetch).mockImplementation(async (input) => jsonApiResponse(input, disabledStatus))
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'AI 상태' }))

    expect(screen.getByRole('dialog', { name: 'AI 연결 상태' })).toHaveTextContent('AI · 미사용')
    expect(screen.getByRole('dialog', { name: 'AI 연결 상태' })).toHaveTextContent('AI 모델을 사용하지 않음')
    expect(screen.queryByRole('button', { name: 'AI 모델 설치' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '질문' })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: '지식 검색' })).toBeEnabled()
  })

  it('시작 중에는 사용 불가 대신 자동 확인 진행 상태를 표시한다', async () => {
    vi.useFakeTimers()
    let statusAttempts = 0
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).endsWith('/api/local-ai/status')) {
        statusAttempts += 1
        if (statusAttempts < 3) throw new TypeError('connection refused')
      }
      return jsonApiResponse(input, readyStatus)
    })
    render(<App />)
    await act(async () => { await Promise.resolve() })

    expect(screen.getByRole('status')).toHaveTextContent('PrivateKB 로컬 서비스를 시작하고 있습니다.')
    expect(screen.getByRole('button', { name: 'AI 확인 대기' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Ollama와 모델 상태 확인을 준비하고 있습니다.')
    expect(screen.getByRole('status')).not.toHaveTextContent('백엔드 준비와 별개로')
    expect(screen.getByLabelText(`프로그램 준비 자동 확인 1/${BACKEND_STARTUP_MAX_ATTEMPTS}`)).toBeInTheDocument()
    expect(screen.getByLabelText('3개 기능 상태 확인 중')).toHaveTextContent('확인 중')
    expect(screen.getAllByText('확인 중').length).toBeGreaterThanOrEqual(4)
    expect(screen.queryByText('사용 불가')).not.toBeInTheDocument()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(BACKEND_STARTUP_RETRY_INTERVAL_MS * 2)
    })

    expect(statusAttempts).toBe(3)
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(document.querySelector('.startup-readiness-collapse.closing')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'AI 상태' })).toBeInTheDocument()
    expect(screen.getByLabelText('3개 기능 중 3개 사용 가능')).toHaveTextContent('3/3')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(STARTUP_STATUS_EXIT_DURATION_MS)
    })
    expect(document.querySelector('.startup-readiness-collapse')).not.toBeInTheDocument()
  })

  it('제한 시간 동안 로컬 서비스 연결에 실패하면 오류와 수동 재확인을 표시한다', async () => {
    vi.useFakeTimers()
    vi.mocked(fetch).mockRejectedValue(new TypeError('connection refused'))
    render(<App />)
    await act(async () => {
      await Promise.resolve()
      await vi.advanceTimersByTimeAsync(
        BACKEND_STARTUP_RETRY_INTERVAL_MS * (BACKEND_STARTUP_MAX_ATTEMPTS - 1),
      )
    })

    expect(screen.getByRole('alert')).toHaveTextContent('PrivateKB 로컬 서비스를 시작하지 못했습니다.')
    expect(screen.getByRole('alert')).toHaveTextContent(`${BACKEND_STARTUP_MAX_ATTEMPTS}초 동안 자동 확인했지만 응답이 없습니다.`)
    expect(screen.getByLabelText('3개 기능 중 0개 사용 가능')).toHaveTextContent('0/3')
    expect(screen.queryByRole('button', { name: '문서 추가' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '첫 문서 추가' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '두 상태 다시 확인' })).toBeEnabled()
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(BACKEND_STARTUP_MAX_ATTEMPTS)
  })

  it('데스크톱 실행 관리자가 실패하면 남아 있는 HTTP 서비스 응답을 정상으로 오인하지 않는다', async () => {
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
    vi.mocked(invoke).mockResolvedValue({ phase: 'ERROR', errorCode: 'RUNTIME_START_FAILED', backendPort: null })
    render(<App />)

    expect(await screen.findByRole('alert')).toHaveTextContent('PrivateKB 로컬 서비스를 시작하지 못했습니다.')
    expect(vi.mocked(fetch)).not.toHaveBeenCalled()
  })

  it('프로그램이 먼저 준비되면 초기 과도 상태에서도 AI 확인을 별도로 계속한다', async () => {
    vi.useFakeTimers()
    const pendingStatus: LocalAiStatus = {
      ...readyStatus,
      ollama: { status: 'CHECKING', version: null, errorCode: null },
      embedding: { ...readyStatus.embedding, status: 'CHECKING', digest: null, sizeBytes: 0 },
      chat: { ...readyStatus.chat, status: 'CHECKING', digest: null, sizeBytes: 0 },
      capabilities: { documentManagement: true, semanticSearch: false, groundedAnswer: false },
      lastCheckedAt: null,
      checkInProgress: false,
    }
    const runningStatus = { ...pendingStatus, checkInProgress: true }
    let statusRequests = 0
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.endsWith('/api/local-ai/checks')) return jsonResponse(runningStatus, 202)
      if (url.endsWith('/api/local-ai/status')) {
        statusRequests += 1
        return jsonResponse(statusRequests === 1 ? pendingStatus : readyStatus)
      }
      return jsonApiResponse(input, readyStatus)
    })

    render(<App />)
    await act(async () => { await Promise.resolve() })

    expect(screen.getByLabelText('앱 상태: 준비됨')).toHaveTextContent('앱 상태 · 준비됨')
    expect(screen.getByRole('button', { name: 'AI 확인 중' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('프로그램 준비 · 준비됨')
    expect(screen.getByRole('status')).toHaveTextContent('로컬 AI · 확인 중')
    expect(screen.getByRole('status')).not.toHaveTextContent('백엔드')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(AI_STATUS_RETRY_INTERVAL_MS)
    })

    expect(screen.getByRole('button', { name: 'AI 상태' })).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(statusRequests).toBe(2)
  })

  it('AI 확인이 실패해도 완료된 프로그램 상태를 오류로 되돌리지 않는다', async () => {
    vi.useFakeTimers()
    const runningStatus: LocalAiStatus = {
      ...readyStatus,
      ollama: { status: 'CHECKING', version: null, errorCode: null },
      embedding: { ...readyStatus.embedding, status: 'CHECKING', digest: null, sizeBytes: 0 },
      chat: { ...readyStatus.chat, status: 'CHECKING', digest: null, sizeBytes: 0 },
      capabilities: { documentManagement: true, semanticSearch: false, groundedAnswer: false },
      checkInProgress: true,
    }
    let statusRequests = 0
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).endsWith('/api/local-ai/status')) {
        statusRequests += 1
        if (statusRequests > 1) throw new TypeError('AI 상태 조회 실패')
        return jsonResponse(runningStatus)
      }
      return jsonApiResponse(input, readyStatus)
    })

    render(<App />)
    await act(async () => { await Promise.resolve() })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(AI_STATUS_RETRY_INTERVAL_MS)
    })

    expect(screen.getByLabelText('앱 상태: 준비됨')).toHaveTextContent('앱 상태 · 준비됨')
    expect(screen.getByRole('button', { name: 'AI 확인 실패' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('프로그램 준비 · 준비됨')
    expect(screen.getByRole('alert')).toHaveTextContent('로컬 AI · 확인 실패')
    expect(screen.getByRole('alert')).not.toHaveTextContent('백엔드')
  })

  it('사용자가 팝업에서 AI 상태를 다시 확인하면 본문 준비 영역을 만들지 않는다', async () => {
    const runningStatus: LocalAiStatus = {
      ...readyStatus,
      ollama: { status: 'CHECKING', version: null, errorCode: null },
      embedding: { ...readyStatus.embedding, status: 'CHECKING', digest: null, sizeBytes: 0 },
      chat: { ...readyStatus.chat, status: 'CHECKING', digest: null, sizeBytes: 0 },
      capabilities: { documentManagement: true, semanticSearch: false, groundedAnswer: false },
      checkInProgress: true,
    }
    vi.mocked(fetch).mockImplementation(async (input) => (
      String(input).endsWith('/api/local-ai/checks')
        ? jsonResponse(runningStatus, 202)
        : jsonApiResponse(input, readyStatus)
    ))
    const user = userEvent.setup()

    render(<App />)
    await user.click(await screen.findByRole('button', { name: 'AI 상태' }))
    await waitFor(() => expect(document.querySelector('.startup-readiness')).not.toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '상태 다시 확인' }))

    await waitFor(() => expect(screen.getByRole('dialog', { name: 'AI 연결 상태' })).toHaveTextContent('확인하고 있습니다'))
    expect(screen.getByRole('button', { name: 'AI 확인 중' })).toBeInTheDocument()
    expect(document.querySelector('.startup-readiness')).not.toBeInTheDocument()
  })

  it('명시적 안내 확인 뒤 고정된 대화 모델 설치를 요청하고 상태를 갱신한다', async () => {
    const missingChat: LocalAiStatus = {
      ...readyStatus,
      chat: { ...readyStatus.chat, status: 'NOT_INSTALLED', digest: null, sizeBytes: 0, errorCode: 'MODEL_NOT_INSTALLED' },
      capabilities: { documentManagement: true, semanticSearch: true, groundedAnswer: false },
    }
    const downloading: ModelInstallJob = {
      jobId: 'install-1', role: 'CHAT', model: 'qwen3.5:4b', status: 'DOWNLOADING',
      progressPercent: 35, completedBytes: 2_000, totalBytes: 6_000, errorCode: null,
      createdAt: '2026-08-27T01:00:00Z', updatedAt: '2026-08-27T01:00:01Z',
    }
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = String(input)
      if (url.endsWith('/models/chat/install')) return jsonResponse(downloading, 202)
      if (url.endsWith('/model-installs/install-1')) {
        return jsonResponse({ ...downloading, status: 'COMPLETED', progressPercent: 100 })
      }
      if (url.endsWith('/checks')) return jsonResponse(readyStatus, 202)
      return jsonApiResponse(input, missingChat)
    })
    const user = userEvent.setup()
    render(<App />)

    await user.click(await screen.findByRole('button', { name: 'AI 상태' }))
    await user.click(screen.getByRole('button', { name: 'AI 모델 설치' }))

    expect(screen.getByLabelText('모델 설치 확인')).toHaveTextContent('약 3.4GB를 인터넷에서 다운로드합니다')
    expect(screen.getByLabelText('모델 설치 확인')).toHaveTextContent('문서·질문·답변은 전송하지 않습니다')
    await user.click(screen.getByRole('button', { name: '설치 시작' }))

    await waitFor(
      () => expect(screen.getByLabelText('3개 기능 중 3개 사용 가능')).toHaveTextContent('3/3'),
      { timeout: 2500 },
    )
    expect(vi.mocked(fetch)).toHaveBeenCalledWith('/api/local-ai/models/chat/install', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ confirmed: true }),
    }))
  })

  it('검색어를 전송하고 문서 근거 구간을 표시한다', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).includes('/search')) {
        return new Response(JSON.stringify({
          results: [{
            chunkId: 'chunk-1',
            documentId: 'document-1',
            documentVersionId: 'version-1',
            originalFilename: '장애대응회의록.txt',
            versionNumber: 1,
            chunkIndex: 0,
            startOffset: 0,
            endOffset: 25,
            content: '재발 방지를 위해 경보 임계치를 조정하기로 결정했습니다.',
            score: 0.91,
          }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      return jsonApiResponse(input, readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    const input = await screen.findByRole('textbox', { name: '지식 검색' })
    await user.type(input, '장애 대응 결정')
    await user.click(screen.getByRole('button', { name: '지식 검색 실행' }))

    expect(await screen.findByText('장애대응회의록.txt')).toBeInTheDocument()
    expect(screen.getByText(/경보 임계치를 조정/)).toBeInTheDocument()
    expect(screen.getByText('관련도 91%')).toBeInTheDocument()
    expect(screen.getByLabelText('최근 질문')).toHaveTextContent('장애 대응 결정')
  })

  it('최근 의미 검색 질문을 중복 없이 최신 3개만 저장해 표시한다', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).includes('/search')) return jsonResponse({ results: [] })
      return jsonApiResponse(input, readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    const input = await screen.findByRole('textbox', { name: '지식 검색' })
    const searchButton = screen.getByRole('button', { name: '지식 검색 실행' })
    for (const search of ['첫 번째 검색', '두 번째 검색', '세 번째 검색', '네 번째 검색']) {
      await user.clear(input)
      await user.type(input, search)
      await user.click(searchButton)
      await waitFor(() => expect(screen.getByLabelText('최근 질문')).toHaveTextContent(search))
    }

    const recentQuestions = screen.getByLabelText('최근 질문')
    expect(recentQuestions.querySelectorAll('button')).toHaveLength(3)
    expect(recentQuestions).toHaveTextContent('네 번째 검색세 번째 검색두 번째 검색')
    expect(recentQuestions).not.toHaveTextContent('첫 번째 검색')
    expect(JSON.parse(window.localStorage.getItem('privatekb.recent-searches') ?? '[]')).toEqual([
      '네 번째 검색',
      '세 번째 검색',
      '두 번째 검색',
    ])

    const previousSearchCount = vi.mocked(fetch).mock.calls.filter(([request]) => String(request).includes('/search')).length
    await user.click(screen.getByRole('button', { name: '세 번째 검색' }))

    await waitFor(() => {
      const searchCalls = vi.mocked(fetch).mock.calls.filter(([request]) => String(request).includes('/search'))
      expect(searchCalls).toHaveLength(previousSearchCount + 1)
      expect(searchCalls.at(-1)?.[1]).toEqual(expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ query: '세 번째 검색', limit: 5 }),
      }))
    })
    expect(input).toHaveValue('세 번째 검색')
    expect(JSON.parse(window.localStorage.getItem('privatekb.recent-searches') ?? '[]')[0]).toBe('세 번째 검색')
  })

  it('근거 기반 답변 스트림과 인용 문서를 순서대로 표시한다', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).includes('/answers/stream')) {
        return new Response([
          'event: citations',
          'data:{"citations":[{"sourceNumber":1,"chunkId":"chunk-1","documentId":"document-1","documentVersionId":"version-1","originalFilename":"장애대응회의록.txt","versionNumber":1,"chunkIndex":0,"startOffset":0,"endOffset":25,"sourceFolderPath":"C:\\\\업무\\\\고객사\\\\OO회사","excerpt":"경보 임계치를 조정하기로 결정했습니다.","score":0.91},{"sourceNumber":2,"chunkId":"chunk-2","documentId":"document-2","documentVersionId":"version-2","originalFilename":"운영계획.txt","versionNumber":1,"chunkIndex":0,"startOffset":0,"endOffset":20,"excerpt":"재발 방지 담당자를 지정했습니다.","score":0.87},{"sourceNumber":3,"chunkId":"chunk-3","documentId":"document-3","documentVersionId":"version-3","originalFilename":"제품회의.txt","versionNumber":1,"chunkIndex":0,"startOffset":0,"endOffset":20,"excerpt":"후속 일정을 확정했습니다.","score":0.82},{"sourceNumber":4,"chunkId":"chunk-4","documentId":"document-4","documentVersionId":"version-4","originalFilename":"고객미팅.txt","versionNumber":1,"chunkIndex":0,"startOffset":0,"endOffset":20,"excerpt":"고객 안내 문구를 수정했습니다.","score":0.78}]}',
          '',
          'event: token',
          'data:{"text":"경보 임계치를 "}',
          '',
          'event: token',
          'data:{"text":"조정했습니다. [1]"}',
          '',
          'event: complete',
          'data:{"refused":false}',
          '',
          '',
        ].join('\n'), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
      }
      return jsonApiResponse(input, readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    const input = await screen.findByRole('textbox', { name: '문서에 질문' })
    await user.type(input, '장애 대응 결정은?')
    await user.click(screen.getByRole('button', { name: '답변 생성' }))

    expect(await screen.findByText('경보 임계치를 조정했습니다. [1]')).toBeInTheDocument()
    expect(screen.getByLabelText('생성된 답변')).toHaveClass('answer-response-scroll')
    expect(screen.getByText('[1] 장애대응회의록.txt')).toBeInTheDocument()
    expect(screen.getByText('경보 임계치를 조정하기로 결정했습니다.')).toBeInTheDocument()
    expect(screen.getByText('관련도 91%')).toBeInTheDocument()
    expect(screen.getByText('원본 폴더 · C:\\업무\\고객사\\OO회사')).toBeInTheDocument()
    expect(screen.getByLabelText('참고한 근거 목록')).toHaveClass('answer-citation-list')
    expect(screen.getByLabelText('참고한 근거 목록').querySelectorAll('article')).toHaveLength(4)
  })

  it('질문 입력창에서 Enter는 답변을 생성하고 Shift+Enter는 줄을 바꾼다', async () => {
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).includes('/answers/stream')) {
        return new Response([
          'event: token',
          'data:{"text":"키보드로 생성한 답변"}',
          '',
          'event: complete',
          'data:{"refused":false}',
          '',
          '',
        ].join('\n'), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
      }
      return jsonApiResponse(input, readyStatus)
    })
    const user = userEvent.setup()
    render(<App />)

    const input = await screen.findByRole('textbox', { name: '문서에 질문' })
    await user.type(input, '첫째 줄{Shift>}{Enter}{/Shift}둘째 줄')

    expect(input).toHaveValue('첫째 줄\n둘째 줄')
    expect(fetch).not.toHaveBeenCalledWith(expect.stringContaining('/answers/stream'), expect.anything())

    await user.type(input, '{Enter}')

    expect(await screen.findByText('키보드로 생성한 답변')).toBeInTheDocument()
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('/answers/stream'), expect.objectContaining({ method: 'POST' }))
  })
})

function jsonResponse<T>(body: T, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function jsonApiResponse(input: RequestInfo | URL, status: LocalAiStatus) {
  return String(input).includes('/documents?')
    ? jsonResponse(emptyDocumentPage)
    : jsonResponse(status)
}

function createDocumentStatus(index: number, status: DocumentStatusItem['status']): DocumentStatusItem {
  const ingestionStatus = status === 'FAILED' ? 'FAILED' : 'PARSED'
  const indexingStatus: DocumentStatusItem['indexingStatus'] = {
    PROCESSING: 'INDEXING',
    WAITING_FOR_MODEL: 'MODEL_WAITING',
    REINDEX_REQUIRED: 'REINDEX_REQUIRED',
    COMPLETED: 'INDEXED',
    FAILED: 'FAILED',
  }[status] as DocumentStatusItem['indexingStatus']
  return {
    documentId: `document-${index}`,
    documentVersionId: `version-${index}`,
    versionNumber: 1,
    originalFilename: `최근 문서 ${index}.txt`,
    detectedMediaType: 'text/plain',
    byteSize: 1024 * index,
    status,
    ingestionStatus,
    indexingStatus,
    errorCode: status === 'FAILED' ? 'PARSE_FAILED' : null,
    createdAt: `2026-08-${String(30 - index).padStart(2, '0')}T01:00:00Z`,
    updatedAt: `2026-08-${String(30 - index).padStart(2, '0')}T01:01:00Z`,
  }
}
