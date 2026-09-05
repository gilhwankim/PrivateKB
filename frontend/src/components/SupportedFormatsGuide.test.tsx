import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { DOCUMENT_ACCEPT } from '../utils/documentFormats'
import { SupportedFormatsGuide } from './SupportedFormatsGuide'

afterEach(cleanup)

describe('문서 지원 형식 안내', () => {
  it('기본적으로 한 줄만 표시하고 상세를 열면 실제 지원하는 11개 확장자와 제한을 보여준다', async () => {
    const user = userEvent.setup()
    render(<SupportedFormatsGuide />)
    const toggle = screen.getByRole('button', { name: '지원 형식 보기' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('region', { name: '지원 형식 및 제한' })).not.toBeInTheDocument()
    await user.click(toggle)
    const panel = screen.getByRole('region', { name: '지원 형식 및 제한' })
    expect(toggle).toHaveAttribute('aria-controls', panel.id)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(within(panel).getAllByRole('listitem')).toHaveLength(7)
    for (const extension of DOCUMENT_ACCEPT.split(',')) expect(panel).toHaveTextContent(extension)
    expect(panel).toHaveTextContent('HWP 5.x')
    expect(panel).toHaveTextContent('HWPX 미지원')
    expect(panel).toHaveTextContent('파일당 최대 25.0MB')
    expect(within(panel).getByTitle('25,000,000바이트')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '지원 형식 접기' }))
    expect(panel).not.toBeVisible()
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('키보드 Enter와 Space로 상세 안내를 펼치고 접는다', async () => {
    const user = userEvent.setup()
    render(<SupportedFormatsGuide />)
    await user.tab()
    expect(screen.getByRole('button', { name: '지원 형식 보기' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('region', { name: '지원 형식 및 제한' })).toBeVisible()
    await user.keyboard(' ')
    expect(screen.queryByRole('region', { name: '지원 형식 및 제한' })).not.toBeInTheDocument()
  })
})
