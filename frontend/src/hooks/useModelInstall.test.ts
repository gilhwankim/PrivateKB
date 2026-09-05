import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelModelInstall, getModelInstall, startModelInstall } from '../api/localAi'
import type { ModelInstallJob } from '../types/localAi'
import { MODEL_INSTALL_POLL_INTERVAL_MS, useModelInstall } from './useModelInstall'

vi.mock('../api/localAi', () => ({ startModelInstall: vi.fn(), getModelInstall: vi.fn(), cancelModelInstall: vi.fn() }))

const downloading: ModelInstallJob = {
  jobId: 'install-1', role: 'CHAT', model: 'qwen3.5:9b', status: 'DOWNLOADING',
  progressPercent: 18, completedBytes: 1_200_000_000, totalBytes: 6_600_000_000,
  errorCode: null, createdAt: '2026-09-01T01:00:00Z', updatedAt: '2026-09-01T01:00:01Z',
}
const completed: ModelInstallJob = { ...downloading, status: 'COMPLETED', progressPercent: 100 }

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('앱 공통 모델 설치 상태', () => {
  beforeEach(() => { vi.useFakeTimers(); vi.resetAllMocks() })
  afterEach(() => { cleanup(); vi.useRealTimers() })

  it('응답 전 같은 프레임의 연속 클릭과 다른 역할 설치도 한 요청만 보낸다', async () => {
    const start = deferred<ModelInstallJob>()
    const poll = deferred<ModelInstallJob>()
    vi.mocked(startModelInstall).mockReturnValue(start.promise)
    vi.mocked(getModelInstall).mockReturnValue(poll.promise)
    const refresh = vi.fn()
    const { result } = renderHook(() => useModelInstall(refresh))
    act(() => { void result.current.start('CHAT'); void result.current.start('CHAT'); void result.current.start('EMBEDDING') })
    expect(startModelInstall).toHaveBeenCalledTimes(1)
    expect(result.current.startingRole).toBe('CHAT')
    expect(result.current.busy).toBe(true)
    await act(async () => { start.resolve(downloading) })
    act(() => { void result.current.start('CHAT') })
    expect(startModelInstall).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(MODEL_INSTALL_POLL_INTERVAL_MS) })
    await act(async () => { await vi.advanceTimersByTimeAsync(MODEL_INSTALL_POLL_INTERVAL_MS * 3) })
    expect(getModelInstall).toHaveBeenCalledTimes(1)
    await act(async () => { poll.resolve(completed) })
    expect(result.current.job?.status).toBe('COMPLETED')
    expect(refresh).toHaveBeenCalledOnce()
    expect(result.current.busy).toBe(false)
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(getModelInstall).toHaveBeenCalledTimes(1)
  })

  it('조회 실패 시 작업을 유지하고 새 설치 없이 기존 작업 상태만 다시 확인한다', async () => {
    vi.mocked(startModelInstall).mockResolvedValue(downloading)
    vi.mocked(getModelInstall).mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(completed)
    const { result } = renderHook(() => useModelInstall(vi.fn()))
    await act(async () => { void result.current.start('CHAT') })
    await act(async () => { await vi.advanceTimersByTimeAsync(MODEL_INSTALL_POLL_INTERVAL_MS) })
    expect(result.current.error).toContain('다운로드가 계속될 수 있으므로')
    expect(result.current.job).toEqual(downloading)
    expect(result.current.canRetry).toBe(true)
    expect(result.current.busy).toBe(true)
    await act(async () => { await result.current.start('CHAT'); await result.current.retry() })
    expect(startModelInstall).toHaveBeenCalledTimes(1)
    expect(getModelInstall).toHaveBeenLastCalledWith('install-1', expect.any(AbortSignal))
    expect(result.current.error).toBe('')
    expect(result.current.job?.status).toBe('COMPLETED')
    expect(result.current.canRetry).toBe(false)
  })

  it('취소 응답 이후 늦게 도착한 조회가 취소 요청 상태를 되돌리지 않는다', async () => {
    const poll = deferred<ModelInstallJob>()
    const cancel = deferred<ModelInstallJob>()
    vi.mocked(startModelInstall).mockResolvedValue(downloading)
    vi.mocked(getModelInstall).mockReturnValueOnce(poll.promise).mockResolvedValueOnce({ ...downloading, status: 'CANCELLED' })
    vi.mocked(cancelModelInstall).mockReturnValue(cancel.promise)
    const { result } = renderHook(() => useModelInstall(vi.fn()))
    await act(async () => { void result.current.start('CHAT') })
    await act(async () => { await vi.advanceTimersByTimeAsync(MODEL_INSTALL_POLL_INTERVAL_MS) })
    act(() => { void result.current.cancel(); void result.current.cancel() })
    expect(cancelModelInstall).toHaveBeenCalledTimes(1)
    await act(async () => { cancel.resolve({ ...downloading, status: 'CANCEL_REQUESTED' }) })
    await act(async () => { poll.resolve({ ...downloading, progressPercent: 19 }) })
    expect(result.current.job?.status).toBe('CANCEL_REQUESTED')
    await act(async () => { await result.current.cancel() })
    expect(cancelModelInstall).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(MODEL_INSTALL_POLL_INTERVAL_MS) })
    expect(result.current.job?.status).toBe('CANCELLED')
    expect(result.current.busy).toBe(false)
  })

  it('설치 완료 직후 AI 준비 상태를 갱신하는 동안에도 시작 버튼 잠금을 유지한다', async () => {
    const refreshed = deferred<void>()
    vi.mocked(startModelInstall).mockResolvedValue(completed)
    const { result } = renderHook(() => useModelInstall(() => refreshed.promise))
    await act(async () => { void result.current.start('CHAT') })
    expect(result.current.refreshing).toBe(true)
    expect(result.current.busy).toBe(true)
    await act(async () => { await result.current.start('CHAT') })
    expect(startModelInstall).toHaveBeenCalledOnce()
    await act(async () => { refreshed.resolve() })
    expect(result.current.busy).toBe(false)
  })

  it.each(['FAILED', 'CANCELLED'] as const)('%s 이후 자동 재설치하지 않고 명시적인 재요청은 허용한다', async status => {
    vi.mocked(startModelInstall).mockResolvedValueOnce({ ...downloading, status }).mockResolvedValueOnce({ ...completed, jobId: 'install-2' })
    const { result } = renderHook(() => useModelInstall(vi.fn()))
    await act(async () => { await result.current.start('CHAT') })
    expect(result.current.job?.status).toBe(status)
    expect(result.current.busy).toBe(false)
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(startModelInstall).toHaveBeenCalledTimes(1)
    await act(async () => { await result.current.start('CHAT') })
    expect(startModelInstall).toHaveBeenCalledTimes(2)
    expect(result.current.job?.jobId).toBe('install-2')
  })

  it('앱 자체가 종료되면 조회만 중단하고 서버에 설치 취소 요청은 보내지 않는다', async () => {
    vi.mocked(startModelInstall).mockResolvedValue(downloading)
    vi.mocked(getModelInstall).mockReturnValue(new Promise(() => {}))
    const { result, unmount } = renderHook(() => useModelInstall(vi.fn()))
    await act(async () => { void result.current.start('CHAT') })
    await act(async () => { await vi.advanceTimersByTimeAsync(MODEL_INSTALL_POLL_INTERVAL_MS) })
    const signal = vi.mocked(getModelInstall).mock.calls[0][1]!
    unmount()
    expect(signal.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(5000)
    expect(getModelInstall).toHaveBeenCalledOnce()
    expect(cancelModelInstall).not.toHaveBeenCalled()
  })
})
