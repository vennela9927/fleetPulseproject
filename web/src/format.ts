const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })
const whole = new Intl.NumberFormat('en')

/** 1,284 / 12.9K: exact below 10,000, compact above. */
export function count(n: number | null | undefined): string {
  if (n === null || n === undefined) return '–'
  return Math.abs(n) < 10_000 ? whole.format(n) : compact.format(n)
}

export function fixed(n: number | null | undefined, digits = 1, unit = ''): string {
  return n === null || n === undefined ? '–' : `${n.toFixed(digits)}${unit}`
}

export function relTime(iso: string | number, now = Date.now()): string {
  const t = typeof iso === 'number' ? iso : Date.parse(iso)
  const s = Math.round((now - t) / 1000)
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  if (s < 3600) return `${Math.floor(s / 60)}m ago`
  if (s < 86_400) return `${Math.floor(s / 3600)}h ago`
  return `${Math.floor(s / 86_400)}d ago`
}

export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

const RULES: Record<string, string> = {
  ENGINE_OVERHEAT: 'Engine overheat',
  CRITICAL_DTC: 'Critical fault code',
  LOW_12V_BATTERY: 'Low 12 V battery',
  HARSH_DRIVING: 'Harsh driving',
  EV_LOW_SOC: 'EV low charge',
  EXCESSIVE_IDLING: 'Excessive idling',
  BATTERY_DEGRADATION: 'Battery degradation',
  HIGH_FAILURE_RISK: 'High failure risk',
  UNAPPROVED_CLUSTER: 'Unapproved parking cluster',
}

export function ruleLabel(code: string): string {
  return RULES[code] ?? code.toLowerCase().replace(/_/g, ' ')
}

/** One line describing why an alert fired, from its details. */
export function alertDetail(rule: string, d: Record<string, unknown>): string {
  const n = (k: string) => (typeof d[k] === 'number' ? (d[k] as number) : undefined)
  switch (rule) {
    case 'ENGINE_OVERHEAT': return `Coolant ${n('coolant_c')} °C for ${n('above_for_seconds')} s`
    case 'CRITICAL_DTC': return `Fault code ${String(d.dtc ?? '')}`
    case 'LOW_12V_BATTERY': return `${n('batt_v')} V with ignition off`
    case 'HARSH_DRIVING': return `${n('events')} events in ${Math.round((n('window_seconds') ?? 0) / 60)} min`
    case 'EV_LOW_SOC': return `${n('soc_pct')}% charge while driving`
    case 'EXCESSIVE_IDLING': return `Idling ${Math.round((n('idle_seconds') ?? 0) / 60)} min`
    case 'HIGH_FAILURE_RISK': {
      const saved = n('est_cost_avoided_usd')
      return `${Math.round((n('probability') ?? 0) * 100)}% risk of a ${String(d.component ?? 'component').toLowerCase()} failure within 7 days`
        + (saved ? ` · about $${count(Math.round(saved))} saved if serviced` : '')
    }
    default: return ''
  }
}
