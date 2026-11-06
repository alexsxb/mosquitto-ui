import { useEffect, useRef, useState } from "react"

const HEIGHT = 170
const MARGIN = { top: 10, right: 12, bottom: 22, left: 48 }
const TICKS = 4
const POINT_RADIUS = 4
/** A longer time does not fit a clock: both ends could show about the same hour. */
const CLOCK_ONLY_MS = 12 * 3_600_000
const KIBI = 1024
/** More than this many points do not fit a chart; the first and last of each column keep its shape without a heavy path. */
const MAX_DRAWN = 1000

function niceStep(range: number, binary: boolean) {
  const unit = binary ? KIBI ** Math.max(Math.floor(Math.log(range / TICKS) / Math.log(KIBI)), 0) : 1
  const rough = range / TICKS / unit
  const power = 10 ** Math.floor(Math.log10(rough))
  const fraction = rough / power
  return (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10) * power * unit
}

function scaleOf(series: [number, number][], zeroBased: boolean, binary: boolean) {
  const values = series.map(([, value]) => value)
  let min = zeroBased ? 0 : Math.min(...values.slice(0, 100000))
  let max = Math.max(...values.slice(0, 100000))
  if (zeroBased && max === 0) {
    max = 1
  }
  if (min === max) {
    const pad = Math.abs(min) * 0.1 || 1
    min -= pad
    max += pad
  }
  const step = niceStep(max - min, binary)
  const low = Math.floor(min / step) * step
  const high = Math.ceil(max / step) * step
  const ticks: number[] = []
  for (let tick = low; tick <= high + step / 2; tick += step) {
    ticks.push(Number(tick.toPrecision(10)))
  }
  return { low, high, ticks }
}

function thin(series: [number, number][]) {
  if (series.length <= MAX_DRAWN) {
    return series
  }
  const every = Math.ceil(series.length / MAX_DRAWN)
  const drawn = series.filter((_, index) => index % every === 0)
  drawn.push(series[series.length - 1])
  return drawn
}

function compact(value: number, locale?: string) {
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 2, notation: Math.abs(value) >= 10000 ? "compact" : "standard" }).format(value)
}

interface LineChartProps {
  series: [number, number][]
  label: string
  locale?: string
  height?: number
  /** For amounts, where the distance to nothing matters. */
  zeroBased?: boolean
  /** Points further apart than this, in ms, are not joined: nothing is known about the time between them. */
  maxGap?: number
  /** Bytes: the lines of the axis fall on whole KB and MB. */
  binary?: boolean
  format?: (value: number) => string
  /** After the value next to the pointer, as the axis has no room for it. */
  unit?: string
}

/** One series over time. Hover or arrow keys read a value; the values also stay in the history next to it. */
export function LineChart({ series, label, locale, height = HEIGHT, zeroBased = false, maxGap = Infinity, binary = false, format = (value) => compact(value, locale), unit = "" }: LineChartProps) {
  const box = useRef<HTMLDivElement>(null)
  const [width, setWidth] = useState(0)
  const [active, setActive] = useState<number | null>(null)

  useEffect(() => {
    const element = box.current
    if (!element) {
      return undefined
    }
    const observer = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])

  const drawn = thin(series)
  const { low, high, ticks } = scaleOf(drawn, zeroBased, binary)
  const first = drawn[0][0]
  const last = drawn[drawn.length - 1][0]
  const plotWidth = Math.max(width - MARGIN.left - MARGIN.right, 1)
  const plotHeight = height - MARGIN.top - MARGIN.bottom
  const x = (time: number) => MARGIN.left + (last === first ? plotWidth : ((time - first) / (last - first)) * plotWidth)
  const y = (value: number) => MARGIN.top + plotHeight - ((value - low) / (high - low)) * plotHeight
  const clock = (time: number) =>
    last - first > CLOCK_ONLY_MS ? new Intl.DateTimeFormat(locale, { dateStyle: "short", timeStyle: "short" }).format(time) : new Date(time).toLocaleTimeString(locale)
  const path = drawn.map(([time, value], index) => `${index === 0 || time - drawn[index - 1][0] > maxGap ? "M" : "L"}${x(time).toFixed(1)},${y(value).toFixed(1)}`).join("")
  const point = active === null ? null : drawn[Math.min(active, drawn.length - 1)]

  function nearest(clientX: number) {
    const left = box.current?.getBoundingClientRect().left ?? 0
    let best = 0
    drawn.forEach(([time], index) => {
      if (Math.abs(x(time) + left - clientX) < Math.abs(x(drawn[best][0]) + left - clientX)) {
        best = index
      }
    })
    setActive(best)
  }

  function key(event: React.KeyboardEvent) {
    const current = active ?? drawn.length - 1
    if (event.key === "ArrowLeft") {
      setActive(Math.max(current - 1, 0))
    } else if (event.key === "ArrowRight") {
      setActive(Math.min(current + 1, drawn.length - 1))
    }
  }

  return (
    <div
      ref={box}
      role="img"
      aria-label={label}
      tabIndex={0}
      className="relative rounded outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
      onPointerMove={(event) => nearest(event.clientX)}
      onPointerLeave={() => setActive(null)}
      onBlur={() => setActive(null)}
      onKeyDown={key}
    >
      <svg width="100%" height={height}>
        {width > 0 && (
          <>
            {ticks.map((tick) => (
              <g key={tick}>
                <line x1={MARGIN.left} x2={MARGIN.left + plotWidth} y1={y(tick)} y2={y(tick)} stroke="var(--border)" strokeWidth={1} />
                <text x={MARGIN.left - 6} y={y(tick)} dy="0.32em" textAnchor="end" fontSize={11} fill="var(--muted-foreground)">
                  {format(tick)}
                </text>
              </g>
            ))}
            <text x={MARGIN.left} y={height - 6} fontSize={11} fill="var(--muted-foreground)">
              {clock(first)}
            </text>
            <text x={MARGIN.left + plotWidth} y={height - 6} textAnchor="end" fontSize={11} fill="var(--muted-foreground)">
              {clock(last)}
            </text>
            <path d={path} fill="none" stroke="var(--primary)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
            {drawn.length === 1 && <circle cx={x(first)} cy={y(drawn[0][1])} r={POINT_RADIUS} fill="var(--primary)" />}
            {point && (
              <>
                <line x1={x(point[0])} x2={x(point[0])} y1={MARGIN.top} y2={MARGIN.top + plotHeight} stroke="var(--muted-foreground)" strokeWidth={1} />
                <circle cx={x(point[0])} cy={y(point[1])} r={POINT_RADIUS} fill="var(--primary)" stroke="var(--card)" strokeWidth={2} />
              </>
            )}
          </>
        )}
      </svg>
      {point && (
        <div
          className="pointer-events-none absolute top-1 rounded border bg-popover px-2 py-1 text-xs whitespace-nowrap text-popover-foreground shadow-sm"
          style={x(point[0]) > width / 2 ? { right: width - x(point[0]) + 8 } : { left: x(point[0]) + 8 }}
        >
          <span className="font-semibold">
            {format(point[1])}
            {unit}
          </span> <span className="text-muted-foreground">{clock(point[0])}</span>
        </div>
      )}
    </div>
  )
}
