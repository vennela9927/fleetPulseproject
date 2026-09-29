import { useMemo } from 'react'
import {
  Bar, BarChart, CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'

/** Recharts writes colours as SVG attributes, where CSS var() is not reliably resolved. */
function useTokens() {
  return useMemo(() => {
    const s = getComputedStyle(document.documentElement)
    const v = (n: string) => s.getPropertyValue(n).trim()
    return {
      series: v('--series-1'), grid: v('--grid'), axis: v('--axis'), muted: v('--text-muted'),
      secondary: v('--text-secondary'), surface: v('--surface'), critical: v('--status-critical'),
    }
  }, [])
}

export interface SeriesPoint {
  t: number
  v: number | null
}

interface ChartProps {
  title: string
  unit: string
  data: SeriesPoint[]
  kind: 'line' | 'bar'
  /** Formats the x value for ticks and the tooltip. */
  formatX: (t: number) => string
  digits?: number
  /** `lower`: values should stay above it (label drawn above the line, clear of the x axis). */
  threshold?: { value: number; label: string; lower?: boolean }
  domain?: [number | 'auto' | 'dataMin' | 'dataMax', number | 'auto' | 'dataMin' | 'dataMax']
  empty?: string
  /** Shown instead of a chart of zeros, e.g. "No fault codes in this period." */
  allZero?: string
}

/** One measure per chart, one axis: a single series needs no legend, the title names it. */
export function SeriesChart({ title, unit, data, kind, formatX, digits = 0, threshold, domain, empty, allZero }: ChartProps) {
  const c = useTokens()
  const hasData = data.some((d) => d.v !== null)
  const zeros = hasData && data.every((d) => d.v === null || d.v === 0)
  // Few points (daily) get markers; dense per-minute series stay a plain line.
  const markers = data.length <= 31
  const fmt = (v: number) => `${v.toLocaleString(undefined, { maximumFractionDigits: digits })}${unit ? ` ${unit}` : ''}`

  const common = {
    data,
    margin: { top: 8, right: threshold ? 12 : 8, bottom: 0, left: 0 },
  }
  const axes = (
    <>
      <CartesianGrid vertical={false} stroke={c.grid} strokeWidth={1} />
      <XAxis dataKey="t" type={kind === 'line' ? 'number' : 'category'} domain={['dataMin', 'dataMax']} scale={kind === 'line' ? 'time' : 'auto'}
             tickFormatter={formatX} tick={{ fill: c.muted, fontSize: 11 }} stroke={c.axis} tickLine={false} minTickGap={24} />
      {/* Bars always grow from zero; lines may zoom, but a threshold is kept in view (below). */}
      <YAxis width={44} tick={{ fill: c.muted, fontSize: 11 }} stroke="none" tickLine={false}
             domain={domain ?? (kind === 'bar' ? [0, 'auto'] : ['auto', 'auto'])} allowDecimals={digits > 0}
             tickFormatter={(v: number) => v.toLocaleString(undefined, { maximumFractionDigits: digits })} />
      <Tooltip cursor={kind === 'line' ? { stroke: c.axis, strokeWidth: 1 } : { fill: c.grid, opacity: 0.5 }}
               isAnimationActive={false}
               content={({ active, payload, label }) =>
                 active && payload?.length && payload[0].value !== null && payload[0].value !== undefined ? (
                   <div className="chart-tip">
                     <div className="t">{formatX(Number(label))}</div>
                     <div><strong>{fmt(Number(payload[0].value))}</strong></div>
                   </div>
                 ) : null} />
      {threshold && (
        <ReferenceLine y={threshold.value} stroke={c.critical} strokeWidth={1} ifOverflow="extendDomain"
                       label={{ value: threshold.label, position: threshold.lower ? 'insideBottomRight' : 'insideTopRight',
                               fill: c.secondary, fontSize: 11 }} />
      )}
    </>
  )

  return (
    <div className="card">
      <div className="card-head"><h2>{title}</h2></div>
      <div className="card-body" style={{ height: 220, paddingTop: 8 }}>
        {!hasData ? (
          <div className="empty">{empty ?? 'No data in this period.'}</div>
        ) : zeros && allZero ? (
          <div className="empty">{allZero}</div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            {kind === 'line' ? (
              <LineChart {...common}>
                {axes}
                <Line dataKey="v" type="linear" stroke={c.series} strokeWidth={2} connectNulls={false}
                      dot={markers ? { r: 4, fill: c.series, stroke: c.surface, strokeWidth: 2 } : false}
                      activeDot={{ r: 4, fill: c.series, stroke: c.surface, strokeWidth: 2 }} isAnimationActive={false} />
              </LineChart>
            ) : (
              <BarChart {...common} barCategoryGap="30%">
                {axes}
                <Bar dataKey="v" fill={c.series} maxBarSize={24} radius={[4, 4, 0, 0]} isAnimationActive={false} />
              </BarChart>
            )}
          </ResponsiveContainer>
        )}
      </div>
    </div>
  )
}
