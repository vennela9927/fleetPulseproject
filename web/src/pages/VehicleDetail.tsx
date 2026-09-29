import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { api, ApiError, type DailyPoint, type MinutePoint, query, type Telemetry, type VehicleDetail as Detail } from '../api'
import { SeriesChart, type SeriesPoint } from '../components/Charts'
import { SeverityBadge } from '../components/SeverityBadge'
import { alertDetail, count, fixed, relTime, ruleLabel } from '../format'

const STATUS_TOKEN = { DRIVING: '--series-1', IDLING: '--series-2', OFF: '--series-3' } as const
const STATUS_LABEL = { DRIVING: 'Driving', IDLING: 'Idling', OFF: 'Engine off' } as const

const day = (t: number) => new Date(t).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
const clock = (t: number) => new Date(t).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })

function Reading({ label, value, sub }: { label: string; value: React.ReactNode; sub?: string }) {
  return (
    <div className="card tile">
      <div className="label">{label}</div>
      <div className="value" style={{ fontSize: 22 }}>{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  )
}

export function VehicleDetail() {
  const { id } = useParams()
  const vehicle = useQuery({
    queryKey: ['vehicle', id],
    queryFn: () => api<Detail>(`/v1/vehicles/${id}`),
    refetchInterval: 10_000,
  })
  const daily = useQuery({
    queryKey: ['telemetry', id, '1d'],
    queryFn: () => api<Telemetry<DailyPoint>>(`/v1/vehicles/${id}/telemetry${query({
      resolution: '1d', start: new Date(Date.now() - 14 * 86_400_000).toISOString() })}`),
    enabled: vehicle.isSuccess,
  })
  const recent = useQuery({
    queryKey: ['telemetry', id, '1m'],
    queryFn: () => api<Telemetry<MinutePoint>>(`/v1/vehicles/${id}/telemetry${query({
      resolution: '1m', start: new Date(Date.now() - 6 * 3_600_000).toISOString() })}`),
    enabled: vehicle.isSuccess,
    refetchInterval: 60_000,
  })

  if (vehicle.isLoading) return <div className="empty">Loading vehicle…</div>
  if (vehicle.isError) {
    const notFound = vehicle.error instanceof ApiError && vehicle.error.status === 404
    return <div className="card error-box">{notFound ? 'This vehicle does not exist or is not in your fleet.' : `Could not load the vehicle: ${vehicle.error.message}`}</div>
  }
  const v = vehicle.data!
  const live = v.live
  const ev = v.powertrain === 'EV'
  const days = daily.data?.items ?? []
  const mins = recent.data?.items ?? []
  const dSeries = (f: (d: DailyPoint) => number | null): SeriesPoint[] => days.map((d) => ({ t: Date.parse(d.ts), v: f(d) }))
  const mSeries = (f: (d: MinutePoint) => number | null): SeriesPoint[] =>
    mins.map((d) => ({ t: Date.parse(d.ts.replace(' ', 'T') + (d.ts.includes('Z') ? '' : 'Z')), v: f(d) }))

  return (
    <>
      <div className="page-head">
        <div>
          <div className="muted" style={{ fontSize: 12.5 }}><Link to="/vehicles">Vehicles</Link> / {v.fleet_name}</div>
          <h1 className="mono" style={{ fontSize: 20 }}>{v.vin}</h1>
          <div className="secondary">{v.model_year} {v.oem} {v.model} · {v.powertrain} · <span className="pill">{v.status.toLowerCase().replace('_', ' ')}</span></div>
        </div>
      </div>

      <div className="tiles">
        <Reading label="Status" value={live ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
            <span className="swatch" style={{ background: `var(${STATUS_TOKEN[live.status]})` }} />{STATUS_LABEL[live.status]}
          </span>) : '–'} sub={live ? `Last report ${relTime(live.ts)}` : 'Not reporting'} />
        <Reading label="Speed" value={fixed(live?.speed_kmh, 0, ' km/h')} />
        {ev ? <Reading label="Charge" value={fixed(live?.soc_pct, 0, '%')} />
            : <Reading label="Fuel" value={fixed(live?.fuel_pct, 0, '%')} />}
        {!ev && <Reading label="Coolant" value={fixed(live?.coolant_c, 0, ' °C')} />}
        <Reading label="12 V battery" value={fixed(live?.batt_v, 2, ' V')} />
        <Reading label="Odometer" value={live?.odo_km ? `${count(Math.round(live.odo_km))} km` : '–'} />
        <Reading label="Fault codes" value={live?.dtc.length ? <span className="mono" style={{ fontSize: 16 }}>{live.dtc.join(', ')}</span> : 'None'} />
      </div>

      <div className="grid-2" style={{ marginBottom: 16 }}>
        <div className="card">
          <div className="card-head"><h2>Breakdown risk (7 days)</h2></div>
          <div className="card-body">
            {v.risk ? (
              <dl className="kv">
                <dt>Probability</dt><dd><strong>{Math.round(v.risk.failure_prob_7d * 100)}%</strong></dd>
                <dt>Likely component</dt><dd>{v.risk.predicted_component ?? '–'}</dd>
                <dt>Cost avoided if serviced</dt><dd>{v.risk.est_cost_avoided_usd ? `$${count(v.risk.est_cost_avoided_usd)}` : '–'}</dd>
                <dt>Model</dt><dd className="muted">{v.risk.model_version}, scored {relTime(v.risk.scored_at)}</dd>
              </dl>
            ) : <div className="muted">No prediction yet. Scores appear once the failure model has been trained and run.</div>}
          </div>
        </div>
        <div className="card">
          <div className="card-head"><h2>Open alerts</h2><Link to="/alerts" className="muted" style={{ fontSize: 12.5 }}>All alerts</Link></div>
          {v.open_alerts.length ? (
            <ul className="feed" style={{ maxHeight: 240 }}>
              {v.open_alerts.map((a) => (
                <li key={a.id}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                    <strong>{ruleLabel(a.rule_code)}</strong><SeverityBadge severity={a.severity} />
                  </div>
                  <div className="muted" style={{ fontSize: 12.5 }}>{alertDetail(a.rule_code, a.details)} · {relTime(a.opened_at)}</div>
                </li>
              ))}
            </ul>
          ) : <div className="empty">No open alerts.</div>}
        </div>
      </div>

      <h2 style={{ margin: '8px 0 12px' }}>Last 6 hours <span className="muted" style={{ fontWeight: 400 }}>· per minute</span></h2>
      <div className="grid-charts" style={{ marginBottom: 24 }}>
        <SeriesChart title="Average speed" unit="km/h" kind="line" formatX={clock}
                     data={mSeries((d) => d.avg_speed_kmh)} empty="No reports in the last 6 hours." domain={[0, 'auto']} />
        {ev ? (
          <SeriesChart title="Lowest 12 V battery voltage" unit="V" kind="line" formatX={clock} digits={2}
                       data={mSeries((d) => d.min_batt_v)} threshold={{ value: 11.8, label: 'Low 11.8 V', lower: true }} />
        ) : (
          <SeriesChart title="Highest coolant temperature" unit="°C" kind="line" formatX={clock}
                       data={mSeries((d) => d.max_coolant_c)} threshold={{ value: 110, label: 'Overheat 110 °C' }} />
        )}
      </div>

      <h2 style={{ margin: '8px 0 12px' }}>Last 14 days <span className="muted" style={{ fontWeight: 400 }}>· per day, operating hours</span></h2>
      <div className="grid-charts">
        <SeriesChart title="Distance driven" unit="km" kind="bar" formatX={day} data={dSeries((d) => d.km)} />
        <SeriesChart title="Fault codes reported" unit="" kind="bar" formatX={day} data={dSeries((d) => d.dtc_events)}
                     allZero="No fault codes reported in this period." />
        {ev ? (
          <SeriesChart title="Lowest battery health" unit="%" kind="line" formatX={day} digits={1} data={dSeries((d) => d.soh_min_pct)} />
        ) : (
          <SeriesChart title="Highest coolant temperature" unit="°C" kind="line" formatX={day} data={dSeries((d) => d.coolant_max_c)}
                       threshold={{ value: 110, label: 'Overheat 110 °C' }} />
        )}
        <SeriesChart title="Lowest 12 V battery voltage" unit="V" kind="line" formatX={day} digits={2}
                     data={dSeries((d) => d.batt_v_min)} threshold={{ value: 11.8, label: 'Low 11.8 V', lower: true }} />
      </div>
    </>
  )
}
