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
      {error && <div className="toast" role="alert" onClick={() => setError(null)}>{error}</div>}
    </>
  )
}
