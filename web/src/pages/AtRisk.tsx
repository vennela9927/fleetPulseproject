import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, ApiError, type Page } from '../api'
import { count, dateTime, relTime } from '../format'

interface Outcome {
  flagged_per_day: number
  precision: number
  recall: number
  median_days_warning: number | null
  net_savings_usd_per_week_per_100k: number
}

interface ModelInfo {
  version: string
  trained_at: string
  metrics: {
    horizon_days: number
    split: { test: number; test_positive_rate: number; test_from: string }
    model: { pr_auc: number; roc_auc: number }
    baseline_rule: Outcome
    model_same_budget: Outcome
    model_alert_threshold: Outcome & { threshold: number }
    component_accuracy: number
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
          <div className="card">
            <div className="card-head"><h2>Alerts it raises</h2></div>
            <div className="card-body">
              <div className="tile" style={{ padding: 0, marginBottom: 12 }}>
                <div className="value num">{usd(m.model_alert_threshold.net_savings_usd_per_week_per_100k)}</div>
                <div className="secondary" style={{ fontSize: 12.5 }}>
                  net saving per week per 100K vehicles, inspecting only where the risk pays for it
                  ({count(Math.round(m.model_alert_threshold.flagged_per_day))} a day, not {count(Math.round(m.baseline_rule.flagged_per_day))})
                </div>
              </div>
              <dl className="kv">
                <dt>Alert when risk is at least</dt><dd>{pct(m.model_alert_threshold.threshold)}</dd>
                <dt>Alerts that were real</dt><dd>{pct(m.model_alert_threshold.precision)}</dd>
                <dt>Breakdowns alerted</dt><dd>{pct(m.model_alert_threshold.recall)}</dd>
                <dt>Likely part named correctly</dt><dd>{pct(m.component_accuracy)}</dd>
                <dt>Ranking quality (PR-AUC)</dt><dd>{m.model.pr_auc.toFixed(2)} <span className="muted">vs {(m.split.test_positive_rate).toFixed(3)} by chance</span></dd>
              </dl>
              <p className="muted" style={{ fontSize: 12.5, marginBottom: 0 }}>
                Savings assume a $150 inspection per flagged vehicle and count, for each breakdown caught, the
                difference between its breakdown cost (towing, downtime) and a planned repair.
              </p>
            </div>
          </div>
        </div>
      )}

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
