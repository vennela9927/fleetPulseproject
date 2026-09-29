import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { hasRole } from '../auth'
import { count } from '../format'
import { useInspectionCost } from '../inspectionCost'

interface PlanEntry {
  vehicle_id: number
  vin: string
  fleet: string
  probability: number
  likely_part: string | null
  saving_usd: number
  expected_value_usd: number
  urgent: boolean
  depot_id?: number
  depot?: string
  date?: string
  moved_from?: string | null
  distance_km?: number | null
}

interface Plan {
  inspection_cost_usd: number
  days: string[]
  summary: {
    scheduled: number
    urgent_scheduled: number
    expected_net_saving_usd: number
    too_risky_to_wait: number
    waiting_for_a_bay: number
    value_waiting_usd: number
    not_worth_inspecting: number
  }
  depots: { depot_id: number; depot: string; bays_per_day: number
    days: { date: string; bays: number; already_booked: number; items: PlanEntry[] }[] }[]
  too_risky_to_wait: PlanEntry[]
  waiting: PlanEntry[]
}

interface Parts {
  expected_failures: number
  items: { depot_id: number; depot: string; part: string; expected: number; low: number; high: number; high_risk_vehicles: number }[]
}

const pct = (x: number) => `${Math.round(x * 100)}%`
const usd = (x: number) => `${x < 0 ? '−' : ''}$${count(Math.abs(Math.round(x)))}`
const day = (iso: string) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })

function Load({ used, bays }: { used: number; bays: number }) {
  const full = used >= bays
  return (
    <div style={{ minWidth: 90 }}>
      <div className="num" style={{ fontSize: 13 }}>{used} / {bays}{full && <span className="muted"> · full</span>}</div>
      <div style={{ height: 6, borderRadius: 3, background: 'var(--grid)', marginTop: 3 }} aria-hidden>
        <div style={{ width: `${Math.min(100, (100 * used) / bays)}%`, height: '100%', borderRadius: 3, background: 'var(--series-1)' }} />
      </div>
    </div>
  )
}

function Vehicle({ e }: { e: PlanEntry }) {
  return (
    <>
      <Link to={`/vehicles/${e.vehicle_id}`} className="mono">{e.vin}</Link>
      <div className="muted" style={{ fontSize: 12 }}>{e.fleet}</div>
    </>
  )
}

interface Inspection {
  id: number
  vehicle_id: number
  vin: string
  depot: string
  scheduled_for: string
  predicted_probability: number | null
  predicted_component: string | null
}

/** Closing the loop: what the mechanic found becomes the label the model is graded on. */
function InspectionsCard() {
  const qc = useQueryClient()
  const manager = hasRole('fleet_manager')
  const list = useQuery({ queryKey: ['inspections'],
    queryFn: () => api<{ items: Inspection[] }>('/v1/maintenance/inspections?status=SCHEDULED&limit=8') })
  const refresh = () => ['inspections', 'model-health', 'plan'].forEach((k) => void qc.invalidateQueries({ queryKey: [k] }))
  const record = useMutation({
    mutationFn: (v: { id: number; found: boolean; part: string | null }) =>
      api(`/v1/maintenance/bookings/${v.id}/result`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fault_found: v.found, component: v.found ? v.part : null }) }),
    onSuccess: refresh,
  })
  const workshop = useMutation({
    mutationFn: () => api<{ recorded: number; faults_found: number }>('/v1/ops/workshop-results?limit=100', { method: 'POST' }),
    onSuccess: refresh,
  })
  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-head">
        <h2>Record what the workshop found</h2>
        {manager && (
          <button className="btn small" disabled={workshop.isPending} onClick={() => workshop.mutate()}
                  title="Demo: the simulator knows each vehicle's real fault and plays the workshop system">
            {workshop.isPending ? 'Importing…' : workshop.data ? `Imported ${workshop.data.recorded} results` : 'Import results from the workshop (demo)'}
          </button>
        )}
      </div>
      {!list.data?.items.length ? <div className="empty">{list.isLoading ? 'Loading…' : 'No booked inspections waiting for a result.'}</div> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Booked</th><th>Vehicle</th><th>Model said</th><th /></tr></thead>
            <tbody>{list.data.items.map((i) => (
              <tr key={i.id}>
                <td>{day(i.scheduled_for.slice(0, 10))}<div className="muted" style={{ fontSize: 12 }}>{i.depot}</div></td>
                <td><Link to={`/vehicles/${i.vehicle_id}`} className="mono">{i.vin}</Link></td>
                <td>{i.predicted_probability === null ? '–' : `${pct(i.predicted_probability)}, ${i.predicted_component ?? 'unknown part'}`}</td>
                <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {manager && <>
                    <button className="btn small" disabled={record.isPending}
                            onClick={() => record.mutate({ id: i.id, found: true, part: i.predicted_component })}>Fault found</button>{' '}
                    <button className="btn small" disabled={record.isPending}
                            onClick={() => record.mutate({ id: i.id, found: false, part: null })}>Nothing found</button>
                  </>}
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
      <p className="muted" style={{ fontSize: 12.5, margin: 0, padding: '10px 16px 14px' }}>
        Each result is compared with the probability the model gave when the inspection was booked. The At risk page
        shows whether faults are being found as often as the model promised.
      </p>
    </div>
  )
}

export function ServicePlan() {
  const qc = useQueryClient()
  const [cost, setCost] = useInspectionCost()
  const [days, setDays] = useState(3)
  const [depotFilter, setDepotFilter] = useState<number | 'all'>('all')
  const [booked, setBooked] = useState<number | null>(null)
  const manager = hasRole('fleet_manager')

  const plan = useQuery({
    queryKey: ['plan', cost, days],
    queryFn: () => api<Plan>(`/v1/maintenance/plan?inspection_cost=${cost}&days=${days}`),
    placeholderData: (prev) => prev,
  })
  const parts = useQuery({ queryKey: ['parts'], queryFn: () => api<Parts>('/v1/maintenance/parts') })

  const items = useMemo(() => (plan.data?.depots ?? []).flatMap((d) => d.days.flatMap((x) => x.items)), [plan.data])
  const book = useMutation({
    mutationFn: () => api<{ booked: number }>('/v1/maintenance/bookings', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ items: items.map((b) => ({ vehicle_id: b.vehicle_id, depot_id: b.depot_id, date: b.date })) }),
    }),
    onSuccess: (r) => { setBooked(r.booked); void qc.invalidateQueries({ queryKey: ['plan'] }) },
  })

  const p = plan.data
  const s = p?.summary
  const shown = depotFilter === 'all' ? items : items.filter((b) => b.depot_id === depotFilter)

  // Parts as a part x depot grid: stores teams read across a row to see where each part is needed.
  const partRows = useMemo(() => {
    const depots = [...new Map((parts.data?.items ?? []).map((i) => [i.depot_id, i.depot])).entries()]
    const byPart = new Map<string, Map<number, Parts['items'][number]>>()
    for (const i of parts.data?.items ?? []) {
      if (!byPart.has(i.part)) byPart.set(i.part, new Map())
      byPart.get(i.part)!.set(i.depot_id, i)
    }
    const rows = [...byPart.entries()].map(([part, m]) => ({ part, m, total: [...m.values()].reduce((a, i) => a + i.expected, 0) }))
    return { depots, rows: rows.sort((a, b) => b.total - a.total) }
  }, [parts.data])

  return (
    <>
      <div className="page-head">
        <h1>Service plan</h1>
        <span className="muted">Who to inspect, where and when, within each depot's bays. Most valuable first.</span>
      </div>

      <div className="filters">
        <label className="secondary" htmlFor="cost">Inspection cost $</label>
        <input id="cost" type="number" min={10} max={2000} step={10} value={cost} style={{ width: 90 }}
               onChange={(e) => { const v = Number(e.target.value); if (v >= 10 && v <= 2000) setCost(v) }} />
        <label className="secondary" htmlFor="days" style={{ marginLeft: 8 }}>Plan</label>
        <select id="days" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {[1, 2, 3, 5, 7].map((d) => <option key={d} value={d}>{d === 1 ? 'tomorrow' : `next ${d} days`}</option>)}
        </select>
        <span style={{ flex: 1 }} />
        {manager && items.length > 0 && booked === null && (
          <button className="btn primary" disabled={book.isPending} onClick={() => book.mutate()}>
            {book.isPending ? 'Booking…' : `Book these ${items.length} inspections`}
          </button>
        )}
        {booked !== null && <span className="pill">Booked {booked} inspections</span>}
      </div>
      {book.isError && (
        <div className="card error-box" style={{ marginBottom: 12 }}>
          {book.error instanceof ApiError && book.error.status === 409 ? `The plan changed: ${book.error.message}` : book.error.message}
        </div>
      )}

      {plan.isError ? <div className="card error-box">Could not load the plan: {(plan.error as Error).message}</div> : !p || !s ? (
        <div className="card empty">Loading…</div>
      ) : (
        <>
          <div className="tiles">
            <div className="card tile"><div className="label">Inspections planned</div>
              <div className="value num">{count(s.scheduled)}</div>
              <div className="sub">{s.urgent_scheduled} of them urgent (risk ≥ 80%), all tomorrow</div></div>
            <div className="card tile"><div className="label">Expected net saving</div>
              <div className="value num">{usd(s.expected_net_saving_usd)}</div>
              <div className="sub">risk × breakdown cost avoided, minus ${p.inspection_cost_usd} each</div></div>
            <div className="card tile"><div className="label">Too risky to wait</div>
              <div className="value num">{s.too_risky_to_wait}</div>
              <div className="sub">no bay tomorrow within 350 km; the smallest savings are left out first</div></div>
            <div className="card tile"><div className="label">Waiting for a bay</div>
              <div className="value num">{s.waiting_for_a_bay}</div>
              <div className="sub">{s.waiting_for_a_bay ? `${usd(s.value_waiting_usd)} expected value` : 'none'}</div></div>
          </div>

          {p.too_risky_to_wait.length > 0 && (
            <div className="card" style={{ marginBottom: 16, borderColor: 'var(--status-serious)' }}>
              <div className="card-head">
                <h2>Too risky to wait: no bay tomorrow</h2>
                <span className="muted" style={{ fontSize: 12.5 }}>
                  urgent vehicles with the smallest expected saving, left out when bays ran out · add a shift, or send a mobile technician
                </span>
              </div>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Vehicle</th><th>Risk</th><th>Likely problem</th><th style={{ textAlign: 'right' }}>Expected saving</th></tr></thead>
                  <tbody>{p.too_risky_to_wait.slice(0, 10).map((e) => (
                    <tr key={e.vehicle_id}><td><Vehicle e={e} /></td><td className="num">{pct(e.probability)}</td>
                      <td>{e.likely_part ?? '–'}</td><td className="num" style={{ textAlign: 'right' }}>{usd(e.expected_value_usd)}</td></tr>
                  ))}</tbody>
                </table>
              </div>
              {p.too_risky_to_wait.length > 10 && <div className="muted" style={{ padding: '8px 16px', fontSize: 12.5 }}>and {p.too_risky_to_wait.length - 10} more</div>}
            </div>
          )}

          <div className="card" style={{ marginBottom: 16 }}>
            <div className="card-head"><h2>Bays used per depot</h2><span className="muted" style={{ fontSize: 12.5 }}>including bookings already made</span></div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Depot</th>{p.days.map((d) => <th key={d}>{day(d)}</th>)}<th style={{ textAlign: 'right' }}>From other depots</th></tr></thead>
                <tbody>{p.depots.map((d) => {
                  const moved = d.days.flatMap((x) => x.items).filter((b) => b.moved_from)
                  return (
                    <tr key={d.depot_id}>
                      <td>{d.depot}<div className="muted" style={{ fontSize: 12 }}>{d.bays_per_day} bays a day</div></td>
                      {d.days.map((x) => <td key={x.date}><Load used={x.already_booked + x.items.length} bays={x.bays} /></td>)}
                      <td className="secondary" style={{ textAlign: 'right', fontSize: 12.5 }}>
                        {moved.length ? `${moved.length} from ${[...new Set(moved.map((b) => b.moved_from))].join(', ')}` : '–'}
                      </td>
                    </tr>
                  )
                })}</tbody>
              </table>
            </div>
          </div>

          <div className="card" style={{ marginBottom: 16 }}>
            <div className="card-head">
              <h2>Planned inspections</h2>
              <select value={depotFilter} aria-label="Depot" onChange={(e) => setDepotFilter(e.target.value === 'all' ? 'all' : Number(e.target.value))}>
                <option value="all">All depots</option>
                {p.depots.map((d) => <option key={d.depot_id} value={d.depot_id}>{d.depot}</option>)}
              </select>
            </div>
            {!shown.length ? <div className="empty">Nothing to inspect: no vehicle's risk pays for an inspection at ${cost}.</div> : (
              <div className="table-wrap" style={{ maxHeight: 520, overflowY: 'auto' }}>
                <table>
                  <thead><tr><th>When</th><th>Where</th><th>Vehicle</th><th>Risk</th><th>Likely problem</th><th style={{ textAlign: 'right' }}>Expected saving</th></tr></thead>
                  <tbody>{shown.map((b) => (
                    <tr key={b.vehicle_id}>
                      <td>{day(b.date!)}{b.urgent && <div className="muted" style={{ fontSize: 12 }}>urgent</div>}</td>
                      <td>{b.depot}{b.moved_from && <div className="muted" style={{ fontSize: 12 }}>from {b.moved_from}, {b.distance_km} km</div>}</td>
                      <td><Vehicle e={b} /></td>
                      <td className="num">{pct(b.probability)}</td>
                      <td>{b.likely_part ?? '–'}</td>
                      <td className="num" style={{ textAlign: 'right' }}>{usd(b.expected_value_usd)}</td>
                    </tr>
                  ))}</tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}

      <InspectionsCard />

      <div className="card">
        <div className="card-head">
          <h2>Parts likely needed in the next 7 days</h2>
          <span className="muted" style={{ fontSize: 12.5 }}>
            {parts.data ? `about ${count(Math.round(parts.data.expected_failures))} breakdowns expected in total · 90% range in brackets` : ' '}
          </span>
        </div>
        {parts.isError ? <div className="error-box">Could not load the forecast.</div> : !parts.data ? <div className="empty">Loading…</div> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Part</th>{partRows.depots.map(([id, name]) => <th key={id} style={{ textAlign: 'right' }}>{name.replace(' Hub', '')}</th>)}
                <th style={{ textAlign: 'right' }}>Total</th></tr></thead>
              <tbody>{partRows.rows.map((r) => (
                <tr key={r.part}>
                  <td>{r.part}</td>
                  {partRows.depots.map(([id]) => {
                    const i = r.m.get(id)
                    return <td key={id} className="num" style={{ textAlign: 'right' }}>
                      {i ? <Fragment>{Math.round(i.expected)} <span className="muted">({i.low}–{i.high})</span></Fragment> : '–'}
                    </td>
                  })}
                  <td className="num" style={{ textAlign: 'right' }}><strong>{Math.round(r.total)}</strong></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        )}
        <p className="muted" style={{ fontSize: 12.5, margin: 0, padding: '10px 16px 14px' }}>
          Each vehicle's risk is a calibrated probability, so they add up to the expected number of failures of each part;
          the range assumes vehicles fail independently. The part is the model's best guess (right 83% of the time on test).
        </p>
      </div>
    </>
  )
}
