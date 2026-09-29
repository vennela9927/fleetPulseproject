import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError, type Alert, type AlertStatus, type Page, query, type Severity } from '../api'
import { hasRole } from '../auth'
import { SeverityBadge } from '../components/SeverityBadge'
import { alertDetail, dateTime, relTime, ruleLabel } from '../format'

const RULES = ['ENGINE_OVERHEAT', 'CRITICAL_DTC', 'LOW_12V_BATTERY', 'HARSH_DRIVING', 'EV_LOW_SOC', 'EXCESSIVE_IDLING']

export function Alerts() {
  const qc = useQueryClient()
  const manager = hasRole('fleet_manager')
  const [status, setStatus] = useState<AlertStatus | ''>('OPEN')
  const [severity, setSeverity] = useState<Severity | ''>('')
  const [rule, setRule] = useState('')
  const [toast, setToast] = useState<string | null>(null)

  const alerts = useInfiniteQuery({
    queryKey: ['alerts', 'list', status, severity, rule],
    queryFn: ({ pageParam }) =>
      api<Page<Alert>>(`/v1/alerts${query({ status, severity, rule, limit: 50, cursor: pageParam })}`),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor,
    refetchInterval: 15_000,
  })

  const act = useMutation({
    mutationFn: ({ id, action }: { id: number; action: 'acknowledge' | 'resolve' }) =>
      api<unknown>(`/v1/alerts/${id}/${action}`, { method: 'POST' }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['alerts'] }),
    onError: (e) => {
      setToast(e instanceof ApiError && e.status === 409
        ? 'Someone else already changed this alert. The list has been refreshed.'
        : `Could not update the alert: ${e.message}`)
      void qc.invalidateQueries({ queryKey: ['alerts'] })
      setTimeout(() => setToast(null), 5000)
    },
  })

  const rows = alerts.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <div className="page-head">
        <h1>Alerts</h1>
        {!manager && <span className="muted">Read-only: acknowledging needs the fleet manager role</span>}
      </div>
      <div className="filters">
        <select value={status} onChange={(e) => setStatus(e.target.value as AlertStatus | '')} aria-label="Status">
          <option value="">Any status</option>
          <option value="OPEN">Open</option>
          <option value="ACKNOWLEDGED">Acknowledged</option>
          <option value="RESOLVED">Resolved</option>
        </select>
        <select value={severity} onChange={(e) => setSeverity(e.target.value as Severity | '')} aria-label="Severity">
          <option value="">Any severity</option>
          <option value="CRITICAL">Critical</option>
          <option value="WARNING">Warning</option>
          <option value="INFO">Info</option>
        </select>
        <select value={rule} onChange={(e) => setRule(e.target.value)} aria-label="Rule">
          <option value="">Any rule</option>
          {RULES.map((r) => <option key={r} value={r}>{ruleLabel(r)}</option>)}
        </select>
      </div>
      <div className="card">
        {alerts.isError ? (
          <div className="error-box">Could not load alerts: {(alerts.error as Error).message}</div>
        ) : !rows.length ? (
          <div className="empty">{alerts.isLoading ? 'Loading…' : 'No alerts match these filters.'}</div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Severity</th><th>Alert</th><th>Vehicle</th><th>Opened</th><th>Status</th><th /></tr>
              </thead>
              <tbody>
                {rows.map((a) => (
                  <tr key={a.id}>
                    <td><SeverityBadge severity={a.severity} /></td>
                    <td>
                      <div style={{ fontWeight: 500 }}>{ruleLabel(a.rule_code)}</div>
                      <div className="muted" style={{ fontSize: 12.5 }}>{alertDetail(a.rule_code, a.details)}</div>
                    </td>
                    <td><Link to={`/vehicles/${a.vehicle_id}`} className="mono">{a.vin}</Link></td>
                    <td title={dateTime(a.opened_at)} className="num">{relTime(a.opened_at)}</td>
                    <td><span className="pill">{a.status.toLowerCase()}</span></td>
                    <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                      {manager && a.status === 'OPEN' && (
                        <button className="btn small" disabled={act.isPending}
                                onClick={() => act.mutate({ id: a.id, action: 'acknowledge' })}>Acknowledge</button>
                      )}{' '}
                      {manager && a.status !== 'RESOLVED' && (
                        <button className="btn small" disabled={act.isPending}
                                onClick={() => act.mutate({ id: a.id, action: 'resolve' })}>Resolve</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {alerts.hasNextPage && (
        <div style={{ textAlign: 'center', marginTop: 12 }}>
          <button className="btn" onClick={() => void alerts.fetchNextPage()} disabled={alerts.isFetchingNextPage}>
            {alerts.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  )
}
