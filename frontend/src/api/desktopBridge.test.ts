import { beforeEach, describe, expect, it, vi } from 'vitest'
import { invoke } from '@tauri-apps/api/core'
import { apiUrl, resetApiBaseUrlForTests } from './base'
import { getDesktopRuntimeStatus, importDesktopSelection } from './desktopBridge'

vi.mock('@tauri-apps/api/core', () => ({
  invoke: vi.fn(),
  Channel: class { onmessage = () => undefined },
}))

describe('데스크톱 실행 상태 연결', () => {
  beforeEach(() => {
    resetApiBaseUrlForTests()
    vi.mocked(invoke).mockReset()
  })

  it('준비된 앱 서버 포트를 이후 API 요청에 적용한다', async () => {
    vi.mocked(invoke).mockResolvedValue({
      phase: 'READY',
      errorCode: null,
      backendPort: 18080,
    })

    await getDesktopRuntimeStatus()

    expect(apiUrl('/api/local-ai/status')).toBe('http://127.0.0.1:18080/api/local-ai/status')
  })

  it('시작 중에는 기존 API 주소를 바꾸지 않는다', async () => {
    vi.mocked(invoke).mockResolvedValue({
      phase: 'STARTING',
      errorCode: null,
      backendPort: null,
    })

    await getDesktopRuntimeStatus()

    expect(apiUrl('/api/local-ai/status')).toBe('/api/local-ai/status')
  })

  it('문서 등록 진행 상황을 데스크톱 채널에서 화면 콜백으로 전달한다', async () => {
    const progress = {
      requestedCount: 20,
      completedCount: 7,
      acceptedCount: 6,
      duplicateCount: 1,
      failedCount: 0,
      stoppedCount: 0,
      stopReason: null,
    }
    const result = { requestedCount: 20, acceptedCount: 19, duplicateCount: 1, failedCount: 0, stoppedCount: 0, stopReason: null }
    vi.mocked(invoke).mockImplementation(async (_command, args) => {
      const channel = (args as { onProgress: { onmessage: (value: typeof progress) => void } }).onProgress
      channel.onmessage(progress)
      return result
    })
    const onProgress = vi.fn()

    await expect(importDesktopSelection('selection-1', onProgress)).resolves.toEqual(result)

    expect(onProgress).toHaveBeenCalledExactlyOnceWith(progress)
    expect(invoke).toHaveBeenCalledWith('import_document_selection', expect.objectContaining({ selectionId: 'selection-1' }))
  })
})
