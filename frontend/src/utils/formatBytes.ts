/** 파일·모델 용량은 사용자 화면 전체에서 1,000바이트 기준으로 표시한다. */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0B'
  if (bytes < 1000) return `${Math.round(bytes)}B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1000
  let unit = 0
  while (unit < units.length - 1 && Number(value.toFixed(1)) >= 1000) {
    value /= 1000
    unit += 1
  }
  return `${value.toFixed(1)}${units[unit]}`
}
