# FleetPulse dashboard

React + TypeScript (Vite). Signs in with Keycloak (authorization code + PKCE), then reads the
FleetPulse API through `/api`, which Vite proxies to the FastAPI service in development.

| Page | What it shows |
|---|---|
| Overview | fleet KPIs, live map of every reporting vehicle (MapLibre, WebGL), live alert feed (SSE) |
| Alerts | filterable, paginated alerts; managers can acknowledge and resolve |
| Vehicles | searchable vehicle list |
| Vehicle | live readings, breakdown risk, open alerts, last 6 h per minute, last 14 days per day |
| Chaos | events sent vs. stored (exact reconciliation), fault injection, traffic spikes, pause |

```bash
npm install
npm run dev        # http://localhost:5173 (needs the compose stack and the API on 127.0.0.1:8000)
npm test           # unit tests (vitest)
npm run build      # production bundle in dist/
```

Demo users are listed in `services/api/README.md`. Basemap: OpenFreeMap (OpenStreetMap data),
no API key. Colours follow a validated palette: vehicle status uses categorical colours, alert
severity uses the reserved status colours, always with an icon and a label.
