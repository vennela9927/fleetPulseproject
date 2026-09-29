import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, ApiError, type Page } from '../api'
import { count, dateTime, relTime } from '../format'
import { useInspectionCost } from '../inspectionCost'

interface Outcome {
  flagged_per_day: number
  precision: number
  recall: number
  median_days_warning: number | null
  net_savings_usd_per_week_per_100k: number
}

interface CurvePoint {
  threshold: number
  flagged: number
  caught: number
  saved_usd: number
  precision: number | null
  recall: number
}

interface MakerResult { pr_auc: number | null; precision: number | null; recall: number | null }

interface ModelInfo {
  version: string
  trained_at: string
  metrics: {
    horizon_days: number
    split: { test: number; test_positive_rate: number; test_from: string }
    model: { pr_auc: number; roc_auc: number }
    baseline_rule: Outcome
    model_same_budget: Outcome
    model_alert_threshold: Outcome & { threshold: number; avg_saving_usd: number }
    component_accuracy: number
    cost_curve?: CurvePoint[]
    unseen_maker?: { maker: string; examples: number; trained_with: MakerResult; never_seen: MakerResult }[]
  }
}

interface AtRiskRow {
  vehicle_id: number
  vin: string
  fleet_name: string
  oem: string
  model: string
  failure_prob_7d: number
  predicted_component: string | null
  est_cost_avoided_usd: string | number | null
  top_factors: { feature: string; label: string; value: number | null }[]
  scored_at: string
}

const pct = (x: number) => `${Math.round(x * 100)}%`
const usd = (x: number) => `${x < 0 ? '−' : ''}$${count(Math.abs(Math.round(x)))}`

function Compare({ label, rule, model, format }: {
  label: string; rule: number | null; model: number | null; format: (n: number) => string
}) {
  return (
    <tr>
      <td>{label}</td>
      <td className="num" style={{ textAlign: 'right' }}>{rule === null ? '–' : format(rule)}</td>
      <td className="num" style={{ textAlign: 'right' }}><strong>{model === null ? '–' : format(model)}</strong></td>
    </tr>
  )
}

const title = (s: string) => s.charAt(0) + s.slice(1).toLowerCase()

/** "What does an inspection cost you?" The alert rule is p x average saving > cost, so the cost sets
 * the threshold; the stored test-set curve gives what that threshold would have caught and saved. */
function CostCard({ m }: { m: ModelInfo['metrics'] }) {
  const [cost, setCost] = useInspectionCost()
  const curve = m.cost_curve ?? []
  const avgSaving = m.model_alert_threshold.avg_saving_usd
  const threshold = cost / avgSaving
  // The highest curve threshold at or below the rule's: it flags the same vehicles unless some score
  // falls in between, and calibrated scores are coarse steps, so it matches the model's real alerts.
  const point = [...curve].reverse().find((c) => c.threshold <= threshold + 1e-9) ?? curve[0] ?? null
  const net = (c: CurvePoint) => c.saved_usd - cost * c.flagged
  const best = curve.reduce<CurvePoint | null>((b, c) => (!b || net(c) > net(b) ? c : b), null)

  return (
    <div className="card">
      <div className="card-head"><h2>Alerts it raises</h2></div>
      <div className="card-body">
        {point ? (
          <>
            <label htmlFor="cost" className="secondary" style={{ fontSize: 13, display: 'block' }}>
              What does one inspection cost you? <strong className="num" style={{ color: 'var(--text-primary)' }}>${cost}</strong>
            </label>
            <input id="cost" type="range" min={50} max={600} step={10} value={cost} style={{ width: '100%', margin: '6px 0 12px' }}
                   onChange={(e) => setCost(Number(e.target.value))} aria-valuetext={`$${cost}`} />
            <div className="tile" style={{ padding: 0, marginBottom: 12 }}>
              <div className="value num">{usd(net(point))}</div>
              <div className="secondary" style={{ fontSize: 12.5 }}>
                net saving per week per 100K vehicles, alerting when risk is at least {pct(threshold)}
                {best && net(best) - net(point) >= 500 &&
                  <> (best possible in hindsight: {usd(net(best))} at {pct(best.threshold)})</>}
              </div>
            </div>
            <dl className="kv">
              <dt>Alerts per day, per 100K</dt><dd className="num">{count(Math.round(point.flagged))}</dd>
              <dt>Alerts that were real</dt><dd>{point.precision === null ? '–' : pct(point.precision)}</dd>
              <dt>Breakdowns alerted</dt><dd>{pct(point.recall)}</dd>
              <dt>Likely part named correctly</dt><dd>{pct(m.component_accuracy)}</dd>
              <dt>Ranking quality (PR-AUC)</dt><dd>{m.model.pr_auc.toFixed(2)} <span className="muted">vs {(m.split.test_positive_rate).toFixed(3)} by chance</span></dd>
            </dl>
            <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>
              An inspection pays when risk × the breakdown it prevents (on average ${count(avgSaving)} of towing and
              downtime over a planned repair) is more than it costs. The <Link to="/plan">service plan</Link> uses this cost.
            </p>
          </>
        ) : (
          <dl className="kv">
            <dt>Alert when risk is at least</dt><dd>{pct(m.model_alert_threshold.threshold)}</dd>
            <dt>Alerts that were real</dt><dd>{pct(m.model_alert_threshold.precision)}</dd>
            <dt>Breakdowns alerted</dt><dd>{pct(m.model_alert_threshold.recall)}</dd>
          </dl>
        )}
      </div>
    </div>
  )
}

interface ModelHealth {
  inspections: number
  faults_found: number
  found_rate: number | null
  expected_rate: number | null
  expected_range: [number, number] | null
  part_right: number | null
  status: 'TOO_FEW' | 'ON_TRACK' | 'BELOW_EXPECTED' | 'ABOVE_EXPECTED'
  min_inspections: number
  window_days: number
}

const HEALTH_TEXT: Record<ModelHealth['status'], string> = {
  TOO_FEW: 'Not enough inspections yet to judge.',
  ON_TRACK: 'On track: the workshop finds faults about as often as the model promised.',
  BELOW_EXPECTED: 'Below what the model promised: its probabilities are too confident for today\'s fleet. Recalibrate or retrain on these results.',
  ABOVE_EXPECTED: 'Faults are found more often than predicted: the model is too cautious. Retrain to catch more.',
}

/** The feedback loop: inspections booked from the model's scores, graded by what mechanics found. */
function WorkshopCard() {
  const h = useQuery({ queryKey: ['model-health'], queryFn: () => api<ModelHealth>('/v1/maintenance/model-health'),
    refetchInterval: 30_000 })
  const d = h.data
  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-head">
        <h2>The model, graded by the workshop</h2>
        <span className="muted" style={{ fontSize: 12.5 }}>inspections booked from its scores, last {d?.window_days ?? 30} days</span>
      </div>
      {!d ? <div className="empty">{h.isError ? 'Could not load.' : 'Loading…'}</div> : !d.inspections ? (
        <div className="empty">No inspection results yet. Record them on the <Link to="/plan">service plan</Link>.</div>
      ) : (
        <div className="card-body" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 16 }}>
          <div className="tile" style={{ padding: 0 }}>
            <div className="label">Faults found</div>
            <div className="value num">{d.found_rate === null ? '–' : pct(d.found_rate)}</div>
            <div className="sub">{d.faults_found} of {d.inspections} inspections</div>
          </div>
          <div className="tile" style={{ padding: 0 }}>
            <div className="label">The model promised</div>
            <div className="value num">{d.expected_rate === null ? '–' : pct(d.expected_rate)}</div>
            <div className="sub">{d.expected_range ? `expected range ${pct(d.expected_range[0])}–${pct(d.expected_range[1])}` : ' '}</div>
          </div>
          <div className="tile" style={{ padding: 0 }}>
            <div className="label">Right part named</div>
            <div className="value num">{d.part_right === null ? '–' : pct(d.part_right)}</div>
            <div className="sub">when a fault was found</div>
          </div>
          <div style={{ alignSelf: 'center', fontSize: 13 }}>
            <span className="pill" style={{ marginBottom: 6 }}>{d.status.replace(/_/g, ' ').toLowerCase()}</span>
            <div className="secondary" style={{ marginTop: 6 }}>{HEALTH_TEXT[d.status]}</div>
          </div>
        </div>
      )}
    </div>
  )
}

function MakersCard({ rows }: { rows: NonNullable<ModelInfo['metrics']['unseen_maker']> }) {
  const f = (x: number | null) => (x === null ? '–' : x.toFixed(2))
  return (
    <div className="card" style={{ marginBottom: 16 }}>
      <div className="card-head">
        <h2>Does it work for a vehicle maker it has never seen?</h2>
        <span className="muted" style={{ fontSize: 12.5 }}>each maker left out of training in turn</span>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Maker</th>
              <th style={{ textAlign: 'right' }}>Ranking quality, trained with it</th><th style={{ textAlign: 'right' }}>never seen</th>
              <th style={{ textAlign: 'right' }}>Breakdowns caught, trained with it</th><th style={{ textAlign: 'right' }}>never seen</th>
              <th style={{ textAlign: 'right' }}>Alerts that were real, never seen</th></tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.maker}>
                <td>{title(r.maker)}</td>
                <td className="num" style={{ textAlign: 'right' }}>{f(r.trained_with.pr_auc)}</td>
                <td className="num" style={{ textAlign: 'right' }}><strong>{f(r.never_seen.pr_auc)}</strong></td>
                <td className="num" style={{ textAlign: 'right' }}>{r.trained_with.recall === null ? '–' : pct(r.trained_with.recall)}</td>
                <td className="num" style={{ textAlign: 'right' }}><strong>{r.never_seen.recall === null ? '–' : pct(r.never_seen.recall)}</strong></td>
                <td className="num" style={{ textAlign: 'right' }}>{r.never_seen.precision === null ? '–' : pct(r.never_seen.precision)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted" style={{ fontSize: 12.5, margin: 0, padding: '10px 16px 14px' }}>
        Every maker's data is converted to one standard format before the model sees it, so a maker onboarded today
        (like Draco) is scored from its first full day of data, with close to the accuracy of makers it was trained on.
      </p>
    </div>
  )
}

export function AtRisk() {
  const model = useQuery({ queryKey: ['risk-model'], queryFn: () => api<ModelInfo>('/v1/risk/model'), retry: false })
  const rows = useInfiniteQuery({
    queryKey: ['at-risk'],
    queryFn: ({ pageParam }) => api<Page<AtRiskRow>>(`/v1/risk?limit=50${pageParam ? `&cursor=${pageParam}` : ''}`),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor,
    enabled: model.isSuccess,
  })

  if (model.isError) {
    const none = model.error instanceof ApiError && model.error.status === 404
    return (
      <>
        <div className="page-head"><h1>Breakdown risk</h1></div>
        <div className="card error-box">{none ? 'No failure model has been trained yet.' : `Could not load the model: ${model.error.message}`}</div>
      </>
    )
  }
  const m = model.data?.metrics
  const items = rows.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <div className="page-head">
        <h1>Breakdown risk</h1>
        <span className="muted">{model.data ? `Model ${model.data.version}, trained ${relTime(model.data.trained_at)}` : ' '}</span>
      </div>

      {m && (
        <div className="grid-2" style={{ marginBottom: 16 }}>
          <div className="card">
            <div className="card-head">
              <h2>How well it predicts</h2>
              <span className="muted" style={{ fontSize: 12.5 }}>vehicles and days it never saw in training</span>
            </div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Checking the same number of vehicles each day</th><th style={{ textAlign: 'right' }}>Mechanic's rule</th><th style={{ textAlign: 'right' }}>Model</th></tr></thead>
                <tbody>
                  <Compare label="Flagged vehicles that did break down" rule={m.baseline_rule.precision} model={m.model_same_budget.precision} format={pct} />
                  <Compare label="Breakdowns caught in advance" rule={m.baseline_rule.recall} model={m.model_same_budget.recall} format={pct} />
                  <Compare label="Typical warning before breakdown" rule={m.baseline_rule.median_days_warning} model={m.model_same_budget.median_days_warning} format={(d) => `${d} days`} />
                  <Compare label="Net saving per week, per 100K vehicles" rule={m.baseline_rule.net_savings_usd_per_week_per_100k} model={m.model_same_budget.net_savings_usd_per_week_per_100k} format={usd} />
                </tbody>
              </table>
            </div>
          </div>
          <CostCard m={m} />
        </div>
      )}
      {m && <WorkshopCard />}
      {m?.unseen_maker && <MakersCard rows={m.unseen_maker} />}

      <div className="card">
        <div className="card-head"><h2>Vehicles most likely to break down in the next {m?.horizon_days ?? 7} days</h2>
          <span className="muted" style={{ fontSize: 12.5 }}>{items[0] ? `scored ${dateTime(items[0].scored_at)}` : ' '}</span></div>
        {rows.isError ? <div className="error-box">Could not load risk scores: {(rows.error as Error).message}</div>
          : !items.length ? <div className="empty">{rows.isLoading || model.isLoading ? 'Loading…' : 'No vehicles have been scored yet.'}</div>
          : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Vehicle</th><th>Risk</th><th>Likely problem</th><th style={{ textAlign: 'right' }}>Saved if serviced</th><th>Why</th></tr></thead>
                <tbody>
                  {items.map((r) => (
                    <tr key={r.vehicle_id}>
                      <td>
                        <Link to={`/vehicles/${r.vehicle_id}`} className="mono">{r.vin}</Link>
                        <div className="muted" style={{ fontSize: 12 }}>{r.oem} {r.model} · {r.fleet_name}</div>
                      </td>
                      <td style={{ minWidth: 120 }}>
                        <div className="num" style={{ fontWeight: 600 }}>{pct(r.failure_prob_7d)}</div>
                        <div style={{ height: 6, borderRadius: 3, background: 'var(--grid)', marginTop: 4 }} aria-hidden>
                          <div style={{ width: pct(r.failure_prob_7d), height: '100%', borderRadius: 3, background: 'var(--series-1)' }} />
                        </div>
                      </td>
                      <td>{r.predicted_component ?? '–'}</td>
                      <td className="num" style={{ textAlign: 'right' }}>{r.est_cost_avoided_usd ? usd(Number(r.est_cost_avoided_usd)) : '–'}</td>
                      <td className="secondary" style={{ fontSize: 12.5 }}>{r.top_factors.map((f) => f.label).join(' · ') || '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>
      {rows.hasNextPage && (
        <div style={{ textAlign: 'center', marginTop: 12 }}>
          <button className="btn" onClick={() => void rows.fetchNextPage()} disabled={rows.isFetchingNextPage}>
            {rows.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </>
  )
}
