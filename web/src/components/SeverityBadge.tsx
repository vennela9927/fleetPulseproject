import type { Severity } from '../api'
import { IconCritical, IconInfo, IconWarning } from './icons'

// Severity uses the reserved status palette, never a series color, and always an icon and a
// label, so it never relies on color alone.
const STYLE: Record<Severity, { color: string; Icon: typeof IconInfo; label: string }> = {
  CRITICAL: { color: 'var(--status-critical)', Icon: IconCritical, label: 'Critical' },
  WARNING: { color: 'var(--status-warning)', Icon: IconWarning, label: 'Warning' },
  INFO: { color: 'var(--text-muted)', Icon: IconInfo, label: 'Info' },
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  const { color, Icon, label } = STYLE[severity]
  return (
    <span className="badge">
      <Icon style={{ color }} />
      {label}
    </span>
  )
}
