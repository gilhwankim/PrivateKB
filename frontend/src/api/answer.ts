import type { AnswerStreamCallbacks, GroundedCitation } from '../types/answer'
import { apiUrl } from './base'

const DEFAULT_WORKSPACE_ID = '00000000-0000-0000-0000-000000000001'

export async function streamGroundedAnswer(
  question: string,
  callbacks: AnswerStreamCallbacks,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(apiUrl(`/api/workspaces/${DEFAULT_WORKSPACE_ID}/answers/stream`), {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ question }),
    signal,
  })

  if (!response.ok) {
    throw new Error((await readProblem(response)) ?? '근거 기반 답변을 시작할 수 없습니다.')
  }
  if (!response.body) {
    throw new Error('답변 스트림을 읽을 수 없습니다.')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value, { stream: !done }).replaceAll('\r\n', '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      completed = dispatchEvent(buffer.slice(0, boundary), callbacks) || completed
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }

  if (buffer.trim()) {
    completed = dispatchEvent(buffer, callbacks) || completed
  }
  if (!completed && !signal.aborted) {
    throw new Error('답변 스트림이 예기치 않게 종료되었습니다.')
  }
}

function dispatchEvent(frame: string, callbacks: AnswerStreamCallbacks): boolean {
  let eventName = 'message'
  const dataLines: string[] = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (dataLines.length === 0) return false

  const data = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
  if (eventName === 'citations' && Array.isArray(data.citations)) {
    callbacks.onCitations(data.citations as GroundedCitation[])
  } else if (eventName === 'token' && typeof data.text === 'string') {
    callbacks.onToken(data.text)
  } else if (eventName === 'refusal' && typeof data.reason === 'string') {
    callbacks.onRefusal(data.reason)
  } else if (eventName === 'error') {
    throw new Error(typeof data.message === 'string' ? data.message : '답변을 완료할 수 없습니다.')
  }
  return eventName === 'complete'
}

async function readProblem(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { detail?: unknown }
    return typeof body.detail === 'string' ? body.detail : null
  } catch {
    return null
  }
}
