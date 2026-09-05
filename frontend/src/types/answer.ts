export interface GroundedCitation {
  sourceNumber: number
  chunkId: string
  documentId: string
  documentVersionId: string
  originalFilename: string
  versionNumber: number
  uploadedAt: string
  chunkIndex: number
  startOffset: number
  endOffset: number
  sourceFolderPath?: string | null
  excerpt: string
  score: number
}

export interface AnswerStreamCallbacks {
  onCitations: (citations: GroundedCitation[]) => void
  onToken: (text: string) => void
  onRefusal: (reason: string) => void
}
