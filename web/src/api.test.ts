import { describe, expect, it } from 'vitest'
import { parseSse, query } from './api'

describe('parseSse', () => {
  it('parses complete events and keeps a partial one for the next chunk', () => {
    const { messages, rest } = parseSse('event: alert\ndata: {"a":1}\n\nevent: alert\ndata: {"b"')
    expect(messages).toEqual([{ event: 'alert', data: '{"a":1}' }])
    expect(rest).toBe('event: alert\ndata: {"b"')
    const next = parseSse(rest + ':2}\n\n')
    expect(next.messages).toEqual([{ event: 'alert', data: '{"b":2}' }])
    expect(next.rest).toBe('')
  })

  it('ignores comments and keep-alives, handles CRLF and multi-line data', () => {
    const { messages } = parseSse(': connected\r\n\r\n: keep-alive\n\ndata: line1\ndata: line2\n\n')
    expect(messages).toEqual([{ event: 'message', data: 'line1\nline2' }])
  })
})

describe('query', () => {
  it('drops empty values and encodes the rest', () => {
    expect(query({ status: 'OPEN', severity: '', rule: null, cursor: undefined, limit: 50 })).toBe('?status=OPEN&limit=50')
    expect(query({ vin_prefix: 'A B' })).toBe('?vin_prefix=A+B')
    expect(query({})).toBe('')
  })
})
