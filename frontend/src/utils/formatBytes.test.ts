import { describe, expect, it } from 'vitest'
import { formatBytes } from './formatBytes'

describe('사용자 화면의 용량 단위', () => {
  it.each([
    [0, '0B'], [NaN, '0B'], [-1, '0B'], [999, '999B'], [1000, '1.0KB'],
    [1024, '1.0KB'], [3072, '3.1KB'], [1_000_000, '1.0MB'], [1_048_576, '1.0MB'],
    [25 * 1024 * 1024, '26.2MB'], [1_000_000_000, '1.0GB'], [10 * 1024 ** 3, '10.7GB'],
    [999_999, '1.0MB'], [999_999_999, '1.0GB'],
  ])('%s바이트를 %s로 표시한다', (bytes, expected) => {
    expect(formatBytes(bytes)).toBe(expected)
  })
})
