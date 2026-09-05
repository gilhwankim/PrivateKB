export type OllamaConnectionStatus =
  | 'CHECKING'
  | 'CONNECTED'
  | 'NOT_RUNNING'
  | 'INCOMPATIBLE'
  | 'CHECK_FAILED'

export type LocalAiCheckPhase =
  | 'WAITING'
  | 'CHECKING'
  | 'COMPLETE'
  | 'ERROR'

export type LocalAiModelStatus =
  | 'CHECKING'
  | 'DISABLED'
  | 'NOT_INSTALLED'
  | 'READY'
  | 'INCOMPATIBLE'
  | 'ERROR'

export interface LocalAiStatus {
  ollama: {
    status: OllamaConnectionStatus
    version: string | null
    errorCode: string | null
  }
  embedding: ModelStatus
  chat: ModelStatus
  chatProfile: ChatProfileDetails
  capabilities: {
    documentManagement: boolean
    semanticSearch: boolean
    groundedAnswer: boolean
  }
  lastCheckedAt: string | null
  checkInProgress: boolean
}

export interface ModelStatus {
  model: string | null
  status: LocalAiModelStatus
  digest: string | null
  sizeBytes: number
  errorCode: string | null
}

export type ChatModelProfile = 'DISABLED' | 'LOW_SPEC' | 'STANDARD' | 'HIGH_SPEC'

export interface ChatProfileDetails {
  profile: ChatModelProfile
  displayName: string
  model: string | null
  estimatedDownloadBytes: number
  contextLength: number
  maximumGeneratedTokens: number
  maximumSourceDocuments: number
  maximumUploadBytes?: number
}

export type ModelInstallRole = 'EMBEDDING' | 'CHAT'

export type ModelInstallStatus =
  | 'QUEUED'
  | 'DOWNLOADING'
  | 'VERIFYING'
  | 'CANCEL_REQUESTED'
  | 'CANCELLED'
  | 'COMPLETED'
  | 'FAILED'

export interface ModelInstallJob {
  jobId: string
  role: ModelInstallRole
  model: string
  status: ModelInstallStatus
  progressPercent: number
  completedBytes: number
  totalBytes: number
  errorCode: string | null
  createdAt: string
  updatedAt: string
}
