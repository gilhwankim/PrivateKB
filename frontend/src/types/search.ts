export interface SearchResult {
  chunkId: string
  documentId: string
  documentVersionId: string
  originalFilename: string
  versionNumber: number
  uploadedAt: string
  chunkIndex: number
  startOffset: number
  endOffset: number
  content: string
  score: number
}

export interface SearchResponse {
  results: SearchResult[]
}
