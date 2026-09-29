import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Alert, type Page, type Severity, streamAlerts } from '../api'
import { alertDetail, relTime, ruleLabel } from '../format'
import { SeverityBadge } from './SeverityBadge'

interface StreamedAlert {
  dedupKey: string
  vin: string
  vehicleId: number
  rule: string
  severity: Severity
  openedAt: string
  details: Record<string, unknown>
}

interface FeedItem {
  key: string
  vin: string
  vehicleId: number
  rule: string
  severity: Severity
  openedAt: string
  details: Record<string, unknown>
  fresh: boolean
}

const fromApi = (a: Alert): FeedItem => ({
  key: `db:${a.id}`, vin: a.vin, vehicleId: a.vehicle_id, rule: a.rule_code, severity: a.severity,
  openedAt: a.opened_at, details: a.details, fresh: false,
})

/**
 * Open alerts, newest first. New alerts arrive over the live stream; while the stream is
 * down the list is refreshed by polling, so it never silently goes stale.
 */
export function AlertFeed() {
  const qc = useQueryClient()
  const [connected, setConnected] = useState(false)
  const [live, setLive] = useState<FeedItem[]>([])
  const [, tick] = useState(0)

  const recent = useQuery({
    queryKey: ['alerts', 'feed'],
    queryFn: () => api<Page<Alert>>('/v1/alerts?status=OPEN&limit=30'),
    refetchInterval: connected ? false : 10_000,
  })

  useEffect(() => {
    const ctrl = new AbortController()
    void streamAlerts((m) => {
      if (m.event !== 'alert') return
      const a = JSON.parse(m.data) as StreamedAlert
      setLive((prev) => [{ key: a.dedupKey, vin: a.vin, vehicleId: a.vehicleId, rule: a.rule, severity: a.severity,
        openedAt: a.openedAt, details: a.details, fresh: true }, ...prev].slice(0, 50))
      void qc.invalidateQueries({ queryKey: ['summary'] })
    }, setConnected, ctrl.signal)
    return () => ctrl.abort()
  }, [qc])

  // Keep the relative times current.
  useEffect(() => {
    const t = setInterval(() => tick((n) => n + 1), 15_000)
    return () => clearInterval(t)
  }, [])

  const seen = new Set(live.map((i) => `${i.vin}:${i.rule}:${i.openedAt}`))
  const items = [...live, ...(recent.data?.items ?? []).map(fromApi)
    .filter((i) => !seen.has(`${i.vin}:${i.rule}:${i.openedAt}`))].slice(0, 50)

  return (
    <div className="card">
      <div className="card-head">
        <h2>Open alerts</h2>
        <span className="muted" style={{ fontSize: 12.5, display: 'inline-flex', alignItems: 'center', gap: 6 }}
              title={connected ? 'Receiving alerts as they happen' : 'Reconnecting; refreshing every 10 s'}>
          <span className="dot-live" style={{ background: connected ? 'var(--status-good)' : 'var(--text-muted)' }} />
          {connected ? 'Live' : 'Reconnecting'}
        </span>
      </div>
      {recent.isError && !items.length ? (
        <div className="error-box">Could not load alerts. Retrying…</div>
      ) : !items.length ? (
        <div className="empty">{recent.isLoading ? 'Loading…' : 'No open alerts.'}</div>
      ) : (
        <ul className="feed" aria-live="polite">
          {items.map((i) => (
            <li key={i.key} className={i.fresh ? 'fresh' : undefined}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'center' }}>
                <strong>{ruleLabel(i.rule)}</strong>
                <SeverityBadge severity={i.severity} />
              </div>
              <div className="secondary" style={{ fontSize: 12.5 }}>{alertDetail(i.rule, i.details)}</div>
              <div className="muted" style={{ fontSize: 12 }}>
                <Link to={`/vehicles/${i.vehicleId}`} className="mono">{i.vin}</Link> · {relTime(i.openedAt)}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
