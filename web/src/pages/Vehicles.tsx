import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, Navigate, useParams } from 'react-router-dom'
import { api, type Fleet, type Page, query, type Vehicle } from '../api'

function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms)
    return () => clearTimeout(t)
  }, [value, ms])
  return v
}

export function Vehicles() {
  const [fleet, setFleet] = useState('')
  const [powertrain, setPowertrain] = useState('')
  const [vin, setVin] = useState('')
  const vinPrefix = useDebounced(vin.trim().toUpperCase())
  const validPrefix = vinPrefix.length >= 3 ? vinPrefix : ''

  const fleets = useQuery({ queryKey: ['fleets'], queryFn: () => api<{ items: Fleet[] }>('/v1/fleets') })
  const vehicles = useInfiniteQuery({
    queryKey: ['vehicles', fleet, powertrain, validPrefix],
    queryFn: ({ pageParam }) => api<Page<Vehicle>>(`/v1/vehicles${query({
      fleet_id: fleet, powertrain, vin_prefix: validPrefix, limit: 100, cursor: pageParam })}`),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.next_cursor,
  })
  const rows = vehicles.data?.pages.flatMap((p) => p.items) ?? []

  return (
    <>
      <div className="page-head"><h1>Vehicles</h1></div>
      <div className="filters">
        <select value={fleet} onChange={(e) => setFleet(e.target.value)} aria-label="Fleet">
          <option value="">All fleets</option>
          {fleets.data?.items.map((f) => <option key={f.id} value={f.id}>{f.name} ({f.vehicles.toLocaleString()})</option>)}
        </select>
        <select value={powertrain} onChange={(e) => setPowertrain(e.target.value)} aria-label="Powertrain">
          <option value="">Any powertrain</option>
          <option value="ICE">Combustion</option>
          <option value="HYBRID">Hybrid</option>
          <option value="EV">Electric</option>
        </select>
        <input type="text" placeholder="VIN starts with…" value={vin} maxLength={17}
               onChange={(e) => setVin(e.target.value)} aria-label="VIN prefix" style={{ width: 200 }} />
        {vin.trim() && vin.trim().length < 3 && <span className="muted">Type at least 3 characters</span>}
      </div>
      <div className="card">
        {vehicles.isError ? (
          <div className="error-box">Could not load vehicles: {(vehicles.error as Error).message}</div>
        ) : !rows.length ? (
          <div className="empty">{vehicles.isLoading ? 'Loading…' : 'No vehicles match these filters.'}</div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>VIN</th><th>Make and model</th><th>Year</th><th>Powertrain</th><th>Fleet</th><th>Status</th></tr></thead>
              <tbody>
                {rows.map((v) => (
                  <tr key={v.id}>
                    <td><Link to={`/vehicles/${v.id}`} className="mono">{v.vin}</Link></td>
                    <td>{v.oem} {v.model}</td>
                    <td className="num">{v.model_year}</td>
                    <td>{v.powertrain}</td>
                    <td>{v.fleet_name}</td>
                    <td><span className="pill">{v.status.toLowerCase().replace('_', ' ')}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {vehicles.hasNextPage && (
        <div style={{ textAlign: 'center', marginTop: 12 }}>
          <button className="btn" onClick={() => void vehicles.fetchNextPage()} disabled={vehicles.isFetchingNextPage}>
            {vehicles.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </>
  )
}

/** /vehicles/by-vin/:vin → /vehicles/:id (the live map knows VINs, pages use ids). */
export function VehicleByVin() {
  const { vin = '' } = useParams()
  const found = useQuery({
    queryKey: ['vehicle-by-vin', vin],
    queryFn: () => api<Page<Vehicle>>(`/v1/vehicles${query({ vin_prefix: vin, limit: 1 })}`),
  })
  if (found.isLoading) return <div className="empty">Finding vehicle…</div>
  const v = found.data?.items[0]
  if (!v || v.vin !== vin) return <div className="card error-box">Vehicle {vin} was not found.</div>
  return <Navigate to={`/vehicles/${v.id}`} replace />
}
