import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { ApiError } from './api'
import { App } from './App'
import { initAuth } from './auth'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retry transient failures, but not client errors (a 404 or 403 will not change).
      retry: (n, e) => n < 3 && !(e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 429),
      refetchOnWindowFocus: false,
    },
  },
})

const root = createRoot(document.getElementById('root')!)

// Log in before rendering anything, once (StrictMode would otherwise start two logins).
initAuth()
  .then(() => {
    root.render(
      <StrictMode>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </QueryClientProvider>
      </StrictMode>,
    )
  })
  .catch((e: unknown) => {
    root.render(
      <div className="card error-box" style={{ margin: 40 }}>
        Could not reach the sign-in service. Is Keycloak running on port 8080?
        <div className="muted" style={{ marginTop: 8 }}>{String(e)}</div>
      </div>,
    )
  })
