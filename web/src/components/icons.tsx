import type { SVGProps } from 'react'

type P = SVGProps<SVGSVGElement>
const base = { viewBox: '0 0 16 16', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6,
  strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true }

export const IconOverview = (p: P) => <svg {...base} {...p}><rect x="2" y="2" width="5" height="5" rx="1" /><rect x="9" y="2" width="5" height="5" rx="1" /><rect x="2" y="9" width="5" height="5" rx="1" /><rect x="9" y="9" width="5" height="5" rx="1" /></svg>
export const IconBell = (p: P) => <svg {...base} {...p}><path d="M4 11V7a4 4 0 0 1 8 0v4l1 1.5H3L4 11Z" /><path d="M6.5 14h3" /></svg>
export const IconTruck = (p: P) => <svg {...base} {...p}><path d="M1.5 4h8v7h-8zM9.5 6.5h3l2 2.5V11h-5" /><circle cx="4.5" cy="12" r="1.3" /><circle cx="11.5" cy="12" r="1.3" /></svg>
export const IconFlask = (p: P) => <svg {...base} {...p}><path d="M6 2h4M7 2v4L3 13a1 1 0 0 0 .9 1.5h8.2A1 1 0 0 0 13 13L9 6V2" /><path d="M5 10h6" /></svg>
export const IconCritical = (p: P) => <svg {...base} {...p}><path d="M8 1.8 14.5 13.5H1.5L8 1.8Z" fill="currentColor" stroke="none" /><path d="M8 6v3.5M8 11.6v.1" stroke="#fff" /></svg>
export const IconWarning = (p: P) => <svg {...base} {...p}><circle cx="8" cy="8" r="6.3" fill="currentColor" stroke="none" /><path d="M8 4.8v3.7M8 10.9v.1" stroke="#0b0b0b" /></svg>
export const IconInfo = (p: P) => <svg {...base} {...p}><circle cx="8" cy="8" r="6.3" /><path d="M8 7.5v3.7M8 5v.1" /></svg>
export const IconLogout = (p: P) => <svg {...base} {...p}><path d="M6 14H3V2h3M10.5 11 14 8l-3.5-3M14 8H6" /></svg>
export const IconPlug = (p: P) => <svg {...base} {...p}><path d="M6 1.8v3M10 1.8v3M4.5 4.8h7v2.7a3.5 3.5 0 0 1-7 0V4.8ZM8 11v3.2" /></svg>
export const IconGauge = (p: P) => <svg {...base} {...p}><path d="M2.5 11a5.5 5.5 0 1 1 11 0" /><path d="M8 11l3-3.5" /></svg>
export const IconCalendar = (p: P) => <svg {...base} {...p}><rect x="2" y="3" width="12" height="11" rx="1.5" /><path d="M2 6.5h12M5.5 1.8v2.4M10.5 1.8v2.4" /></svg>
export const IconSpark =(p: P) => <svg {...base} {...p}><path d="M8 1.8 9.4 6.6 14.2 8 9.4 9.4 8 14.2 6.6 9.4 1.8 8 6.6 6.6Z" /></svg>
