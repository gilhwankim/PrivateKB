import { useId, useState } from 'react'
import { DOCUMENT_FORMATS, DOCUMENT_MAX_FILE_BYTES } from '../utils/documentFormats'
import { formatBytes } from '../utils/formatBytes'
import { ChevronIcon, DocumentIcon } from './icons'

export function SupportedFormatsGuide({ maxFileBytes = DOCUMENT_MAX_FILE_BYTES }: { maxFileBytes?: number }) {
  const [expanded, setExpanded] = useState(false)
  const panelId = useId()

  return (
    <div className="supported-formats">
      <div className="supported-formats-summary">
        <span>PDF · Word · Excel · PPT · HWP 5.x · TXT · Markdown</span>
        <button
          type="button"
          aria-expanded={expanded}
          aria-controls={panelId}
          onClick={() => setExpanded(value => !value)}
        >
          지원 형식 {expanded ? '접기' : '보기'}
          <ChevronIcon width={14} height={14} />
        </button>
      </div>
      <section id={panelId} className="supported-formats-panel" aria-label="지원 형식 및 제한" hidden={!expanded}>
        <h3>지원 형식 및 제한</h3>
        <ul className="supported-formats-list">
          {DOCUMENT_FORMATS.map(format => (
            <li key={format.name}>
              <DocumentIcon width={19} height={19} />
              <strong>{format.name}</strong>
              <span>{format.extensions.join(' · ')}{'note' in format ? ` (${format.note})` : ''}</span>
            </li>
          ))}
        </ul>
        <p className="supported-formats-note">
          <span title={`${maxFileBytes.toLocaleString('ko-KR')}바이트`}>파일당 최대 {formatBytes(maxFileBytes)}</span>
          <span>OCR 최대 20쪽 · 추출문 최대 500만 자</span>
          <span>HWPX 미지원</span>
          <span>Windows 프로그램에서는 추가 전 확인 창에서 제외된 파일과 사유를 볼 수 있습니다.</span>
        </p>
      </section>
    </div>
  )
}
