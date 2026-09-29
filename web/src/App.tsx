import { useQuery } from '@tanstack/react-query'
import { lazy, Suspense } from 'react'
import { NavLink, Route, Routes } from 'react-router-dom'
import { api, type Me } from './api'
import { hasRole, logout } from './auth'
import { IconBell, IconCalendar, IconFlask, IconGauge, IconLogout, IconOverview, IconPlug, IconSpark, IconTruck } from './components/icons'

// Each page is its own chunk: the map (MapLibre) and charts (Recharts) load only where used.
const Overview = lazy(() => import('./pages/Overview').then((m) => ({ default: m.Overview })))
const Alerts = lazy(() => import('./pages/Alerts').then((m) => ({ default: m.Alerts })))
const Vehicles = lazy(() => import('./pages/Vehicles').then((m) => ({ default: m.Vehicles })))
const VehicleByVin = lazy(() => import('./pages/Vehicles').then((m) => ({ default: m.VehicleByVin })))
const VehicleDetail = lazy(() => import('./pages/VehicleDetail').then((m) => ({ default: m.VehicleDetail })))
const Chaos = lazy(() => import('./pages/Chaos').then((m) => ({ default: m.Chaos })))
const Makers = lazy(() => import('./pages/Makers').then((m) => ({ default: m.Makers })))
const AtRisk = lazy(() => import('./pages/AtRisk').then((m) => ({ default: m.AtRisk })))
const Copilot = lazy(() => import('./pages/Copilot').then((m) => ({ default: m.Copilot })))
const ServicePlan = lazy(() => import('./pages/ServicePlan').then((m) => ({ default: m.ServicePlan })))

export function App() {
  const me = useQuery({ queryKey: ['me'], queryFn: () => api<Me>('/v1/me'), staleTime: Infinity })
  // Platform operators have no fleet of their own: they only see vehicle-maker onboarding.
  const fleetUser = hasRole('fleet_manager') || hasRole('fleet_viewer')
  const roleLabel = hasRole('fleet_manager') ? 'manager' : fleetUser ? 'viewer' : 'platform admin'

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand"><img src="/favicon.svg" alt="" />FleetPulse</div>
        <nav className="nav" aria-label="Main">
          {fleetUser && <>
            <NavLink to="/" end><IconOverview />Overview</NavLink>
            <NavLink to="/alerts"><IconBell />Alerts</NavLink>
            <NavLink to="/risk"><IconGauge />At risk</NavLink>
            <NavLink to="/plan"><IconCalendar />Service plan</NavLink>
            <NavLink to="/copilot"><IconSpark />Copilot</NavLink>
            <NavLink to="/vehicles"><IconTruck />Vehicles</NavLink>
            <NavLink to="/chaos"><IconFlask />Chaos</NavLink>
          </>}
          <NavLink to="/makers" end={!fleetUser}><IconPlug />Vehicle makers</NavLink>
        </nav>
        <div className="sidebar-foot">
          <div className="tenant">{me.data?.tenant_name ?? ' '}</div>
          <div className="muted">{me.data ? `${me.data.username} · ${roleLabel}` : ' '}</div>
          <button className="btn small" style={{ marginTop: 8, display: 'inline-flex', gap: 6, alignItems: 'center' }} onClick={logout}>
            <IconLogout style={{ width: 13, height: 13 }} />Sign out
          </button>
        </div>
      </aside>
      <main className="main">
        <Suspense fallback={<div className="empty">Loading…</div>}>
        <Routes>
          <Route path="/" element={fleetUser ? <Overview /> : <Makers />} />
          <Route path="/makers" element={<Makers />} />
          <Route path="/risk" element={<AtRisk />} />
          <Route path="/plan" element={<ServicePlan />} />
          <Route path="/copilot" element={<Copilot />} />
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
