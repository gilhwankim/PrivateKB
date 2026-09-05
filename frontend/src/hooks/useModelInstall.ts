import { useEffect, useRef, useState } from 'react'
import { cancelModelInstall, getModelInstall, startModelInstall } from '../api/localAi'
import type { ModelInstallJob, ModelInstallRole } from '../types/localAi'

export const MODEL_INSTALL_POLL_INTERVAL_MS = 700

export function activeInstall(job: ModelInstallJob | null) {
  return Boolean(job && !['COMPLETED', 'CANCELLED', 'FAILED'].includes(job.status))
}

export function installStatusLabel(status: ModelInstallJob['status']) {
  return ({ QUEUED: '설치 대기 중', DOWNLOADING: '다운로드 중', VERIFYING: '설치 확인 중', CANCEL_REQUESTED: '취소 요청 중', CANCELLED: '설치가 취소됨', COMPLETED: '설치 완료', FAILED: '설치 실패' } as const)[status]
}

export type ModelInstallController = ReturnType<typeof useModelInstall>

// 앱에서 한 번만 생성한다. 메뉴·팝업의 마운트 여부와 설치 작업의 수명을 분리한다.
export function useModelInstall(onRefresh: () => void | Promise<void>) {
  const [job, setJob] = useState<ModelInstallJob | null>(null)
  const [startingRole, setStartingRole] = useState<ModelInstallRole | null>(null)
  const [tracking, setTracking] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [cancelling, setCancelling] = useState(false)
  const [error, setError] = useState('')
  const jobRef = useRef<ModelInstallJob | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const cancelRef = useRef<AbortController | null>(null)
  const refreshRef = useRef(onRefresh)

  useEffect(() => { refreshRef.current = onRefresh }, [onRefresh])
  useEffect(() => () => {
    controllerRef.current?.abort()
    cancelRef.current?.abort()
  }, [])

  const publish = (next: ModelInstallJob) => {
    const previous = jobRef.current
    // 취소 응답 뒤 도착한 오래된 조회 응답이 진행 상태를 되돌리지 않도록 한다.
    if (previous?.jobId === next.jobId) {
      if (!activeInstall(previous)) return previous
      if (previous.status === 'CANCEL_REQUESTED' && activeInstall(next)) {
        next = { ...next, status: 'CANCEL_REQUESTED' }
      }
    }
    jobRef.current = next
    setJob(next)
    return next
  }

  const track = async (first: ModelInstallJob, signal: AbortSignal) => {
    if (signal.aborted) return
    let current = publish(first)
    while (activeInstall(current)) {
      await waitForPoll(signal)
      const updated = await getModelInstall(current.jobId, signal)
      if (signal.aborted) return
      current = publish(updated)
    }
    if (current.status === 'COMPLETED') {
      setRefreshing(true)
      await refreshRef.current()
    } else if (current.status === 'FAILED') {
      setError(installErrorMessage(current.errorCode))
    }
  }

  const run = async (role?: ModelInstallRole) => {
    // React 재렌더 전 연속 클릭도 같은 동기 잠금으로 차단한다.
    if (controllerRef.current || cancelRef.current || (role && activeInstall(jobRef.current))) return
    const previous = jobRef.current
    if (!role && !activeInstall(previous)) return
    const controller = new AbortController()
    controllerRef.current = controller
    setTracking(true)
    setError('')
    if (role) {
      setStartingRole(role)
      jobRef.current = null
      setJob(null)
    }
    try {
      const current = role
        ? await startModelInstall(role, controller.signal)
        : await getModelInstall(previous!.jobId, controller.signal)
      if (controller.signal.aborted) return
      setStartingRole(null)
      await track(current, controller.signal)
    } catch (cause) {
      if (!controller.signal.aborted) {
        setError(activeInstall(jobRef.current)
          ? '설치 상태를 확인하지 못했습니다. 다운로드가 계속될 수 있으므로 상태를 다시 확인해 주세요.'
          : cause instanceof Error ? cause.message : '모델 설치 요청을 처리할 수 없습니다.')
      }
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null
        if (!controller.signal.aborted) {
          setStartingRole(null)
          setTracking(false)
          setRefreshing(false)
        }
      }
    }
  }

  const cancel = async () => {
    const current = jobRef.current
    if (!current || !activeInstall(current) || current.status === 'CANCEL_REQUESTED' || cancelRef.current) return
    const controller = new AbortController()
    cancelRef.current = controller
    setCancelling(true)
    try {
      const updated = await cancelModelInstall(current.jobId, controller.signal)
      if (!controller.signal.aborted && jobRef.current?.jobId === current.jobId) publish(updated)
    } catch (cause) {
      if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '설치 취소를 요청할 수 없습니다.')
    } finally {
      if (cancelRef.current === controller) {
        cancelRef.current = null
        if (!controller.signal.aborted) setCancelling(false)
      }
    }
  }

  return {
    job, startingRole, tracking, refreshing, cancelling, error,
    busy: tracking || cancelling || activeInstall(job),
    canRetry: activeInstall(job) && !tracking && !cancelling,
    start: (role: ModelInstallRole) => run(role),
    retry: () => run(),
    cancel,
  }
}

function installErrorMessage(code: string | null) {
  if (code === 'OLLAMA_NOT_RUNNING') return 'Ollama를 실행한 뒤 다시 시도해 주세요.'
  if (code === 'MODEL_INSTALL_VERIFY_FAILED') return '다운로드 후 정확한 모델을 확인하지 못했습니다.'
  return '모델 다운로드를 완료할 수 없습니다.'
}

function waitForPoll(signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) { reject(new DOMException('설치 상태 확인이 중단되었습니다.', 'AbortError')); return }
    const onAbort = () => {
      window.clearTimeout(timeout)
      reject(new DOMException('설치 상태 확인이 중단되었습니다.', 'AbortError'))
    }
    const timeout = window.setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, MODEL_INSTALL_POLL_INTERVAL_MS)
    signal.addEventListener('abort', onAbort, { once: true })
  })
}
