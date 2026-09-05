import { apiUrl } from './base'
import type { DocumentPage } from '../types/documents'

const DEFAULT_WORKSPACE_ID = '00000000-0000-0000-0000-000000000001'

export async function getDocuments(
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<DocumentPage> {
  const parameters = new URLSearchParams({ page: String(page), size: String(size) })
  const response = await fetch(
    apiUrl(`/api/workspaces/${DEFAULT_WORKSPACE_ID}/documents?${parameters}`),
    { headers: { Accept: 'application/json' }, signal },
  )
  if (!response.ok) throw new Error('문서 목록을 불러올 수 없습니다.')
  return (await response.json()) as DocumentPage
}

export async function retryDocumentEmbedding(documentVersionId: string): Promise<void> {
  const response = await fetch(
    apiUrl(`/api/document-versions/${encodeURIComponent(documentVersionId)}/ingestion/retry`),
    { method: 'POST', headers: { Accept: 'application/json' } },
  )
  if (response.ok) return
  if (response.status === 409) throw new Error('이 문서는 더 이상 자동으로 재처리할 수 없습니다.')
  throw new Error('문서 재처리를 시작할 수 없습니다.')
}
