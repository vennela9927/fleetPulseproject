import { describe, expect, it } from 'vitest'
import { alertDetail, count, relTime, ruleLabel } from './format'

describe('format', () => {
  it('counts exactly below 10,000 and compactly above', () => {
    expect(count(1284)).toBe('1,284')
    expect(count(12_900)).toBe('12.9K')
    expect(count(null)).toBe('–')
  })

  it('describes relative times', () => {
    const now = Date.parse('2026-09-29T12:00:00Z')
    expect(relTime('2026-09-29T11:59:58Z', now)).toBe('just now')
    expect(relTime('2026-09-29T11:58:00Z', now)).toBe('2m ago')
    expect(relTime('2026-09-29T09:00:00Z', now)).toBe('3h ago')
  })

  it('labels rules and explains alerts from their details', () => {
    expect(ruleLabel('ENGINE_OVERHEAT')).toBe('Engine overheat')
    expect(ruleLabel('SOMETHING_NEW')).toBe('something new')
    expect(alertDetail('ENGINE_OVERHEAT', { coolant_c: 122.3, above_for_seconds: 40 })).toBe('Coolant 122.3 °C for 40 s')
    expect(alertDetail('HARSH_DRIVING', { events: 5, window_seconds: 360 })).toBe('5 events in 6 min')
  })
})
