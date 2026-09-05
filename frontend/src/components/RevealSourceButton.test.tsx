import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { RevealSourceButton, SOURCE_ERROR_EXIT_MS, SOURCE_ERROR_VISIBLE_MS } from './RevealSourceButton'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  Channel: class { onmessage = () => undefined },
}))

describe('원본 폴더 바로가기', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.mocked(invoke).mockReset()
    Object.defineProperty(window, '__TAURI_INTERNALS__', { value: {}, configurable: true })
  })
  afterEach(() => {
    cleanup()
    vi.useRealTimers()
    delete (window as Window & { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
  })
  const show = () => render(<RevealSourceButton documentVersionId="20000000-0000-0000-0000-000000000001" filename="회의록.txt" />)

  it('클릭 전에는 조회하지 않고 네이티브 연결에는 문서 ID만 전달한다', async () => {
    vi.mocked(invoke).mockResolvedValue({ sourceChanged: false })
    const user = userEvent.setup()
    show()
    expect(invoke).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '회의록.txt 폴더에서 보기' }))
    expect(invoke).toHaveBeenCalledExactlyOnceWith('reveal_document_source', { documentVersionId: '20000000-0000-0000-0000-000000000001' })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText('탐색기에서 원본 파일을 선택했습니다.')).not.toBeInTheDocument()
  })

  it('원본 메타데이터가 달라도 폴더가 열리면 성공 안내를 표시하지 않는다', async () => {
    vi.mocked(invoke).mockResolvedValue({ sourceChanged: true })
    const user = userEvent.setup()
    show()
    await user.click(screen.getByRole('button'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/검색한 내용과 다를 수 있습니다/)).not.toBeInTheDocument()
  })

  it.each([
    ['SOURCE_NOT_RECORDED', '원본 위치가 기록되지 않은'],
    ['SOURCE_MISSING', '원본 파일을 찾을 수 없습니다'],
    ['SOURCE_UNAVAILABLE', '권한과 드라이브 연결'],
    ['SOURCE_UNSAFE', '네트워크 위치나 링크 경로'],
    ['APP_NOT_READY', '프로그램이 아직 준비되지'],
    ['REVEAL_BUSY', '다른 파일의 위치'],
    ['C:\\비공개\\카나리.txt', '원본 위치를 열 수 없습니다'],
  ])('%s 오류를 경로 노출 없이 안내한다', async (code, message) => {
    vi.mocked(invoke).mockRejectedValue(code)
    const user = userEvent.setup()
    show()
    await user.click(screen.getByRole('button'))
    expect(screen.getByRole('alert')).toHaveTextContent(message)
    expect(screen.getByRole('alert')).toHaveClass('source-reveal-tooltip')
    expect(screen.queryByText(/카나리/)).not.toBeInTheDocument()
    expect(screen.getByRole('button')).toBeEnabled()
  })

  it('오류 툴팁은 잠시 표시한 뒤 아래로 내려가며 사라진다', async () => {
    vi.useFakeTimers()
    vi.mocked(invoke).mockRejectedValue('SOURCE_MISSING')
    show()
    fireEvent.click(screen.getByRole('button'))
    await act(async () => { await Promise.resolve() })

    expect(screen.getByRole('alert')).not.toHaveClass('is-exiting')
    await act(async () => { await vi.advanceTimersByTimeAsync(SOURCE_ERROR_VISIBLE_MS) })
    expect(screen.getByRole('alert')).toHaveClass('is-exiting')
    await act(async () => { await vi.advanceTimersByTimeAsync(SOURCE_ERROR_EXIT_MS) })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('작업 중 중복 클릭을 막고 제한 시간 후 대기 표시를 종료한다', async () => {
    vi.useFakeTimers()
    vi.mocked(invoke).mockReturnValue(new Promise(() => undefined))
    show()
    fireEvent.click(screen.getByRole('button'))
    expect(screen.getByRole('button')).toBeDisabled()
    fireEvent.click(screen.getByRole('button'))
    expect(invoke).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(10000) })
    expect(screen.getByRole('alert')).toHaveTextContent('위치 확인이 지연')
    expect(screen.getByRole('button')).toBeEnabled()
  })

  it('키보드 Enter로도 실행한다', async () => {
    vi.mocked(invoke).mockResolvedValue({ sourceChanged: false })
    const user = userEvent.setup()
    show()
    screen.getByRole('button').focus()
    await user.keyboard('{Enter}')
    await waitFor(() => expect(invoke).toHaveBeenCalledTimes(1))
  })

  it('일반 브라우저에서는 비활성화하고 데스크톱 전용임을 안내한다', () => {
    delete (window as Window & { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
    show()
    expect(screen.getByRole('button')).toBeDisabled()
    expect(screen.getByRole('button')).toHaveAttribute('title', 'Windows 프로그램에서 사용할 수 있습니다.')
    expect(invoke).not.toHaveBeenCalled()
  })
})
