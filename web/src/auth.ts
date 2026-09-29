import Keycloak from 'keycloak-js'

/**
 * OIDC login against the FleetPulse realm: authorization code flow with PKCE, tokens kept in
 * memory only (never localStorage, where any injected script could read them).
 */
export const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8080',
  realm: 'fleetpulse',
  clientId: 'fleetpulse-web',
})

export async function initAuth(): Promise<void> {
  await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false })
}

/** A token valid for at least 30 more seconds, refreshed if needed. */
export async function accessToken(): Promise<string> {
  try {
    await keycloak.updateToken(30)
  } catch {
    await keycloak.login()
  }
  return keycloak.token as string
}

export function hasRole(role: 'fleet_manager' | 'fleet_viewer' | 'platform_admin'): boolean {
  return keycloak.hasRealmRole(role)
}

export function logout(): void {
  void keycloak.logout({ redirectUri: window.location.origin })
}
