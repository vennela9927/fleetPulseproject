import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { count, relTime } from '../format'
import { useInspectionCost } from '../inspectionCost'

interface Entry {
  vehicle_id: number
  vin: string
  fleet: string
  probability: number
  likely_part: string | null
  expected_value_usd: number
  depot?: string
  moved_from?: string | null
}

export interface MaintenanceOverview {
  scored: number
  scored_at?: string
  inspection_cost_usd?: number
  bands?: { urgent: number; worth_inspecting: number; no_action: number }
  expected_breakdowns_7d?: number
  plan?: { days: number; scheduled: number; urgent_scheduled: number; expected_net_saving_usd: number
    too_risky_to_wait: number; waiting_for_a_bay: number; value_waiting_usd: number }
  tomorrow?: { date: string; inspections: number; bays: number; used: number; depots_full: number; depots: number
    moved_to_nearby_depot: number }
  too_risky_to_wait?: Entry[]
  first_inspections?: Entry[]
}

const pct = (x: number) => `${Math.round(x * 100)}%`
const usd = (x: number) => `${x < 0 ? '−' : ''}$${count(Math.abs(Math.round(x)))}`

const BANDS = [
  { key: 'urgent', label: 'Inspect tomorrow', note: '80% risk or more', token: '--status-critical' },
  { key: 'worth_inspecting', label: 'Worth inspecting', note: 'risk × saving above the inspection cost', token: '--status-warning' },
  { key: 'no_action', label: 'No action', note: 'an inspection would cost more than it saves', token: '--status-good' },
] as const

function Action({ to, tone, children }: { to: string; tone?: 'critical' | 'warning'; children: React.ReactNode }) {
  return (
    <li style={{ display: 'flex', gap: 8, alignItems: 'baseline', padding: '5px 0' }}>
      <span className="swatch" aria-hidden style={{ flex: 'none', background: tone ? `var(--status-${tone})` : 'var(--grid)' }} />
      <Link to={to} style={{ color: 'inherit' }}>{children}</Link>
    </li>
  )
}

/** The maintenance situation for the whole fleet, and what to do about it first. Every count comes
 * from the plan's own rules on the latest risk scores, at the inspection cost set on the At risk page. */
export function FleetHealth({ criticalAlerts }: { criticalAlerts: number }) {
  const [cost] = useInspectionCost()
  const q = useQuery({
    queryKey: ['maintenance-overview', cost],
    queryFn: () => api<MaintenanceOverview>(`/v1/maintenance/overview?inspection_cost=${cost}`),
    refetchInterval: 60_000,
  })
  const o = q.data
  if (q.isError) return <div className="card error-box" style={{ marginBottom: 16 }}>Fleet health is unavailable right now.</div>
  if (!o) return <div className="card empty" style={{ marginBottom: 16 }}>Loading fleet health…</div>
  if (!o.scored || !o.bands || !o.plan || !o.tomorrow)
    return <div className="card empty" style={{ marginBottom: 16 }}>No risk scores yet. Train and score the model (README, “History and the model”).</div>

  const { bands, plan, tomorrow } = o
  const risky = o.too_risky_to_wait ?? []
  const first = [...risky.map((e) => ({ ...e, noBay: true })), ...(o.first_inspections ?? []).map((e) => ({ ...e, noBay: false }))].slice(0, 5)

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-head">
        <h2>Fleet health <span className="muted" style={{ fontWeight: 400 }}>· breakdown risk in the next 7 days</span></h2>
        <span className="muted" style={{ fontSize: 12.5 }}>
          {count(o.scored)} vehicles scored {o.scored_at ? relTime(o.scored_at) : ''} · inspection {usd(o.inspection_cost_usd ?? cost)}
        </span>
      </div>
      <div style={{ padding: 16, display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(300px, 1fr))', gap: 24 }}>
        <div>
          <Link to="/risk" aria-label="Open breakdown risk" style={{ display: 'flex', height: 12, borderRadius: 6, overflow: 'hidden', background: 'var(--grid)' }}>
            {BANDS.map((b) => bands[b.key] > 0 && (
              <span key={b.key} title={`${b.label}: ${count(bands[b.key])}`}
                    style={{ width: `${(100 * bands[b.key]) / o.scored}%`, minWidth: 3, background: `var(${b.token})` }} />
            ))}
          </Link>
          <ul style={{ listStyle: 'none', margin: '12px 0 0', padding: 0 }}>
            {BANDS.map((b) => (
              <li key={b.key} style={{ display: 'flex', gap: 8, alignItems: 'baseline', padding: '3px 0' }}>
                <span className="swatch" aria-hidden style={{ background: `var(${b.token})` }} />
                <span style={{ flex: 1 }}>{b.label} <span className="muted" style={{ fontSize: 12 }}>· {b.note}</span></span>
                <strong className="num">{count(bands[b.key])}</strong>
              </li>
            ))}
          </ul>
          <div className="tiles" style={{ margin: '14px 0 0', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))' }}>
            <div>
              <div className="muted" style={{ fontSize: 12.5 }}>Expected breakdowns, 7 days</div>
              <div className="num" style={{ fontSize: 20, fontWeight: 600 }}>{count(Math.round(o.expected_breakdowns_7d ?? 0))}</div>
              <div className="muted" style={{ fontSize: 12 }}>sum of the calibrated probabilities</div>
            </div>
            <div>
              <div className="muted" style={{ fontSize: 12.5 }}>Net saving from the plan</div>
              <div className="num" style={{ fontSize: 20, fontWeight: 600 }}>{usd(plan.expected_net_saving_usd)}</div>
              <div className="muted" style={{ fontSize: 12 }}>{count(plan.scheduled)} inspections over {plan.days} days, after their cost</div>
            </div>
          </div>
          <div style={{ marginTop: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5 }}>
              <span className="muted">Tomorrow's bays</span>
              <span className="num">{count(tomorrow.used)} / {count(tomorrow.bays)} · {tomorrow.bays ? pct(tomorrow.used / tomorrow.bays) : '–'}</span>
            </div>
            <div style={{ height: 8, borderRadius: 4, background: 'var(--grid)', marginTop: 4 }} aria-hidden>
              <div style={{ width: `${tomorrow.bays ? Math.min(100, (100 * tomorrow.used) / tomorrow.bays) : 0}%`, height: '100%', borderRadius: 4, background: 'var(--series-1)' }} />
            </div>
            <div className="muted" style={{ fontSize: 12, marginTop: 3 }}>{tomorrow.depots_full} of {tomorrow.depots} depots full</div>
          </div>
        </div>

        <div>
          <h3 style={{ margin: '0 0 6px', fontSize: 14 }}>Today's actions</h3>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {plan.too_risky_to_wait > 0 && (
              <Action to="/plan" tone="critical">
                <strong>{count(plan.too_risky_to_wait)}</strong> vehicles too risky to wait have no free bay tomorrow: add a shift or a mobile technician
              </Action>
            )}
            <Action to="/plan" tone={tomorrow.inspections ? 'warning' : undefined}>
              <strong>{count(tomorrow.inspections)}</strong> inspections planned for tomorrow, {count(plan.urgent_scheduled)} of them urgent: review and book
            </Action>
            {tomorrow.moved_to_nearby_depot > 0 && (
              <Action to="/plan"><strong>{count(tomorrow.moved_to_nearby_depot)}</strong> go to a nearby depot because their own is full</Action>
            )}
            {plan.waiting_for_a_bay > 0 && (
              <Action to="/plan">
                <strong>{count(plan.waiting_for_a_bay)}</strong> worth inspecting are waiting for a bay ({usd(plan.value_waiting_usd)} expected saving)
              </Action>
            )}
            <Action to="/alerts" tone={criticalAlerts ? 'critical' : undefined}>
              <strong>{count(criticalAlerts)}</strong> critical alerts open
            </Action>
          </ul>

          <h3 style={{ margin: '14px 0 6px', fontSize: 14 }}>First in line</h3>
          {!first.length ? <div className="muted" style={{ fontSize: 13 }}>Nothing is worth inspecting at this cost.</div> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Vehicle</th><th style={{ textAlign: 'right' }}>Risk</th><th>Likely part</th><th>Where</th><th style={{ textAlign: 'right' }}>Net saving</th></tr></thead>
                <tbody>
                  {first.map((e) => (
                    <tr key={e.vehicle_id}>
                      <td><Link to={`/vehicles/${e.vehicle_id}`} className="mono">{e.vin}</Link></td>
                      <td className="num" style={{ textAlign: 'right' }}>{pct(e.probability)}</td>
                      <td>{e.likely_part ?? '–'}</td>
                      <td>{e.noBay ? <span style={{ color: 'var(--status-critical)' }}>no bay tomorrow</span>
                        : <>{e.depot}{e.moved_from && <div className="muted" style={{ fontSize: 12 }}>from {e.moved_from}</div>}</>}</td>
                      <td className="num" style={{ textAlign: 'right' }}>{usd(e.expected_value_usd)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div style={{ marginTop: 10 }}><Link to="/plan" className="btn small">Open the service plan</Link></div>
        </div>
      </div>
    </div>
  )
}
