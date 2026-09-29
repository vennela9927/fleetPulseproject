import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// /api is proxied to the FastAPI service, so the browser talks to one origin (no CORS in
// development) and production can do the same with a reverse proxy. 127.0.0.1 rather than
// localhost: on Windows, localhost tries IPv6 first and costs ~200 ms per connection.
export default defineConfig({
  plugins: [react()],
  // MapLibre loads its web worker relative to its own module; pre-bundling moves the module
  // away from the worker file and breaks that URL, so serve it from node_modules as is.
  optimizeDeps: { exclude: ['maplibre-gl'] },
  // Pages are split into chunks; the largest is the map page, which is MapLibre itself (~280 KB gzipped).
  build: { chunkSizeWarningLimit: 1100 },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: process.env.API_URL ?? 'http://127.0.0.1:8000',
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
  },
})
