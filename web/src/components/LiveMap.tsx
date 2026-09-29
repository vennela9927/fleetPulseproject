import type { Feature, Point } from 'geojson'
import { type GeoJSONSource, LngLatBounds, Map as MapLibreMap, type MapLayerMouseEvent, NavigationControl, Popup } from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { LiveMap as LiveMapData, LiveStatus } from '../api'
import { count, fixed, relTime } from '../format'

const STATUSES: { key: LiveStatus; label: string; token: string }[] = [
  { key: 'DRIVING', label: 'Driving', token: '--series-1' },
  { key: 'IDLING', label: 'Idling', token: '--series-2' },
  { key: 'OFF', label: 'Engine off', token: '--series-3' },
]

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function prefersDark(): boolean {
  const theme = document.documentElement.dataset.theme
  if (theme) return theme === 'dark'
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

/** Keyless vector basemaps from OpenFreeMap (OpenStreetMap data), matched to the theme. */
function basemap(dark: boolean): string {
  return `https://tiles.openfreemap.org/styles/${dark ? 'dark' : 'positron'}`
}

interface Props {
  data: LiveMapData | undefined
  /** Called with the VIN of a clicked vehicle. */
  onOpen: (vin: string) => void
}

/** The tenant's vehicles on a WebGL map (50K points render smoothly), coloured by live status. */
export function LiveMap({ data, onOpen }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const map = useRef<MapLibreMap | null>(null)
  const fitted = useRef(false)
  const openRef = useRef(onOpen)
  const [hidden, setHidden] = useState<Set<LiveStatus>>(new Set())
  const [ready, setReady] = useState(false)

  useEffect(() => {
    openRef.current = onOpen
  }, [onOpen])

  const counts = useMemo(() => {
    const c: Record<string, number> = {}
    for (const r of data?.rows ?? []) c[r[3]] = (c[r[3]] ?? 0) + 1
    return c
  }, [data])

  // Create the map once.
  useEffect(() => {
    if (!container.current) return
    const m = new MapLibreMap({
      container: container.current,
      style: basemap(prefersDark()),
      center: [78.5, 17.5],
      zoom: 4.2,
      attributionControl: { compact: true },
    })
    m.addControl(new NavigationControl({ showCompass: false }), 'top-right')
    m.on('load', () => {
      m.addSource('vehicles', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      // Draw vehicles under the basemap's labels so city and road names stay readable.
      const firstLabel = m.getStyle().layers.find((l) => l.type === 'symbol')?.id
      m.addLayer({
        id: 'vehicles',
        type: 'circle',
        source: 'vehicles',
        paint: {
          'circle-radius': ['interpolate', ['linear'], ['zoom'], 4, 1.8, 9, 3.5, 13, 6],
          'circle-color': ['match', ['get', 'status'],
            'DRIVING', cssVar('--series-1'), 'IDLING', cssVar('--series-2'), cssVar('--series-3')],
          // The surface ring keeps overlapping vehicles distinguishable.
          'circle-stroke-color': cssVar('--surface'),
          'circle-stroke-width': ['interpolate', ['linear'], ['zoom'], 4, 0, 9, 1, 13, 2],
        },
      }, firstLabel)
      m.on('mouseenter', 'vehicles', () => { m.getCanvas().style.cursor = 'pointer' })
      m.on('mouseleave', 'vehicles', () => { m.getCanvas().style.cursor = '' })
      m.on('click', 'vehicles', (e: MapLayerMouseEvent) => {
        const f = e.features?.[0]
        if (!f) return
        const p = f.properties as { vin: string; status: LiveStatus; speed: number | null; ts: number | null }
        const el = document.createElement('div')
        const status = STATUSES.find((s) => s.key === p.status)?.label ?? p.status
        el.innerHTML = `<div class="mono" style="font-weight:600"></div>
          <div class="secondary" style="margin:2px 0 6px"></div>
          <button class="btn small primary">Open vehicle</button>`
        ;(el.children[0] as HTMLElement).textContent = p.vin
        ;(el.children[1] as HTMLElement).textContent =
          `${status} · ${fixed(p.speed, 0, ' km/h')} · ${p.ts ? relTime(p.ts) : 'no data'}`
        el.querySelector('button')!.addEventListener('click', () => openRef.current(p.vin))
        new Popup({ closeButton: false, offset: 8 })
          .setLngLat((f.geometry as Point).coordinates as [number, number])
          .setDOMContent(el)
          .addTo(m)
      })
      setReady(true)
    })
    map.current = m
    return () => {
      m.remove()
      map.current = null
    }
  }, [])

  // Push new positions into the source.
  useEffect(() => {
    const m = map.current
    if (!ready || !m || !data) return
    const features: Feature[] = data.rows.map(([vin, lat, lon, status, speed, ts]) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [lon, lat] },
      properties: { vin, status, speed, ts },
    }))
    ;(m.getSource('vehicles') as GeoJSONSource).setData({ type: 'FeatureCollection', features })
    if (!fitted.current && data.rows.length) {
      const b = new LngLatBounds()
      for (const r of data.rows) b.extend([r[2], r[1]])
      m.fitBounds(b, { padding: 40, maxZoom: 11, duration: 0 })
      fitted.current = true
    }
  }, [data, ready])

  useEffect(() => {
    const m = map.current
    if (!ready || !m) return
    const visible = STATUSES.map((s) => s.key).filter((k) => !hidden.has(k))
    m.setFilter('vehicles', ['in', ['get', 'status'], ['literal', visible]])
  }, [hidden, ready])

  const toggle = (k: LiveStatus) =>
    setHidden((h) => {
      const n = new Set(h)
      if (n.has(k)) n.delete(k)
      else n.add(k)
      return n
    })

  return (
    <div className="card">
      <div className="card-head">
        <h2>Live fleet</h2>
        <div className="legend" role="group" aria-label="Filter by status">
          {STATUSES.map((s) => (
            <button key={s.key} type="button" className="btn small" aria-pressed={!hidden.has(s.key)}
                    onClick={() => toggle(s.key)} style={{ opacity: hidden.has(s.key) ? 0.45 : 1 }}>
              <span className="swatch" style={{ background: `var(${s.token})`, marginRight: 6 }} />
              {s.label} <span className="muted num" style={{ marginLeft: 4 }}>{count(counts[s.key] ?? 0)}</span>
            </button>
          ))}
        </div>
      </div>
      <div className="map" ref={container} role="region" aria-label="Map of live vehicle positions" />
    </div>
  )
}
