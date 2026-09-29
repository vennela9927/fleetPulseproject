import { useState } from 'react'

/** The cost of one inspection, set by the fleet manager on the At risk page and used by the
 * service plan. Remembered per browser as a convenience; the API defaults to $150. */
export const DEFAULT_INSPECTION_COST = 150
const KEY = 'fleetpulse.inspectionCost'

function read(): number {
  try {
    const v = Number(localStorage.getItem(KEY))
    return v >= 10 && v <= 2000 ? v : DEFAULT_INSPECTION_COST
  } catch {
    return DEFAULT_INSPECTION_COST
  }
}

export function useInspectionCost(): [number, (c: number) => void] {
  const [cost, setCost] = useState(read)
  return [cost, (c: number) => {
    setCost(c)
    try { localStorage.setItem(KEY, String(c)) } catch { /* private mode: keep it for this page only */ }
  }]
}
