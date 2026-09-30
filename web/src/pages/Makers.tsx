import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import { hasRole } from '../auth'
import { relTime } from '../format'

interface Mapping {
  id: number
  oem_code: string
  version: number
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED'
  proposed_by: string
  approved_by_email: string | null
  created_at: string
}

interface Status {
  active_versions: Record<string, number>
  replays: Record<string, { state: string; scanned?: number; replayed?: number; version: number }>
}

interface Preview {
  compiled: boolean
  error?: string
  oem?: string
  sampled?: number
  mapped?: number
  failures?: { reason: string; count: number; example: string }[]
  example?: Record<string, unknown> | null
}

const title = (oem: string) => oem.charAt(0) + oem.slice(1).toLowerCase()

export function Makers() {
  const qc = useQueryClient()
  const admin = hasRole('platform_admin')
  const [previews, setPreviews] = useState<Record<number, Preview>>({})
  const [error, setError] = useState<string | null>(null)

  const mappings = useQuery({ queryKey: ['mappings'], queryFn: () => api<{ items: Mapping[] }>('/v1/oem-mappings') })
  const status = useQuery({
    queryKey: ['mapping-status'],
    queryFn: () => api<Status>('/v1/oem-mappings/status'),
    refetchInterval: (q) => Object.values(q.state.data?.replays ?? {}).some((r) => r.state === 'RUNNING') ? 1_000 : 10_000,
  })
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: ['mappings'] })
    void qc.invalidateQueries({ queryKey: ['mapping-status'] })
  }
  const fail = (e: Error) => setError(e.message)

  const propose = useMutation({
    mutationFn: async (file: File) => {
      let spec: unknown
      try {
        spec = JSON.parse(await file.text())
      } catch {
        throw new Error(`${file.name} is not valid JSON`)
      }
      return api<{ id: number }>('/v1/oem-mappings', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(spec),
      })
    },
    onSuccess: () => { setError(null); refresh() },
    onError: fail,
  })
  const preview = useMutation({
    mutationFn: (id: number) => api<Preview>(`/v1/oem-mappings/${id}/preview`, { method: 'POST' }),
    onSuccess: (p, id) => setPreviews((s) => ({ ...s, [id]: p })),
    onError: fail,
  })
  const approve = useMutation({
    mutationFn: (id: number) => api<unknown>(`/v1/oem-mappings/${id}/approve`, { method: 'POST' }),
    onSuccess: () => { setError(null); refresh() },
    onError: fail,
  })

  const rows = mappings.data?.items ?? []
  const drafts = rows.filter((m) => m.status === 'DRAFT')
  const active = rows.filter((m) => m.status === 'ACTIVE')

  return (
    <>
      <div className="page-head">
        <h1>Vehicle makers</h1>
        <span className="muted">A new maker is a mapping file: no code change, no deploy, no downtime</span>
      </div>

      {admin && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div className="card-head"><h2>Onboard a maker</h2></div>
          <div className="card-body">
            <p className="secondary" style={{ marginTop: 0 }}>
              Upload the maker's mapping file. Until it is approved, their vehicles' data is kept (parked), not lost;
              approval replays it.
            </p>
            <input type="file" accept="application/json,.json" aria-label="Mapping file" disabled={propose.isPending}
                   onChange={(e) => { const f = e.target.files?.[0]; if (f) propose.mutate(f); e.target.value = '' }} />
          </div>
        </div>
      )}

      {drafts.map((m) => {
        const p = previews[m.id]
        const clean = p?.compiled && p.sampled && p.mapped === p.sampled
        return (
          <div className="card" key={m.id} style={{ marginBottom: 16 }}>
            <div className="card-head">
              <h2>{title(m.oem_code)} v{m.version} <span className="pill" style={{ marginLeft: 6 }}>awaiting approval</span></h2>
              <span className="muted" style={{ fontSize: 12.5 }}>proposed {relTime(m.created_at)}</span>
            </div>
            <div className="card-body">
              {!p ? (
                <p className="secondary" style={{ marginTop: 0 }}>Preview maps this maker's parked events with the proposed file before anything goes live.</p>
              ) : !p.compiled ? (
                <p style={{ marginTop: 0 }}><strong>The mapping does not compile:</strong> {p.error}</p>
              ) : !p.sampled ? (
                <p style={{ marginTop: 0 }}>The mapping compiles, but no parked events were found to test it against yet.</p>
              ) : (
                <div style={{ marginBottom: 12 }}>
                  <p style={{ marginTop: 0 }}>
                    <strong>{p.mapped} of {p.sampled}</strong> parked events map cleanly
                    {clean ? '.' : '; the rest fail as shown below.'}
                  </p>
                  {p.failures?.map((f) => (
                    <div key={f.reason} className="secondary" style={{ fontSize: 12.5 }}>{f.count} × {f.reason}: <span className="mono">{f.example}</span></div>
                  ))}
                  {p.example && (
                    <details style={{ marginTop: 8 }}>
                      <summary className="secondary" style={{ cursor: 'pointer' }}>Example of a mapped event</summary>
                      <pre className="mono" style={{ margin: '8px 0 0', padding: 10, background: 'var(--hover)', borderRadius: 8, overflowX: 'auto' }}>
                        {JSON.stringify(p.example, null, 2)}
                      </pre>
                    </details>
                  )}
                </div>
              )}
              {admin && (
                <div className="filters" style={{ marginBottom: 0 }}>
                  <button className="btn" disabled={preview.isPending} onClick={() => preview.mutate(m.id)}>
                    {preview.isPending && preview.variables === m.id ? 'Previewing…' : p ? 'Preview again' : 'Preview'}
                  </button>
                  <button className="btn primary" disabled={!p?.compiled || approve.isPending} onClick={() => approve.mutate(m.id)}
                          title={p?.compiled ? undefined : 'Preview the mapping first'}>
                    Approve and go live
                  </button>
                </div>
              )}
            </div>
          </div>
        )
      })}

      <div className="card">
        <div className="card-head"><h2>Live makers</h2></div>
        {mappings.isError ? (
          <div className="error-box">Could not load makers: {(mappings.error as Error).message}</div>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Maker</th><th>Mapping</th><th>Approved by</th><th>In the pipeline</th><th>Parked events</th></tr></thead>
              <tbody>
                {active.map((m) => {
                  const live = status.data?.active_versions[m.oem_code]
                  const replay = status.data?.replays[m.oem_code]
                  return (
                    <tr key={m.id}>
                      <td>{title(m.oem_code)}</td>
                      <td className="num">v{m.version}</td>
                      <td>{m.approved_by_email ?? (m.proposed_by === 'seed' ? 'built in' : '–')}</td>
                      <td>{live === m.version ? 'active' : status.isLoading ? '…' : live ? `v${live} (updating)` : 'activating…'}</td>
                      <td className="num">{replay
                        ? replay.state === 'DONE' ? `${(replay.replayed ?? 0).toLocaleString()} replayed`
                          : replay.state === 'RUNNING' ? `replaying… ${(replay.replayed ?? 0).toLocaleString()}` : replay.state
                        : '–'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <FeedHealth admin={admin} />
      {error && <div className="toast" role="alert" onClick={() => setError(null)}>{error}</div>}
    </>
  )
}

interface DriftRow {
  oem_code: string
  field: string
  psi: number
  baseline_mean: number | null
  current_mean: number | null
  status: 'OK' | 'WATCH' | 'DRIFT'
  hint: string | null
}

const FIELD_LABEL: Record<string, string> = {
  speed_kmh: 'Speed', rpm: 'RPM', coolant_c: 'Coolant', batt_v: '12 V battery', fuel_pct: 'Fuel', soc_pct: 'EV charge',
}

/** Feed drift: a maker's data still valid but distributed differently from its own recent past. */
function FeedHealth({ admin }: { admin: boolean }) {
  const qc = useQueryClient()
  const drift = useQuery({
    queryKey: ['drift'],
    queryFn: () => api<{ checked_at: string | null; items: DriftRow[] }>('/v1/oem-mappings/drift'),
    refetchInterval: 20_000,
  })
  const firmware = useMutation({
    mutationFn: (mph: boolean) => api<{ speedInMph: boolean }>(`/v1/ops/firmware?mph=${mph}`, { method: 'POST' }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ['drift'] }),
  })
  const items = drift.data?.items ?? []
  const makers = [...new Set(items.map((i) => i.oem_code))]
  const fields = Object.keys(FIELD_LABEL).filter((f) => items.some((i) => i.field === f))
  const flagged = items.filter((i) => i.status !== 'OK')

  return (
    <div className="card" style={{ marginTop: 16 }}>
      <div className="card-head">
        <h2>Feed health</h2>
        <span className="muted" style={{ fontSize: 12.5 }}>
          {drift.data?.checked_at ? `each maker's last 5 minutes vs the 40 minutes before; speed and rpm while moving; makers with a gap in that history are not judged · checked ${relTime(drift.data.checked_at)}` : ' '}
        </span>
      </div>
      {!items.length ? <div className="empty">{drift.isLoading ? 'Loading…' : 'No drift check has run yet.'}</div> : (
        <>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Maker</th>{fields.map((f) => <th key={f} style={{ textAlign: 'right' }}>{FIELD_LABEL[f]}</th>)}</tr></thead>
              <tbody>{makers.map((m) => (
                <tr key={m}>
                  <td>{title(m)}</td>
                  {fields.map((f) => {
                    const c = items.find((i) => i.oem_code === m && i.field === f)
                    return (
                      <td key={f} className="num" style={{ textAlign: 'right' }}>
                        {!c ? '–' : c.status === 'OK' ? <span className="muted">stable</span>
                          : <span className="pill" style={c.status === 'DRIFT' ? { background: 'var(--status-serious)', color: '#fff' } : undefined}>
                              {c.status.toLowerCase()} · {c.psi.toFixed(2)}</span>}
                      </td>
                    )
                  })}
                </tr>
              ))}</tbody>
            </table>
          </div>
          {flagged.length > 0 && (
            <ul style={{ margin: 0, padding: '10px 16px 0 32px', fontSize: 13 }}>
              {flagged.map((f) => (
                <li key={`${f.oem_code}-${f.field}`}>
                  <strong>{title(f.oem_code)} {FIELD_LABEL[f.field]?.toLowerCase() ?? f.field}</strong>: average {f.baseline_mean} → {f.current_mean}
                  {f.hint && <span className="secondary"> ({f.hint})</span>}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      <div style={{ padding: '10px 16px 14px', display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <span className="muted" style={{ fontSize: 12.5, flex: 1, minWidth: 260 }}>
          Values that pass validation can still be wrong: a firmware update sending mph as km/h raises no alert but
          skews every trip and the failure model's inputs. A maker is flagged only when it moves differently from the
          other makers (population stability index above 0.25), so a fleet-wide change such as time of day is not blamed on one feed.
        </span>
        {admin && (
          <span style={{ display: 'flex', gap: 8 }}>
            <button className="btn small" disabled={firmware.isPending} onClick={() => firmware.mutate(true)}>Demo: Aurora firmware bug</button>
            <button className="btn small" disabled={firmware.isPending} onClick={() => firmware.mutate(false)}>Fix it</button>
          </span>
        )}
      </div>
    </div>
  )
}
