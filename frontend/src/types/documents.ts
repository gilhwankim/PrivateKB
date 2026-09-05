export type DocumentEmbeddingStatus =
  | 'PROCESSING'
  | 'WAITING_FOR_MODEL'
  | 'REINDEX_REQUIRED'
  | 'COMPLETED'
  | 'FAILED'

export type IngestionStatus =
  | 'RECEIVED'
  | 'STORED'
  | 'PARSING'
  | 'OCR_PENDING'
  | 'OCR_RUNNING'
  | 'PARSED'
  | 'FAILED'

export type IndexingStatus =
  | 'PENDING'
  | 'MODEL_WAITING'
  | 'REINDEX_REQUIRED'
  | 'INDEXING'
  | 'PAUSED'
  | 'INDEXED'
  | 'FAILED'
  | null

export interface DocumentStatusItem {
  documentId: string
  documentVersionId: string
  versionNumber: number
  originalFilename: string
  detectedMediaType: string
  byteSize: number
  status: DocumentEmbeddingStatus
  ingestionStatus: IngestionStatus
  indexingStatus: IndexingStatus
  errorCode: string | null
  createdAt: string
  updatedAt: string
}

export interface DocumentPage {
  documents: DocumentStatusItem[]
  totalElements: number
  page: number
  size: number
  hasNext: boolean
}
