import type { SearchResponse } from '../types/search'
import { apiUrl } from './base'

const DEFAULT_WORKSPACE_ID = '00000000-0000-0000-0000-000000000001'

export async function searchKnowledge(query: string, limit = 5): Promise<SearchResponse> {
  const response = await fetch(apiUrl(`/api/workspaces/${DEFAULT_WORKSPACE_ID}/search`), {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ query, limit }),
  })

  if (response.ok) {
    return (await response.json()) as SearchResponse
  }

  const problem = await readProblem(response)
  throw new Error(problem ?? '문서를 검색할 수 없습니다.')
}

async function readProblem(response: Response): Promise<string | null> {
  try {
    const body = (await response.json()) as { detail?: unknown }
    return typeof body.detail === 'string' ? body.detail : null
  } catch {
    return null
  }
}
