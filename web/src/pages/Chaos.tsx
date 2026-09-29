import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, query } from '../api'
import { hasRole } from '../auth'
import { count, relTime } from '../format'

interface Pipeline {
  started_at: string
  paused: boolean
  burst_factor: number
  burst_until: string
  sent: number
  stored: number
  in_flight: number
  per_oem: { oem: string; sent: number; stored: number }[]
  chaos_sent: { duplicates: number; invalid: number; out_of_order: number }
}

const COMPONENTS = [
  { key: 'COOLING', label: 'Cooling system (overheating)' },
  { key: 'ELECTRICAL', label: '12 V electrical (flat battery)' },
  { key: 'BRAKES', label: 'ABS / brakes (harsh braking)' },
  { key: 'IGNITION', label: 'Ignition (misfires)' },
  { key: 'EV_BATTERY', label: 'High-voltage battery (EVs)' },
  { key: 'TRANSMISSION', label: 'Transmission' },
]

export function Chaos() {
  const qc = useQueryClient()
  const manager = hasRole('fleet_manager')
  const [component, setComponent] = useState('COOLING')
  const [vehicles, setVehicles] = useState(10)
  const [injected, setInjected] = useState<{ component: string; vins: string[] } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const p = useQuery({
    queryKey: ['pipeline'],
    queryFn: () => api<Pipeline>('/v1/ops/pipeline'),
    refetchInterval: 2_000,
    retry: false,
  })
  const onError = (e: Error) => setError(e.message)
  const refresh = () => void qc.invalidateQueries({ queryKey: ['pipeline'] })

  const inject = useMutation({
    mutationFn: () => api<{ component: string; vins: string[] }>('/v1/ops/inject', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ component, count: vehicles, minutes: 3 }),
    }),
    onSuccess: (r) => { setInjected(r); setError(null) },
    onError,
  })
  const burst = useMutation({
    mutationFn: () => api<unknown>(`/v1/ops/burst${query({ factor: 3, seconds: 120 })}`, { method: 'POST' }),
    onSuccess: refresh, onError,
  })
  const pause = useMutation({
    mutationFn: (paused: boolean) => api<unknown>(`/v1/ops/pause${query({ paused: String(paused) })}`, { method: 'POST' }),
    onSuccess: refresh, onError,
  })

  const d = p.data
  const settled = d && d.paused && d.in_flight === 0 && d.sent > 0
  const bursting = d && d.burst_factor > 1 && Date.parse(d.burst_until) > p.dataUpdatedAt

  return (
    <>
      <div className="page-head">
        <h1>Chaos and reconciliation</h1>
        <span className="muted">Demo controls act on your fleet's simulated vehicles only</span>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-head">
          <h2>Events sent vs. stored</h2>
          <span className="muted" style={{ fontSize: 12.5 }}>
            {d ? `This run started ${relTime(d.started_at)} · refreshes every 2 s` : ' '}
          </span>
        </div>
        {p.isError ? (
          <div className="error-box">
            {p.error instanceof ApiError && p.error.status === 503
              ? 'The vehicle simulator is not streaming. Start it in live mode to reconcile events.'
              : `Could not load pipeline counts: ${(p.error as Error).message}`}
          </div>
        ) : !d ? <div className="empty">Loading…</div> : (
          <div className="card-body">
            <div className="tiles" style={{ marginBottom: 12 }}>
              <div className="tile"><div className="label">Sent by vehicles</div><div className="value num">{count(d.sent)}</div>
                <div className="sub">valid, unique events</div></div>
              <div className="tile"><div className="label">Stored</div><div className="value num">{count(d.stored)}</div>
                <div className="sub">in the telemetry store</div></div>
              <div className="tile"><div className="label">In flight</div><div className="value num">{count(d.in_flight)}</div>
                <div className="sub">{settled ? 'nothing lost, nothing duplicated' : d.paused ? 'settling…' : 'normal while traffic flows'}</div></div>
            </div>
            <p style={{ margin: '0 0 12px', display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="dot-live" style={{ background: settled ? 'var(--status-good)' : 'var(--text-muted)' }} />
              {settled ? <strong>Exact match: every event sent was stored exactly once.</strong>
                : d.paused ? 'Traffic paused. Waiting for the last events (including deliberately delayed ones) to arrive.'
                  : 'Traffic is flowing, so stored trails sent by the events still in the pipeline. Pause to reconcile exactly.'}
            </p>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Vehicle maker</th><th style={{ textAlign: 'right' }}>Sent</th><th style={{ textAlign: 'right' }}>Stored</th><th style={{ textAlign: 'right' }}>Difference</th></tr></thead>
                <tbody>
                  {d.per_oem.map((o) => (
                    <tr key={o.oem}>
                      <td>{o.oem.charAt(0) + o.oem.slice(1).toLowerCase()}</td>
                      <td className="num" style={{ textAlign: 'right' }}>{o.sent.toLocaleString()}</td>
                      <td className="num" style={{ textAlign: 'right' }}>{o.stored.toLocaleString()}</td>
                      <td className="num" style={{ textAlign: 'right' }}>{(o.sent - o.stored).toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="muted" style={{ margin: '12px 0 0', fontSize: 12.5 }}>
              Also sent on purpose, and correctly not stored: {count(d.chaos_sent.duplicates)} duplicates,
              {' '}{count(d.chaos_sent.invalid)} corrupt records. {count(d.chaos_sent.out_of_order)} events were delivered out of order.
            </p>
          </div>
        )}
      </div>

      <div className="grid-charts">
        <div className="card">
          <div className="card-head"><h2>Make vehicles break down</h2></div>
          <div className="card-body">
            <p className="secondary" style={{ marginTop: 0 }}>A fault develops over 3 minutes; alerts should follow within seconds of each threshold being crossed.</p>
            <div className="filters">
              <select value={component} onChange={(e) => setComponent(e.target.value)} aria-label="Fault">
                {COMPONENTS.map((c) => <option key={c.key} value={c.key}>{c.label}</option>)}
              </select>
              <input type="number" min={1} max={50} value={vehicles} aria-label="Vehicles"
                     onChange={(e) => setVehicles(Math.max(1, Math.min(50, Number(e.target.value) || 1)))} style={{ width: 80 }} />
              <span className="muted">vehicles</span>
              <button className="btn primary" disabled={!manager || inject.isPending} onClick={() => inject.mutate()}>Inject fault</button>
            </div>
            {injected && (
              <div className="secondary" style={{ fontSize: 12.5 }}>
                Injected into {injected.vins.length} vehicles:{' '}
                {injected.vins.slice(0, 8).map((v, i) => (
                  <span key={v}>{i > 0 && ', '}<Link to={`/vehicles/by-vin/${v}`} className="mono">{v}</Link></span>
                ))}{injected.vins.length > 8 && ` and ${injected.vins.length - 8} more`}
              </div>
            )}
          </div>
        </div>

        <div className="card">
          <div className="card-head"><h2>Stress the pipeline</h2></div>
          <div className="card-body">
            <div className="filters" style={{ marginBottom: 8 }}>
              <button className="btn" disabled={!manager || burst.isPending || !!bursting} onClick={() => burst.mutate()}>
                {bursting ? `Traffic spike x${d?.burst_factor} running` : 'Traffic spike x3 for 2 min'}
              </button>
              <button className="btn" disabled={!manager || pause.isPending || !d} onClick={() => pause.mutate(!d?.paused)}>
                {d?.paused ? 'Resume traffic' : 'Pause traffic'}
              </button>
            </div>
            <p className="secondary" style={{ margin: '8px 0 6px' }}>To take a Kafka broker down and bring it back, run this in a terminal:</p>
            <pre className="mono" style={{ margin: 0, padding: 10, background: 'var(--hover)', borderRadius: 8, overflowX: 'auto' }}>
{`docker compose stop kafka
# ...watch "In flight" grow while events queue upstream
docker compose start kafka`}
            </pre>
          </div>
        </div>
      </div>
      {!manager && <p className="muted">Controls need the fleet manager role.</p>}
      {error && <div className="toast" role="alert" onClick={() => setError(null)}>{error}</div>}
    </>
  )
}
