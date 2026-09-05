import { beforeEach, describe, expect, it } from 'vitest'
import { apiUrl, configureDesktopApiPort, resetApiBaseUrlForTests } from './base'

describe('PrivateKB API 주소 정책', () => {
  beforeEach(() => resetApiBaseUrlForTests())

  it('일반 개발 화면에서는 같은 출처 API 경로를 사용한다', () => {
    expect(apiUrl('/api/local-ai/status')).toBe('/api/local-ai/status')
  })

  it('API가 아닌 경로는 거부한다', () => {
    expect(() => apiUrl('/actuator/health')).toThrow('/api/로 시작')
  })

  it('데스크톱 실행 관리자가 선택한 루프백 포트를 사용한다', () => {
    configureDesktopApiPort(18080)

    expect(apiUrl('/api/local-ai/status')).toBe('http://127.0.0.1:18080/api/local-ai/status')
  })

  it('유효하지 않은 데스크톱 포트는 거부한다', () => {
    expect(() => configureDesktopApiPort(0)).toThrow('포트가 올바르지 않습니다')
    expect(() => configureDesktopApiPort(65536)).toThrow('포트가 올바르지 않습니다')
    expect(() => configureDesktopApiPort(12.5)).toThrow('포트가 올바르지 않습니다')
  })
})
