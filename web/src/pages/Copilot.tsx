import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { hasRole } from '../auth'
import { dateTime, relTime } from '../format'

interface Proposal {
  action_id: number
  tool: string
  status: string
  reason: string
  // book_service
  vehicle_id?: number
  vin?: string
  depot?: string
  scheduled_for?: string
  // book_service_plan
  vehicles?: number
  days?: string[]
  expected_net_saving_usd?: number
  too_risky_to_wait?: number
}

interface ChatResponse {
  conversation_id: string
  reply: string
  engine: string
  proposals: Proposal[]
  flags: string[]
  tool_calls: { name: string; args: Record<string, unknown> }[]
}

interface Action {
  id: number
  tool: string
  arguments: Omit<Proposal, 'action_id' | 'tool' | 'status' | 'depot_id'> & { depot_id?: number }
  rationale: string | null
  status: 'PROPOSED' | 'EXECUTED' | 'REJECTED' | 'FAILED'
  created_at: string
  requested_by_email: string | null
}

type Message =
  | { role: 'user'; text: string }
  | { role: 'assistant'; text: string; engine: string; proposals: Proposal[]; flags: string[]; tools: string[] }
  | { role: 'error'; text: string }

const SUGGESTIONS = [
  'Which vehicles are most likely to break down this week?',
  'What should our workshops do in the next 3 days?',
  'Which parts should we order for next week?',
  'Summarise my fleet',
]

/** Bold and bullet lists from the model's reply, as React elements: model output is never HTML. */
function Rich({ text }: { text: string }) {
  const inline = (s: string): ReactNode[] =>
    s.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) =>
      part.startsWith('**') && part.endsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong>
        : part.length > 1 && part.startsWith('`') && part.endsWith('`') ? <code key={i} className="mono">{part.slice(1, -1)}</code>
        : <Fragment key={i}>{part}</Fragment>)
  const blocks: ReactNode[] = []
  let bullets: string[] = []
  const flush = () => {
    if (bullets.length) blocks.push(<ul key={blocks.length} style={{ margin: '4px 0', paddingLeft: 20 }}>{bullets.map((b, i) => <li key={i}>{inline(b)}</li>)}</ul>)
    bullets = []
  }
  for (const line of text.split('\n')) {
    const m = line.match(/^\s*[-*•]\s+(.*)/)
    if (m) { bullets.push(m[1]); continue }
    flush()
    if (line.trim()) blocks.push(<p key={blocks.length} style={{ margin: '4px 0' }}>{inline(line)}</p>)
  }
  flush()
  return <>{blocks}</>
}

interface AuditRow {
  id: number
  ts: string
  actor_type: 'USER' | 'AGENT' | 'SERVICE'
  actor: string | null
  action: string
  details: Record<string, unknown>
  row_hash: string
  prev_hash: string | null
}

const AUDIT_LABEL: Record<string, string> = {
  'agent.propose': 'proposed it (cannot book anything itself)',
  'agent.approve': 'approved it; the booking was made',
  'agent.reject': 'rejected it; nothing was booked',
  'agent.approve_failed': 'approved it, but it no longer fitted; nothing was booked',
}

/** The proposal's rows in the hash-chained audit log: the copilot proposing, a person deciding. */
function AuditTrail({ actionId }: { actionId: number }) {
  const q = useQuery({
    queryKey: ['copilot-audit', actionId],
    queryFn: () => api<{ items: AuditRow[] }>(`/v1/copilot/actions/${actionId}/audit`),
  })
  if (q.isLoading) return <div className="muted" style={{ fontSize: 12 }}>Loading the audit trail…</div>
  if (q.isError) return <div className="muted" style={{ fontSize: 12 }}>The audit trail is unavailable.</div>
  return (
    <ol style={{ margin: '6px 0 0', paddingLeft: 18, fontSize: 12.5 }}>
      {q.data!.items.map((r) => (
        <li key={r.id} style={{ margin: '3px 0' }}>
          <span className="num">{dateTime(r.ts)}</span> · <strong>{r.actor_type === 'AGENT' ? 'Copilot (AI)' : r.actor ?? 'a user'}</strong>{' '}
          {AUDIT_LABEL[r.action] ?? r.action}
          <div className="muted mono" style={{ fontSize: 11 }} title="This row's hash covers its content and the previous row's hash">
            #{r.id} · hash {r.row_hash.slice(0, 12)} · previous {r.prev_hash ? r.prev_hash.slice(0, 12) : 'none'}
          </div>
        </li>
      ))}
    </ol>
  )
}

function AuditToggle({ actionId, version }: { actionId: number; version: string }) {
  const [open, setOpen] = useState(false)
  return (
    <div style={{ marginTop: 6 }}>
      <button type="button" className="btn small" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? 'Hide audit trail' : 'Audit trail'}
      </button>
      {open && <AuditTrail key={version} actionId={actionId} />}
    </div>
  )
}

function ProposalCard({ p, onDecided }: { p: Proposal; onDecided: () => void }) {
  const manager = hasRole('fleet_manager')
  const [state, setState] = useState<string>(p.status)
  const [error, setError] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<string | null>(null)
  const isPlan = p.tool === 'book_service_plan'
  const decide = useMutation({
    mutationFn: (approve: boolean) =>
      api<{ status: string; booking_id?: number; bookings?: number; error?: string }>(
        `/v1/copilot/actions/${p.action_id}/${approve ? 'approve' : 'reject'}`, { method: 'POST' }),
    onSuccess: (r) => {
      setState(r.status)
      setOutcome(r.error ?? (r.bookings ? `Booked ${r.bookings} inspections.` : null))
      onDecided()
    },
    onError: (e) => setError(e instanceof ApiError && e.status === 409 ? 'Already decided by someone else.' : e.message),
  })
  return (
    <div className="card" style={{ padding: 12, marginTop: 8, background: 'var(--surface-raised)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'baseline' }}>
        <strong>Proposed: {isPlan ? 'service plan' : 'service booking'}</strong>
        <span className="pill">{state === 'PROPOSED' ? 'awaiting approval' : state.toLowerCase()}</span>
      </div>
      <div className="secondary" style={{ fontSize: 13, margin: '4px 0 8px' }}>
        {isPlan ? (
          <>
            {p.vehicles} inspections, {p.days?.map((d) => d.slice(5)).join(', ')}, expected net saving ${(p.expected_net_saving_usd ?? 0).toLocaleString()}
            {!!p.too_risky_to_wait && <div>{p.too_risky_to_wait} vehicles are too risky to wait and have no bay tomorrow.</div>}
            <div><Link to="/plan">See the plan</Link></div>
          </>
        ) : (
          <>
            <Link to={`/vehicles/${p.vehicle_id}`} className="mono">{p.vin}</Link> into {p.depot}, {dateTime(p.scheduled_for!)}
            <div>{p.reason}</div>
          </>
        )}
      </div>
      {state === 'PROPOSED' && (manager ? (
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn primary small" disabled={decide.isPending} onClick={() => decide.mutate(true)}>Approve</button>
          <button className="btn small" disabled={decide.isPending} onClick={() => decide.mutate(false)}>Reject</button>
        </div>
      ) : <div className="muted" style={{ fontSize: 12.5 }}>A fleet manager must approve this.</div>)}
      {state === 'EXECUTED' && <div style={{ fontSize: 12.5, color: 'var(--success-text)' }}>{outcome ?? 'Booked.'} The copilot proposed it; you approved it.</div>}
      {state === 'FAILED' && <div style={{ fontSize: 12.5 }}>Not booked: {outcome}</div>}
      {error && <div style={{ fontSize: 12.5 }}>{error}</div>}
      <AuditToggle actionId={p.action_id} version={state} />
    </div>
  )
}

export function Copilot() {
  const qc = useQueryClient()
  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [conversation, setConversation] = useState<string | null>(null)
  const bottom = useRef<HTMLDivElement>(null)

  const pending = useQuery({
    queryKey: ['copilot-actions'],
    queryFn: () => api<{ items: Action[] }>('/v1/copilot/actions?status=PROPOSED'),
    refetchInterval: 15_000,
  })
  const approved = useQuery({
    queryKey: ['copilot-actions', 'EXECUTED'],
    queryFn: () => api<{ items: (Action & { decided_at: string | null; decided_by_email: string | null })[] }>(
      '/v1/copilot/actions?status=EXECUTED'),
  })
  const refreshActions = () => void qc.invalidateQueries({ queryKey: ['copilot-actions'] })

  const send = useMutation({
    mutationFn: (message: string) => api<ChatResponse>('/v1/copilot/chat', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message, conversation_id: conversation }),
    }),
    onSuccess: (r) => {
      setConversation(r.conversation_id)
      setMessages((m) => [...m, { role: 'assistant', text: r.reply, engine: r.engine, proposals: r.proposals,
        flags: r.flags, tools: [...new Set(r.tool_calls.map((t) => t.name))] }])
      if (r.proposals.length) refreshActions()
    },
    onError: (e) => setMessages((m) => [...m, { role: 'error', text: e instanceof ApiError && e.status === 429
      ? `Slow down a little: try again in ${e.retryAfter ?? 1} s.` : `Something went wrong: ${e.message}` }]),
  })

  useEffect(() => { bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [messages, send.isPending])

  const ask = (text: string) => {
    const t = text.trim()
    if (!t || send.isPending) return
    setMessages((m) => [...m, { role: 'user', text: t }])
    setInput('')
    send.mutate(t)
  }

  return (
    <>
      <div className="page-head">
        <h1>Copilot</h1>
        <span className="muted">Answers from your fleet's data. It can only propose actions; a manager approves them.</span>
      </div>
      <div className="grid-2">
        <div className="card" style={{ display: 'flex', flexDirection: 'column', minHeight: 560 }}>
          <div style={{ flex: 1, overflowY: 'auto', padding: 16, maxHeight: 620 }} aria-live="polite">
            {!messages.length && (
              <div>
                <p className="secondary" style={{ marginTop: 0 }}>Ask about breakdown risk, a vehicle, or alerts. Try:</p>
                <div className="filters">
                  {SUGGESTIONS.map((s) => <button key={s} className="btn small" onClick={() => ask(s)}>{s}</button>)}
                </div>
              </div>
            )}
            {messages.map((m, i) => m.role === 'user' ? (
              <div key={i} style={{ display: 'flex', justifyContent: 'flex-end', margin: '8px 0' }}>
                <div style={{ background: 'var(--accent)', color: '#fff', padding: '8px 12px', borderRadius: 12, maxWidth: '80%' }}>{m.text}</div>
              </div>
            ) : m.role === 'error' ? (
              <div key={i} className="card" style={{ padding: 10, margin: '8px 0' }}>{m.text}</div>
            ) : (
              <div key={i} style={{ margin: '8px 0', maxWidth: '92%' }}>
                {m.flags.length > 0 && (
                  <div className="card" role="alert" style={{ padding: 10, marginBottom: 8, borderColor: 'var(--status-serious)' }}>
                    <strong>Suspicious instructions found in your data, and ignored.</strong>
                    <div className="muted mono" style={{ fontSize: 12, marginTop: 4 }}>{m.flags[0]}</div>
                  </div>
                )}
                <div style={{ background: 'var(--hover)', padding: '8px 12px', borderRadius: 12 }}><Rich text={m.text} /></div>
                {m.proposals.map((p) => <ProposalCard key={p.action_id} p={p} onDecided={refreshActions} />)}
                <div className="muted" style={{ fontSize: 11.5, marginTop: 4 }}>
                  {m.engine.startsWith('gemini') ? `Gemini (${m.engine.slice(7)})` : 'Rule-based assistant (no language model)'}
                  {m.tools.length > 0 && <> · looked up: {m.tools.join(', ').replace(/_/g, ' ')}</>}
                </div>
              </div>
            ))}
            {send.isPending && <div className="muted" style={{ margin: '8px 0' }}>Thinking…</div>}
            <div ref={bottom} />
          </div>
          <form style={{ display: 'flex', gap: 8, padding: 12, borderTop: '1px solid var(--border)' }}
                onSubmit={(e) => { e.preventDefault(); ask(input) }}>
            <input type="text" value={input} onChange={(e) => setInput(e.target.value)} maxLength={2000}
                   placeholder="Ask about your fleet…" aria-label="Message" style={{ flex: 1 }} />
            <button className="btn primary" disabled={!input.trim() || send.isPending}>Send</button>
            {messages.length > 0 && <button type="button" className="btn" onClick={() => { setMessages([]); setConversation(null) }}>New chat</button>}
          </form>
        </div>

        <div className="card">
          <div className="card-head"><h2>Waiting for approval</h2></div>
          {!pending.data?.items.length ? (
            <div className="empty">{pending.isLoading ? 'Loading…' : 'Nothing to approve.'}</div>
          ) : (
            <ul className="feed">
              {pending.data.items.map((a) => (
                <li key={a.id}>
                  {a.tool === 'book_service_plan' ? (
                    <>
                      <strong>Service plan</strong>
                      <div className="secondary" style={{ fontSize: 12.5 }}>{a.rationale}</div>
                      <div className="muted" style={{ fontSize: 12 }}>{relTime(a.created_at)}</div>
                    </>
                  ) : (
                    <>
                      <strong>Service booking</strong>
                      <div className="secondary" style={{ fontSize: 12.5 }}>
                        <Link to={`/vehicles/${a.arguments.vehicle_id}`} className="mono">{a.arguments.vin}</Link> · {a.arguments.depot}
                      </div>
                      <div className="muted" style={{ fontSize: 12 }}>{a.arguments.reason} · {relTime(a.created_at)}</div>
                    </>
                  )}
                </li>
              ))}
            </ul>
          )}
          <div className="card-head" style={{ borderTop: '1px solid var(--border)' }}><h2>Recently approved</h2></div>
          {!approved.data?.items.length ? (
            <div className="empty">{approved.isLoading ? 'Loading…' : 'Nothing approved yet.'}</div>
          ) : (
            <ul className="feed">
              {approved.data.items.slice(0, 5).map((a) => (
                <li key={a.id}>
                  <strong>{a.tool === 'book_service_plan' ? 'Service plan' : 'Service booking'}</strong>
                  <div className="secondary" style={{ fontSize: 12.5 }}>
                    {a.tool === 'book_service_plan' ? a.rationale : <><span className="mono">{a.arguments.vin}</span> · {a.arguments.depot}</>}
                  </div>
                  <div className="muted" style={{ fontSize: 12 }}>
                    Proposed by the copilot, approved by {a.decided_by_email ?? 'a manager'}{a.decided_at ? ` ${relTime(a.decided_at)}` : ''}
                  </div>
                  <AuditToggle actionId={a.id} version="EXECUTED" />
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </>
  )
}
