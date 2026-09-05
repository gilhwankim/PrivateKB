import type { SVGProps } from 'react'

type IconProps = SVGProps<SVGSVGElement>

const defaults = {
  width: 20,
  height: 20,
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
}

export function HomeIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="m3 11 9-8 9 8"/><path d="M5 10v10h14V10"/><path d="M9 20v-6h6v6"/></svg>
}

export function DocumentIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M6 2h8l4 4v16H6z"/><path d="M14 2v5h5"/><path d="M9 13h6M9 17h6"/></svg>
}

export function FolderIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M3 7V5a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/></svg>
}

export function LayersIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="m12 3-8 4.5 8 4.5 8-4.5Z"/><path d="m4 12 8 4.5 8-4.5"/><path d="m4 16.5 8 4.5 8-4.5"/></svg>
}

export function LockIcon(props: IconProps) {
  return <svg {...defaults} {...props}><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/></svg>
}

export function SearchIcon(props: IconProps) {
  return <svg {...defaults} {...props}><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></svg>
}

export function ChatIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M21 12a8 8 0 0 1-8 8H6l-4 2 1.5-5A9 9 0 1 1 21 12Z"/><path d="M8 12h.01M12 12h.01M16 12h.01"/></svg>
}

export function SettingsIcon(props: IconProps) {
  return <svg {...defaults} {...props}><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1v.1H9.6V21a1.7 1.7 0 0 0-1.1-1.6 1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.1 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1-.4h-.1V9.6h.1a1.7 1.7 0 0 0 1.6-1.1 1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 8.5 4.1a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1v-.1h4v.1A1.7 1.7 0 0 0 15 4.1a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 8.5a1.7 1.7 0 0 0 .6 1 1.7 1.7 0 0 0 1 .4h.1v4H21a1.7 1.7 0 0 0-1.6 1.1Z"/></svg>
}

export function UploadIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M12 16V4M7 9l5-5 5 5"/><path d="M5 14v5h14v-5"/></svg>
}

export function RefreshIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M6.1 8A7 7 0 0 1 18 6l2 2M18 16a7 7 0 0 1-12 2l-2-2"/></svg>
}

export function ShieldIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M12 22s8-3.5 8-10V5l-8-3-8 3v7c0 6.5 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/></svg>
}

export function SparkleIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2Z"/><path d="m18 14 .7 2.3L21 17l-2.3.7L18 20l-.7-2.3L15 17l2.3-.7Z"/></svg>
}

export function ChevronIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="m9 18 6-6-6-6"/></svg>
}

export function CloseIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="M6 6l12 12M18 6 6 18"/></svg>
}

export function CheckIcon(props: IconProps) {
  return <svg {...defaults} {...props}><path d="m6.5 12.5 3.4 3.4 7.6-8"/></svg>
}
