import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api, type LiveMap as LiveMapData, type Summary } from '../api'
import { AlertFeed } from '../components/AlertFeed'
import { IconCritical, IconWarning } from '../components/icons'
import { LiveMap } from '../components/LiveMap'
import { count } from '../format'

function Tile({ label, value, sub, swatch, icon }: {
  label: string; value: string; sub?: string; swatch?: string; icon?: React.ReactNode
}) {
  return (
    <div className="card tile">
      <div className="label">
        {swatch && <span className="swatch" style={{ background: `var(${swatch})` }} />}
        {icon}
        {label}
      </div>
      <div className="value">{value}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  )
}

export function Overview() {
  const navigate = useNavigate()
  const summary = useQuery({
    queryKey: ['summary'],
    queryFn: () => api<Summary>('/v1/fleet/summary'),
    refetchInterval: 5_000,
  })
  const live = useQuery({
    queryKey: ['live-map'],
    queryFn: () => api<LiveMapData>('/v1/live/vehicles?limit=60000'),
    refetchInterval: 5_000,
  })
  const s = summary.data
  const pct = s && s.vehicles ? Math.round((100 * s.reporting) / s.vehicles) : null

  return (
    <>
      <div className="page-head">
        <h1>Fleet overview</h1>
        <span className="muted">Updates every 5 seconds</span>
      </div>
      <div className="tiles">
        <Tile label="Vehicles" value={count(s?.vehicles)} />
        <Tile label="Reporting now" value={count(s?.reporting)} sub={pct === null ? undefined : `${pct}% of fleet`} />
        <Tile label="Driving" value={count(s?.live_status.DRIVING ?? 0)} swatch="--series-1" />
        <Tile label="Idling" value={count(s?.live_status.IDLING ?? 0)} swatch="--series-2" />
        <Tile label="Engine off" value={count(s?.live_status.OFF ?? 0)} swatch="--series-3" />
        <Tile label="Critical alerts" value={count(s?.open_alerts.CRITICAL ?? 0)}
              icon={<IconCritical style={{ width: 13, height: 13, color: 'var(--status-critical)' }} />} sub="open" />
        <Tile label="Warnings" value={count(s?.open_alerts.WARNING ?? 0)}
              icon={<IconWarning style={{ width: 13, height: 13, color: 'var(--status-warning)' }} />} sub="open" />
      </div>
      {summary.isError && <div className="card error-box" style={{ marginBottom: 16 }}>Live data is unavailable right now. Retrying…</div>}
      <div className="grid-2">
        <LiveMap data={live.data} onOpen={(vin) => navigate(`/vehicles/by-vin/${vin}`)} />
        <AlertFeed />
      </div>
    </>
  )
}
