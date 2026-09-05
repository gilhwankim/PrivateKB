import { useEffect, useRef, useState } from 'react'
import { getDesktopSelectionExclusions } from '../api/desktopBridge'
import type { DesktopImportProgress, DesktopSelectionPreview, ExclusionReason } from '../api/desktopBridge'
import { formatBytes } from '../utils/formatBytes'
import { DocumentIcon, FolderIcon, RefreshIcon } from './icons'

const reasonLabels: Record<ExclusionReason, string> = {
  UNSUPPORTED: '지원하지 않는 형식', EMPTY: '빈 파일', TOO_LARGE: '파일 크기 제한 초과',
  HIDDEN_SYSTEM_OR_TEMPORARY: '숨김·시스템·임시 항목', REPARSE_POINT: '링크·연결 폴더',
  INACCESSIBLE: '접근할 수 없음', DEPTH_EXCEEDED: '탐색 깊이 초과',
}

export function SelectionPreviewDialog({ preview, importing, progress, notice, onCancel, onConfirm }: {
  preview: DesktopSelectionPreview
  importing: boolean
  progress?: DesktopImportProgress | null
  notice?: string
  onCancel: () => void
  onConfirm: () => void
}) {
  const [excludedPage, setExcludedPage] = useState(preview.excludedPage)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [requestedPage, setRequestedPage] = useState(0)
  const active = useRef(true)
  const inFlight = useRef(false)
  const listRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])

  const loadPage = async (page: number) => {
    if (inFlight.current || importing) return
    inFlight.current = true
    setLoading(true)
    setError('')
    setRequestedPage(page)
    try {
      const result = await getDesktopSelectionExclusions(preview.selectionId, page)
      if (active.current) {
        setExcludedPage(result)
        if (listRef.current) listRef.current.scrollTop = 0
      }
    } catch {
      if (active.current) setError('제외 목록을 불러올 수 없습니다. 다시 시도하거나 문서·폴더를 다시 선택해 주세요.')
    } finally {
      inFlight.current = false
      if (active.current) setLoading(false)
    }
  }

  const exclusionItems = [
    ['지원하지 않는 형식', preview.exclusions.unsupportedFormat],
    ['빈 파일', preview.exclusions.emptyFile],
    ['크기 제한 초과', preview.exclusions.tooLarge],
    ['숨김·시스템·임시 항목', preview.exclusions.hiddenSystemOrTemporary],
    ['링크·연결 폴더', preview.exclusions.reparsePoint],
    ['접근할 수 없음', preview.exclusions.inaccessible],
    ['탐색 깊이 초과', preview.exclusions.depthExceeded],
  ].filter((item): item is [string, number] => Number(item[1]) > 0)
  const omittedCount = preview.excludedCount - excludedPage.totalElements
  const completedCount = progress?.completedCount ?? 0
  const requestedCount = progress?.requestedCount ?? preview.candidateCount
  const progressPercent = requestedCount === 0 ? 0 : Math.min(100, Math.round(completedCount / requestedCount * 100))

  return (
    <div className="dialog-backdrop" role="presentation">
      <section className="selection-dialog" role="dialog" aria-modal="true" aria-labelledby="selection-dialog-title">
        <div className="selection-dialog-heading">
          <div><p className="eyebrow">처리 전 확인</p><h2 id="selection-dialog-title">{preview.mode === 'FOLDER' ? '폴더 문서 추가' : '선택한 문서 추가'}</h2></div>
          {preview.rootName && <span className="selection-root" title={preview.rootName}>{preview.rootName}</span>}
        </div>
        <div className="selection-dialog-body">
          <div className="selection-summary">
            <div><strong>{preview.candidateCount.toLocaleString()}</strong><span>등록할 문서</span></div>
            <div><strong>{formatBytes(preview.totalBytes)}</strong><span>전체 크기</span></div>
            <div><strong>{preview.excludedCount.toLocaleString()}</strong><span>제외 항목</span></div>
          </div>
          {importing && (
            <section className="document-import-progress" aria-live="polite" aria-label="문서 등록 진행 상황">
              <div className="document-import-stage active">
                <div><strong>1단계 · 파일 등록</strong><span>{completedCount.toLocaleString()} / {requestedCount.toLocaleString()}</span></div>
                <div className="progress-track" role="progressbar" aria-label="파일 등록 진행률" aria-valuemin={0} aria-valuemax={requestedCount} aria-valuenow={completedCount}>
                  <i style={{ width: `${progressPercent}%` }} />
                </div>
                <p>처리를 위해 원본 사본을 임시 저장하고 중복 여부와 문서 정보를 확인하고 있습니다.</p>
              </div>
              <div className="document-import-stage queued">
                <div><strong>2단계 · 내용 처리</strong><span>백그라운드 진행</span></div>
                <p>등록된 문서부터 내용 분석·청크 분할·임베딩을 시작하며, 완료되면 PrivateKB 내부의 원본 사본을 자동 삭제합니다.</p>
              </div>
            </section>
          )}
          {notice && <div className="document-import-alert" role="alert">{notice}</div>}
          {preview.entries.length > 0 ? (
            <div className="selection-list" role="region" aria-label="등록할 문서 일부" tabIndex={0}>
              {preview.entries.map((entry, index) => <div key={index}><span title={entry.displayName}>{entry.displayName}</span><small>{formatBytes(entry.byteSize)}</small></div>)}
              {preview.candidateCount > preview.entries.length && <p>외 {preview.candidateCount - preview.entries.length}개 문서</p>}
            </div>
          ) : <p className="selection-empty">처리할 수 있는 문서가 없습니다.</p>}
          {preview.excludedCount > 0 && (
            <section className="selection-exclusions" aria-label="제외된 파일과 폴더">
              <h3>제외된 항목 <span>{preview.excludedCount.toLocaleString()}개</span></h3>
              <div className="exclusion-reasons">{exclusionItems.map(([label, count]) => <span key={label}>{label} {count}개</span>)}</div>
              <div ref={listRef} className="excluded-entry-list" role="region" aria-label="제외 항목 목록" aria-busy={loading} tabIndex={0}>
                {excludedPage.entries.map((entry, index) => (
                  <div className="excluded-entry" key={`${excludedPage.page}-${index}`}>
                    {entry.kind === 'FOLDER' ? <FolderIcon width={17} height={17} /> : <DocumentIcon width={17} height={17} />}
                    <div className="excluded-entry-copy"><strong title={entry.displayName}>{entry.displayName}</strong><small>{entry.kind === 'FOLDER' ? '폴더 · 하위 항목은 탐색하지 않음' : entry.kind === 'UNKNOWN' ? '항목 정보 확인 불가' : '파일'}</small></div>
                    <span className="excluded-entry-reason">{reasonLabels[entry.reason] ?? '확인할 수 없음'}</span>
                  </div>
                ))}
              </div>
              {preview.exclusions.tooLarge > 0 && <p className="exclusion-note">파일당 최대 {formatBytes(preview.maxFileBytes)} ({preview.maxFileBytes.toLocaleString()}바이트)까지 추가할 수 있습니다.</p>}
              <p className="exclusion-note">제외 개수는 확인된 파일·폴더 기준이며, 제외된 폴더 안의 파일 수는 포함하지 않습니다.</p>
              {omittedCount > 0 && <p className="exclusion-note">목록은 탐색 순서대로 최대 {excludedPage.totalElements.toLocaleString()}개까지 보관합니다. 나머지 {omittedCount.toLocaleString()}개도 위 사유별 개수에 포함됩니다.</p>}
              {excludedPage.totalElements > excludedPage.pageSize && (
                <div className="exclusion-pagination" aria-label="제외 목록 페이지">
                  <button type="button" aria-label="제외 목록 이전 페이지" disabled={loading || importing || excludedPage.page === 0} onClick={() => void loadPage(excludedPage.page - 1)}>이전</button>
                  <span>{excludedPage.page + 1} / {Math.ceil(excludedPage.totalElements / excludedPage.pageSize)}</span>
                  <button type="button" aria-label="제외 목록 다음 페이지" disabled={loading || importing || !excludedPage.hasNext} onClick={() => void loadPage(excludedPage.page + 1)}>다음</button>
                </div>
              )}
              {loading && <p className="exclusion-note" role="status"><RefreshIcon width={13} height={13} className="spin" /> 목록을 불러오고 있습니다.</p>}
              {error && <div className="exclusion-load-error" role="alert">{error}<button type="button" disabled={loading || importing} onClick={() => void loadPage(requestedPage)}>다시 시도</button></div>}
            </section>
          )}
          <p className="selection-guidance">같은 내용의 문서는 다시 임베딩하지 않고 원본 위치만 연결합니다. 처리용 사본은 임베딩 완료 후 자동 삭제하며 사용자의 원본 파일은 변경하지 않습니다. 제외된 항목은 업로드하지 않습니다.</p>
        </div>
        <div className="selection-dialog-actions">
          <button type="button" className="secondary-button" onClick={onCancel} disabled={importing}>취소</button>
          <button type="button" className="primary-button" onClick={onConfirm} disabled={importing || preview.candidateCount === 0}>{importing ? `등록 중 ${completedCount.toLocaleString()} / ${requestedCount.toLocaleString()}` : `${preview.candidateCount}개 문서 추가`}</button>
        </div>
      </section>
    </div>
  )
}
