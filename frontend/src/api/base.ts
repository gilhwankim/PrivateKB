const ALLOWED_DESKTOP_API_BASE_URLS = new Set([
  '',
  'http://127.0.0.1:8080',
])

if (!ALLOWED_DESKTOP_API_BASE_URLS.has(__PRIVATEKB_API_BASE_URL__)) {
  throw new Error('허용되지 않은 PrivateKB API 주소입니다.')
}

const INITIAL_API_BASE_URL = __PRIVATEKB_API_BASE_URL__
let activeApiBaseUrl = INITIAL_API_BASE_URL

export function configureDesktopApiPort(port: number): void {
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('PrivateKB 앱 연결 포트가 올바르지 않습니다.')
  }
  activeApiBaseUrl = `http://127.0.0.1:${port}`
}

export function resetApiBaseUrlForTests(): void {
  activeApiBaseUrl = INITIAL_API_BASE_URL
}

export function apiUrl(path: string): string {
  if (!path.startsWith('/api/')) {
    throw new Error('PrivateKB API 경로는 /api/로 시작해야 합니다.')
  }
  return `${activeApiBaseUrl}${path}`
}
