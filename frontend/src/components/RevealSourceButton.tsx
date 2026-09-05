import { useEffect, useRef, useState } from 'react'
import { isDesktopShell, revealDocumentSource } from '../api/desktopBridge'
import { FolderIcon, RefreshIcon } from './icons'

export const SOURCE_ERROR_VISIBLE_MS = 4000
export const SOURCE_ERROR_EXIT_MS = 280

export function RevealSourceButton({ documentVersionId, filename }: { documentVersionId: string; filename: string }) {
  const [busy, setBusy] = useState(false)
  const [errorNotice, setErrorNotice] = useState<{ message: string, exiting: boolean } | null>(null)
  const mounted = useRef(true)
  const inFlight = useRef(false)
  const exitTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const removeTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearNoticeTimers = () => {
    if (exitTimer.current !== null) clearTimeout(exitTimer.current)
    if (removeTimer.current !== null) clearTimeout(removeTimer.current)
    exitTimer.current = null
    removeTimer.current = null
  }

  const showErrorNotice = (message: string) => {
    clearNoticeTimers()
    setErrorNotice({ message, exiting: false })
    exitTimer.current = setTimeout(() => {
      if (!mounted.current) return
      setErrorNotice((notice) => notice ? { ...notice, exiting: true } : null)
      removeTimer.current = setTimeout(() => {
        if (mounted.current) setErrorNotice(null)
        removeTimer.current = null
      }, SOURCE_ERROR_EXIT_MS)
      exitTimer.current = null
    }, SOURCE_ERROR_VISIBLE_MS)
  }

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      clearNoticeTimers()
    }
  }, [])
  const desktop = isDesktopShell()

  const reveal = async () => {
    if (inFlight.current || !desktop) return
    inFlight.current = true
    setBusy(true)
    clearNoticeTimers()
    setErrorNotice(null)
    try {
      await revealDocumentSource(documentVersionId)
    } catch (error) {
      if (mounted.current) showErrorNotice(error instanceof Error ? error.message : '원본 위치를 열 수 없습니다.')
    } finally {
      inFlight.current = false
      if (mounted.current) setBusy(false)
    }
  }

  return (
    <div className="source-shortcut">
      <button type="button" className="source-reveal-button" disabled={!desktop || busy}
        aria-label={`${filename} 폴더에서 보기`} aria-busy={busy}
        title={desktop ? '탐색기에서 원본 파일 위치 열기' : 'Windows 프로그램에서 사용할 수 있습니다.'}
        onClick={() => void reveal()}>
        {busy ? <RefreshIcon width={15} height={15} className="spin" /> : <FolderIcon width={15} height={15} />}
        {busy ? '위치 확인 중' : '폴더에서 보기'}
      </button>
      {errorNotice && <p className={`source-reveal-tooltip ${errorNotice.exiting ? 'is-exiting' : ''}`} role="alert">{errorNotice.message}</p>}
    </div>
  )
}
