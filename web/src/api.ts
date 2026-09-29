import { accessToken } from './auth'

// ---------------------------------------------------------------------------- types

export type Severity = 'INFO' | 'WARNING' | 'CRITICAL'
export type AlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED'
export type LiveStatus = 'DRIVING' | 'IDLING' | 'OFF'

export interface Page<T> {
  items: T[]
  next_cursor: string | null
}

export interface Me {
  user_id: string
  username: string
  email: string | null
  tenant_id: string
  tenant_name: string | null
  roles: string[]
}

export interface Summary {
  vehicles: number
  reporting: number
  live_status: Partial<Record<LiveStatus, number>>
  open_alerts: Partial<Record<Severity, number>>
}

export interface Fleet {
  id: number
  name: string
  home_depot: string | null
  vehicles: number
}

export interface Vehicle {
  id: number
  vin: string
  model_year: number
  status: 'ACTIVE' | 'IN_SERVICE' | 'RETIRED'
  fleet_id: number
  fleet_name: string
  model: string
  powertrain: 'ICE' | 'HYBRID' | 'EV'
  oem: string
}

export interface LiveState {
  ts: string
  status: LiveStatus
  lat: number
  lon: number
  speed_kmh: number | null
  odo_km: number | null
  fuel_pct: number | null
  soc_pct: number | null
  coolant_c: number | null
  batt_v: number | null
  dtc: string[]
  evt: string | null
}

export interface Alert {
  id: number
  vehicle_id: number
  vin: string
  rule_code: string
  severity: Severity
  status: AlertStatus
  opened_at: string
  detected_at: string
  acknowledged_by: string | null
  resolved_at: string | null
  details: Record<string, unknown>
}

export interface Risk {
  scored_at: string
  model_version: string
  failure_prob_7d: number
  predicted_component: string | null
  est_cost_avoided_usd: number | null
  top_factors: { feature: string; contribution: number }[]
}

export interface VehicleDetail extends Vehicle {
  registered_at: string
  live: LiveState | null
  open_alerts: Omit<Alert, 'vehicle_id' | 'vin' | 'detected_at' | 'acknowledged_by' | 'resolved_at'>[]
  risk: Risk | null
}

/** Column-oriented rows: fields ["vin","lat","lon","status","speed_kmh","ts"]. */
export interface LiveMap {
  fields: string[]
  rows: [string, number, number, LiveStatus, number | null, number | null][]
  truncated: boolean
}

export interface DailyPoint {
  ts: string
  samples: number
  km: number
  coolant_avg_c: number | null
  coolant_max_c: number | null
  batt_v_min: number
  soh_min_pct: number | null
  dtc_events: number
  dtc_codes: string[]
  harsh_events: number
  idle_samples: number
}

export interface MinutePoint {
  ts: string
  samples: number
  avg_speed_kmh: number
  max_coolant_c: number | null
  min_batt_v: number
  lat: number
  lon: number
}

export interface Telemetry<T> {
  vehicle_id: number
  resolution: 'raw' | '1m' | '1d'
  start: string
  end: string
  items: T[]
  truncated: boolean
}

// ---------------------------------------------------------------------------- client

/** An RFC 9457 problem returned by the API. */
export class ApiError extends Error {
  readonly status: number
  readonly retryAfter: number | null

  constructor(status: number, message: string, retryAfter: number | null) {
    super(message)
    this.status = status
    this.retryAfter = retryAfter
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = await accessToken()
  const resp = await fetch(`/api${path}`, {
    ...init,
    headers: { ...init.headers, Authorization: `Bearer ${token}` },
  })
  if (!resp.ok) {
    let message = resp.statusText
    try {
      const problem = await resp.json()
      message = problem.detail ?? problem.title ?? message
    } catch {
      // not JSON: keep the status text
    }
    const retry = resp.headers.get('Retry-After')
    throw new ApiError(resp.status, message, retry ? Number(retry) : null)
  }
  return resp.json() as Promise<T>
}

export function query(params: Record<string, string | number | null | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) if (v !== null && v !== undefined && v !== '') q.set(k, String(v))
  const s = q.toString()
  return s ? `?${s}` : ''
}

// ---------------------------------------------------------------------------- server-sent events

export interface SseMessage {
  event: string
  data: string
}

/**
 * Parses a text/event-stream incrementally. Returns the complete messages in `chunk` and
 * keeps any partial one in the returned `rest` for the next chunk.
 */
export function parseSse(buffer: string): { messages: SseMessage[]; rest: string } {
  const messages: SseMessage[] = []
  const blocks = buffer.replace(/\r\n/g, '\n').split('\n\n')
  const rest = blocks.pop() ?? ''
  for (const block of blocks) {
    let event = 'message'
    const data: string[] = []
    for (const line of block.split('\n')) {
      if (line.startsWith(':')) continue // comment / keep-alive
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'event') event = value
      else if (field === 'data') data.push(value)
    }
    if (data.length) messages.push({ event, data: data.join('\n') })
  }
  return { messages, rest }
}

/**
 * Subscribes to the tenant's alert stream. EventSource cannot send an Authorization header,
 * so this reads the stream with fetch, and reconnects with backoff (and a fresh token) when
 * the connection drops. Stops when `signal` aborts.
 */
export async function streamAlerts(onMessage: (m: SseMessage) => void, onState: (connected: boolean) => void,
                                   signal: AbortSignal): Promise<void> {
  let backoff = 1000
  while (!signal.aborted) {
    try {
      const resp = await fetch('/api/v1/alerts/stream', {
        headers: { Authorization: `Bearer ${await accessToken()}`, Accept: 'text/event-stream' },
        signal,
      })
      if (!resp.ok || !resp.body) throw new Error(`stream failed: ${resp.status}`)
      onState(true)
      backoff = 1000
      const reader = resp.body.pipeThrough(new TextDecoderStream()).getReader()
      let buffer = ''
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        const parsed = parseSse(buffer + value)
        buffer = parsed.rest
        parsed.messages.forEach(onMessage)
      }
    } catch {
      if (signal.aborted) return
    }
    onState(false)
    await new Promise((r) => setTimeout(r, backoff))
    backoff = Math.min(backoff * 2, 30_000)
  }
}
