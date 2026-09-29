import { useQuery } from '@tanstack/react-query'
import { lazy, Suspense } from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { api, type Me } from './api'
import { logout } from './auth'
import { IconBell, IconFlask, IconLogout, IconOverview, IconTruck } from './components/icons'

// Each page is its own chunk: the map (MapLibre) and charts (Recharts) load only where used.
const Overview = lazy(() => import('./pages/Overview').then((m) => ({ default: m.Overview })))
const Alerts = lazy(() => import('./pages/Alerts').then((m) => ({ default: m.Alerts })))
const Vehicles = lazy(() => import('./pages/Vehicles').then((m) => ({ default: m.Vehicles })))
const VehicleByVin = lazy(() => import('./pages/Vehicles').then((m) => ({ default: m.VehicleByVin })))
const VehicleDetail = lazy(() => import('./pages/VehicleDetail').then((m) => ({ default: m.VehicleDetail })))
const Chaos = lazy(() => import('./pages/Chaos').then((m) => ({ default: m.Chaos })))

export function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: () => api<Me>('/v1/me'), staleTime: Infinity })

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><img src="/favicon.svg" alt="" />FleetPulse</div>
        <nav className="nav" aria-label="Main">
          <NavLink to="/" end><IconOverview />Overview</NavLink>
          <NavLink to="/alerts"><IconBell />Alerts</NavLink>
          <NavLink to="/vehicles"><IconTruck />Vehicles</NavLink>
          <NavLink to="/chaos"><IconFlask />Chaos</NavLink>
        </nav>
        <div className="sidebar-foot">
          <div className="tenant">{me.data?.tenant_name ?? ' '}</div>
          <div className="muted">{me.data ? `${me.data.username} · ${me.data.roles.includes('fleet_manager') ? 'manager' : 'viewer'}` : ' '}</div>
          <button className="btn small" style={{ marginTop: 8, display: 'inline-flex', gap: 6, alignItems: 'center' }} onClick={logout}>
            <IconLogout style={{ width: 13, height: 13 }} />Sign out
          </button>
        </div>
      </aside>
      <main className="main">
        <Suspense fallback={<div className="empty">Loading…</div>}>
        <Routes>
          <Route path="/" element={<Overview />} />
          <Route path="/alerts" element={<Alerts />} />
          <Route path="/vehicles" element={<Vehicles />} />
          <Route path="/vehicles/by-vin/:vin" element={<VehicleByVin />} />
          <Route path="/vehicles/:id" element={<VehicleDetail />} />
          <Route path="/chaos" element={<Chaos />} />
          <Route path="*" element={<div className="empty">Page not found.</div>} />
        </Routes>
        </Suspense>
      </main>
    </div>
  )
}
