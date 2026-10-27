import type { SvgShape } from './svg-to-vector.js'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { rootDir, worktreeDir } from './config.js'
import { ensureDir, linkForkSource, step, success } from './lib.js'
import {
  fmtNum,
  parseSvgBody,
  resolveFillColor,
  resolveStrokeColor,
} from './svg-to-vector.js'

// run manually after changing src/res/launcher SVGs; output is committed

const ADAPTIVE_SIZE = 108
const FG_SAFE = 72
// settings-list icon: 24dp render in a 72dp viewport
// SettingCell — mirrors stock settings_account/settings_chat/settings_privacy
const SETTINGS_DP = 24
const SETTINGS_VIEWPORT = 72
const SETTINGS_SAFE = 80
// notification small icon: 24dp white silhouette. safe scales the glyph
// up to ~21dp (the Material small-icon target inside a 24dp canvas).
const NOTIFICATION_DP = 24
const NOTIFICATION_VIEWPORT = 24
const NOTIFICATION_SAFE = 39

const BG_COLOR = '#FF5858'

// debug badge: a small white square (only its top-left corner rounded) tucked
// into the bottom-right corner, holding a β. the white fill is framed with a
// background-coloured outline so it doesn't clash with the icon underneath.
const DEBUG_BADGE_COLOR = '#FFFFFFFF'
// β glyph from Material Design Icons (Apache-2.0), viewBox 0 0 24 24
const BETA_PATH = 'M9.23 17.59v5.53H6.88V6.72c0-1.45.43-2.59 1.28-3.44C9 2.43 10.17 2 11.61 2c1.39 0 2.46.34 3.26 1c.79.68 1.18 1.62 1.18 2.81c0 .82-.26 1.59-.78 2.3s-1.19 1.2-2.02 1.47v.04c1.25.2 2.22.65 2.88 1.38c.66.71.99 1.62.99 2.74c0 1.32-.46 2.4-1.37 3.23c-.92.83-2.12 1.24-3.62 1.24c-1.06 0-2.03-.21-2.9-.62m1.49-6.84V8.83c.87-.11 1.58-.43 2.15-.97c.56-.55.84-1.16.84-1.86c0-1.38-.71-2.08-2.11-2.08c-.76 0-1.35.24-1.76.73s-.61 1.17-.61 2.06v8.79c.91.53 1.8.79 2.66.79c.84 0 1.5-.22 1.97-.65c.47-.44.7-1.06.7-1.85c0-1.79-1.28-2.79-3.84-3.04'
const GLYPH_VIEWBOX = 24
// the β path isn't centered in its 24x24 viewBox — its ink box leans toward
// the bottom-right, so the badge centers on the box, not the viewBox
const BETA_BBOX = { x: 6.88, y: 2, width: 10.24, height: 21.12 }

// debug badge geometry, in the 108dp adaptive-icon viewport
const BADGE_SIZE = 26
const BADGE_CORNER_RADIUS = 12
// gap between the badge edge and the β glyph box
const BADGE_PADDING = 5
// bg-coloured frame; the stroke is centered on the edge, so ~half shows outside
const BADGE_OUTLINE_WIDTH = 4
// pin the badge's outer corner onto the launcher's circular icon mask (the
// inscribed circle of the viewport) so it sits flush in the bottom-right
// corner; the frame's outer tip clips against the mask, which is harmless
const MASK_RADIUS = ADAPTIVE_SIZE / 2
const BADGE_FAR = ADAPTIVE_SIZE / 2 + MASK_RADIUS / Math.SQRT2
const BADGE_NEAR = BADGE_FAR - BADGE_SIZE

// committed under src/res, synced into the worktree by forkSyncFiles
const GEN_DRAWABLE = 'src/res/launcher/generated/drawable'
const GEN_DEBUG_MIPMAP = 'src/res/launcher/generated/mipmap-debug'

function shapeToPathXml(shape: SvgShape, overrideFill?: string): string | null {
  const fill = overrideFill ?? resolveFillColor(shape.fill)
  const stroke = resolveStrokeColor(shape.stroke)
  if (!fill && !stroke) return null
  const attrs: string[] = [`android:pathData="${shape.d}"`]
  if (fill) {
    attrs.push(`android:fillColor="${fill}"`)
    const fillAlpha = shape.fillOpacity ?? shape.attrs?.['fill-opacity']
    if (fillAlpha && Number(fillAlpha) < 1) {
      attrs.push(`android:fillAlpha="${fmtNum(Number(fillAlpha))}"`)
    }
  }
  if (stroke) {
    attrs.push(`android:strokeColor="${stroke}"`)
    attrs.push(`android:strokeWidth="${fmtNum(Number(shape.strokeWidth ?? 1))}"`)
    if (shape.strokeLineCap) attrs.push(`android:strokeLineCap="${shape.strokeLineCap}"`)
    if (shape.strokeLineJoin) attrs.push(`android:strokeLineJoin="${shape.strokeLineJoin}"`)
    const strokeAlpha = shape.attrs?.['stroke-opacity']
    if (strokeAlpha && Number(strokeAlpha) < 1) {
      attrs.push(`android:strokeAlpha="${fmtNum(Number(strokeAlpha))}"`)
    }
  }
  return `        <path\n            ${attrs.join('\n            ')} />`
}

function buildDebugBadge(markColor: string): string {
  const r = BADGE_CORNER_RADIUS
  // white square with only the top-left corner rounded, traced clockwise
  const badge = `M${fmtNum(BADGE_NEAR + r)},${fmtNum(BADGE_NEAR)}`
    + ` H${fmtNum(BADGE_FAR)} V${fmtNum(BADGE_FAR)} H${fmtNum(BADGE_NEAR)}`
    + ` V${fmtNum(BADGE_NEAR + r)} A${fmtNum(r)},${fmtNum(r)} 0 0 1`
    + ` ${fmtNum(BADGE_NEAR + r)},${fmtNum(BADGE_NEAR)} Z`
  // β: scale the 24x24 glyph viewBox to the padded badge interior, then center
  // the glyph's ink box (not the viewBox) on the badge
  const scale = (BADGE_SIZE - 2 * BADGE_PADDING) / GLYPH_VIEWBOX
  const badgeCenter = (BADGE_NEAR + BADGE_FAR) / 2
  const offsetX = badgeCenter - (BETA_BBOX.x + BETA_BBOX.width / 2) * scale
  const offsetY = badgeCenter - (BETA_BBOX.y + BETA_BBOX.height / 2) * scale
  return `\n    <path
        android:pathData="${badge}"
        android:fillColor="${DEBUG_BADGE_COLOR}"
        android:strokeColor="${markColor}"
        android:strokeWidth="${fmtNum(BADGE_OUTLINE_WIDTH)}" />
    <group
        android:translateX="${fmtNum(offsetX)}"
        android:translateY="${fmtNum(offsetY)}"
        android:scaleX="${fmtNum(scale)}"
        android:scaleY="${fmtNum(scale)}">
        <path
            android:pathData="${BETA_PATH}"
            android:fillColor="${markColor}" />
    </group>`
}

interface ScaledVectorOpts {
  widthDp: number
  viewport: number
  safe: number
  overlay?: string
  overrideFill?: string
}

// scale an SVG body to `safe` units, centered inside a `viewport`-sized canvas
function buildScaledVector(shapes: SvgShape[], srcW: number, srcH: number, opts: ScaledVectorOpts): string {
  const inset = (opts.viewport - opts.safe) / 2
  const scale = opts.safe / Math.max(srcW, srcH)
  const offsetX = inset + (opts.safe - srcW * scale) / 2
  const offsetY = inset + (opts.safe - srcH * scale) / 2
  const paths = shapes
    .map(s => shapeToPathXml(s, opts.overrideFill))
    .filter((s): s is string => s !== null)
  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${fmtNum(opts.widthDp)}dp"
    android:height="${fmtNum(opts.widthDp)}dp"
    android:viewportWidth="${opts.viewport}"
    android:viewportHeight="${opts.viewport}">
    <group
        android:translateX="${fmtNum(offsetX)}"
        android:translateY="${fmtNum(offsetY)}"
        android:scaleX="${fmtNum(scale)}"
        android:scaleY="${fmtNum(scale)}">
${paths.join('\n')}
    </group>${opts.overlay ?? ''}
</vector>
`
}

function buildForegroundVector(shapes: SvgShape[], srcW: number, srcH: number, debug = false, badgeColor = '#FFFF5858', overrideFill?: string): string {
  return buildScaledVector(shapes, srcW, srcH, {
    widthDp: ADAPTIVE_SIZE,
    viewport: ADAPTIVE_SIZE,
    safe: FG_SAFE,
    overlay: debug ? buildDebugBadge(badgeColor) : undefined,
    overrideFill,
  })
}

function buildSettingsVector(shapes: SvgShape[], srcW: number, srcH: number): string {
  return buildScaledVector(shapes, srcW, srcH, {
    widthDp: SETTINGS_DP,
    viewport: SETTINGS_VIEWPORT,
    safe: SETTINGS_SAFE,
    overrideFill: '#FFFFFFFF',
  })
}

function buildNotificationVector(shapes: SvgShape[], srcW: number, srcH: number): string {
  return buildScaledVector(shapes, srcW, srcH, {
    widthDp: NOTIFICATION_DP,
    viewport: NOTIFICATION_VIEWPORT,
    safe: NOTIFICATION_SAFE,
    overrideFill: '#FFFFFFFF',
  })
}

function buildAdaptiveIcon(foreground: string): string {
  return `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/icon_background_inu" />
    <foreground android:drawable="@drawable/${foreground}" />
    <monochrome android:drawable="@drawable/icon_plane_inu" />
</adaptive-icon>
`
}

function buildBackgroundVector(shadowShapes: SvgShape[], srcW: number, srcH: number, bgColor = '#FFFF5858'): string {
  const inset = (ADAPTIVE_SIZE - FG_SAFE) / 2
  const scale = FG_SAFE / Math.max(srcW, srcH)
  const offsetX = inset + (FG_SAFE - srcW * scale) / 2
  const offsetY = inset + (FG_SAFE - srcH * scale) / 2
  const paths = shadowShapes
    .map(s => shapeToPathXml(s))
    .filter((s): s is string => s !== null)

  return `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${ADAPTIVE_SIZE}dp"
    android:height="${ADAPTIVE_SIZE}dp"
    android:viewportWidth="${ADAPTIVE_SIZE}"
    android:viewportHeight="${ADAPTIVE_SIZE}">
    <path
        android:pathData="M0,0h${ADAPTIVE_SIZE}v${ADAPTIVE_SIZE}h-${ADAPTIVE_SIZE}z"
        android:fillColor="${bgColor}" />
    <group
        android:translateX="${fmtNum(offsetX)}"
        android:translateY="${fmtNum(offsetY)}"
        android:scaleX="${fmtNum(scale)}"
        android:scaleY="${fmtNum(scale)}">
${paths.join('\n')}
    </group>
</vector>
`
}

async function writeGenerated(relPath: string, content: string | Buffer): Promise<boolean> {
  const absPath = join(rootDir, relPath)
  await ensureDir(dirname(absPath))
  const current = await fs.readFile(absPath).catch(() => null)
  const isBuffer = Buffer.isBuffer(content)
  if (current && (isBuffer ? current.equals(content) : current.toString('utf8') === content)) return false
  step(`Generating ${relPath}`)
  await fs.writeFile(absPath, content)
  return true
}

interface LoadedSvg {
  shapes: SvgShape[]
  srcW: number
  srcH: number
  bgColor: string
}

async function loadSvg(relPath: string): Promise<LoadedSvg> {
  const svgPath = join(rootDir, relPath)
  const svg = await fs.readFile(svgPath, 'utf8')
  const viewBox = svg.match(/viewBox\s*=\s*"\s*0\s+0\s+([\d.]+)\s+([\d.]+)\s*"/)
  if (!viewBox) throw new Error(`${relPath} missing viewBox starting at 0 0`)
  const srcW = Number(viewBox[1])
  const srcH = Number(viewBox[2])
  const allShapes = parseSvgBody(svg)

  let bgColor = BG_COLOR
  const foregroundShapes: SvgShape[] = []

  for (const s of allShapes) {
    const isBg = (s.tag === 'rect' && Number(s.attrs?.width) >= srcW && Number(s.attrs?.height) >= srcH)
      || (s.tag === 'circle' && Number(s.attrs?.r) >= srcW / 2)
    if (isBg && s.fill) {
      const resolved = resolveFillColor(s.fill)
      if (resolved) bgColor = resolved
    } else {
      foregroundShapes.push(s)
    }
  }

  return { shapes: foregroundShapes, srcW, srcH, bgColor }
}

function buildSplashVector(shapes: SvgShape[], bgColor = '#FFFF5858'): string {
  const scale = 200 / 512
  const offset = 60
  const color = bgColor.length === 9 ? `#${bgColor.slice(3)}` : bgColor

  const planePathsXml = shapes.map((shape, i) => {
    const name = `plane_${i}`
    const fill = shape.fill ? resolveFillColor(shape.fill) : '#FFFFFF'
    return `                        <path
                            android:name="${name}"
                            android:pathData="${shape.d}"
                            android:fillColor="${fill}"
                            android:fillAlpha="0"
                            android:strokeWidth="1" />`
  }).join('\n')

  const planeTargetsXml = shapes.map((shape, i) => {
    const name = `plane_${i}`
    const targetAlpha = shape.fillOpacity ?? shape.attrs?.['fill-opacity'] ?? '1'
    return `    <target android:name="${name}">
        <aapt:attr name="android:animation">
            <set>
                <objectAnimator
                    android:propertyName="fillAlpha"
                    android:startOffset="200"
                    android:duration="100"
                    android:valueFrom="0"
                    android:valueTo="${fmtNum(Number(targetAlpha))}"
                    android:valueType="floatType"
                    android:interpolator="@android:interpolator/fast_out_slow_in" />
            </set>
        </aapt:attr>
    </target>`
  }).join('\n')

  return `<animated-vector
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt">
    <aapt:attr name="android:drawable">
        <vector
            android:name="splash"
            android:width="320dp"
            android:height="320dp"
            android:viewportWidth="320"
            android:viewportHeight="320">
            <group
                android:name="scaleme"
                android:pivotX="160"
                android:pivotY="160"
                android:scaleX="1"
                android:scaleY="1">
                <group android:name="bg">
                    <path
                        android:name="circle"
                        android:pathData="M 160 110 C 146.744 110 134.018 115.271 124.645 124.645 C 115.271 134.018 110 146.744 110 160 C 110 173.256 115.271 185.982 124.645 195.355 C 134.018 204.729 146.744 210 160 210 C 173.256 210 185.982 204.729 195.355 195.355 C 204.729 185.982 210 173.256 210 160 C 210 146.744 204.729 134.018 195.355 124.645 C 185.982 115.271 173.256 110 160 110 Z"
                        android:fillColor="${color}"
                        android:fillAlpha="0"
                        android:strokeWidth="1" />
                </group>
                <group
                    android:name="fg"
                    android:pivotX="160"
                    android:pivotY="160"
                    android:scaleX="0.5"
                    android:scaleY="0.5">
                    <clip-path
                        android:name="mask"
                        android:pathData="M 160 60 C 133.489 60 108.036 70.543 89.289 89.289 C 70.543 108.036 60 133.489 60 160 C 60 186.511 70.543 211.964 89.289 230.711 C 108.036 249.457 133.489 260 160 260 C 186.511 260 211.964 249.457 230.711 230.711 C 249.457 211.964 260 186.511 260 160 C 260 133.489 249.457 108.036 230.711 89.289 C 211.964 70.543 186.511 60 160 60 Z" />
                    <group
                        android:name="glyph_pos"
                        android:translateX="${fmtNum(offset)}"
                        android:translateY="${fmtNum(offset)}"
                        android:scaleX="${fmtNum(scale)}"
                        android:scaleY="${fmtNum(scale)}">
${planePathsXml}
                    </group>
                </group>
            </group>
        </vector>
    </aapt:attr>
    <target android:name="circle">
        <aapt:attr name="android:animation">
            <set>
                <objectAnimator
                    android:propertyName="pathData"
                    android:startOffset="200"
                    android:duration="300"
                    android:valueFrom="M 160 110 C 146.744 110 134.018 115.271 124.645 124.645 C 115.271 134.018 110 146.744 110 160 C 110 173.256 115.271 185.982 124.645 195.355 C 134.018 204.729 146.744 210 160 210 C 173.256 210 185.982 204.729 195.355 195.355 C 204.729 185.982 210 173.256 210 160 C 210 146.744 204.729 134.018 195.355 124.645 C 185.982 115.271 173.256 110 160 110 Z"
                    android:valueTo="M 160 60 C 133.489 60 108.036 70.543 89.289 89.289 C 70.543 108.036 60 133.489 60 160 C 60 186.511 70.543 211.964 89.289 230.711 C 108.036 249.457 133.489 260 160 260 C 186.511 260 211.964 249.457 230.711 230.711 C 249.457 211.964 260 186.511 260 160 C 260 133.489 249.457 108.036 230.711 89.289 C 211.964 70.543 186.511 60 160 60 Z"
                    android:valueType="pathType"
                    android:interpolator="@android:anim/overshoot_interpolator" />
                <objectAnimator
                    android:propertyName="fillAlpha"
                    android:startOffset="200"
                    android:duration="100"
                    android:valueFrom="0"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:interpolator="@android:interpolator/fast_out_slow_in" />
            </set>
        </aapt:attr>
    </target>
${planeTargetsXml}
    <target android:name="fg">
        <aapt:attr name="android:animation">
            <set>
                <objectAnimator
                    android:propertyName="scaleX"
                    android:startOffset="200"
                    android:duration="300"
                    android:valueFrom="0.5"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:interpolator="@android:anim/overshoot_interpolator" />
                <objectAnimator
                    android:propertyName="scaleY"
                    android:startOffset="200"
                    android:duration="300"
                    android:valueFrom="0.5"
                    android:valueTo="1"
                    android:valueType="floatType"
                    android:interpolator="@android:anim/overshoot_interpolator" />
            </set>
        </aapt:attr>
    </target>
</animated-vector>
`
}

function rasterizeSvg(svgString: string, width: number, height: number): Buffer {
  try {
    return execFileSync('resvg', [
      '--resources-dir', '.',
      '--quiet',
      '-w', String(width),
      '-h', String(height),
      '-', '-c',
    ], { input: Buffer.from(svgString, 'utf-8'), maxBuffer: 10 * 1024 * 1024 })
  } catch {
    return execFileSync('rsvg-convert', [
      '-w', String(width),
      '-h', String(height),
      '-f', 'png',
    ], { input: Buffer.from(svgString, 'utf-8'), maxBuffer: 10 * 1024 * 1024 })
  }
}

const fg = await loadSvg('src/res/launcher/icon-foreground.svg')
const shadowFile = (await fs.access(join(rootDir, 'src/res/launcher/icon-background-shadow.svg')).then(() => true, () => false))
  ? 'src/res/launcher/icon-background-shadow.svg'
  : 'src/res/launcher/icon-background.svg'
const shadow = await loadSvg(shadowFile)
const mono = await loadSvg('src/res/launcher/icon-mono.svg')

const foreground = buildForegroundVector(fg.shapes, fg.srcW, fg.srcH, false, BG_COLOR)
const foregroundDebug = buildForegroundVector(fg.shapes, fg.srcW, fg.srcH, true, BG_COLOR)
const monochrome = buildForegroundVector([...shadow.shapes, ...mono.shapes], mono.srcW, mono.srcH, false, BG_COLOR, '#FFFFFFFF')
const settingsIcon = buildSettingsVector(mono.shapes, mono.srcW, mono.srcH)
const notificationIcon = buildNotificationVector(mono.shapes, mono.srcW, mono.srcH)
const background = buildBackgroundVector(shadow.shapes, shadow.srcW, shadow.srcH, BG_COLOR)
const debugIcon = buildAdaptiveIcon('icon_foreground_inu_debug')
const splashIcon = buildSplashVector([...shadow.shapes, ...fg.shapes], BG_COLOR)

// construct full-color composited SVG over the circular #FF5858 background:
// solid circle -> shadow shape (from background file) -> main shape (from foreground file)
const shadowRaw = await fs.readFile(join(rootDir, shadowFile), 'utf8')
const fgRaw = await fs.readFile(join(rootDir, 'src/res/launcher/icon-foreground.svg'), 'utf8')
const shadowInner = shadowRaw.replace(/<\/?svg[^>]*>/gi, '').trim()
const fgInner = fgRaw.replace(/<\/?svg[^>]*>/gi, '').trim()

const compositedLauncherSvg = `<?xml version="1.0" encoding="utf-8"?>
<svg width="512" height="512" viewBox="0 0 512 512" fill="none" xmlns="http://www.w3.org/2000/svg">
    <rect width="512" height="512" rx="256" fill="${BG_COLOR}" />
${shadowInner}
${fgInner}
</svg>
`

const targets: [string, string | Buffer][] = [
  [`${GEN_DRAWABLE}/icon_background_inu.xml`, background],
  [`${GEN_DRAWABLE}/icon_plane_inu.xml`, monochrome],
  [`${GEN_DRAWABLE}/icon_foreground_inu.xml`, foreground],
  [`${GEN_DRAWABLE}/icon_foreground_inu_round.xml`, foreground],
  [`${GEN_DRAWABLE}/icon_foreground_inu_debug.xml`, foregroundDebug],
  [`${GEN_DRAWABLE}/icon_settings_inu.xml`, settingsIcon],
  [`${GEN_DRAWABLE}/icon_notification_inu.xml`, notificationIcon],
  [`${GEN_DEBUG_MIPMAP}/ic_launcher.xml`, debugIcon],
  [`${GEN_DEBUG_MIPMAP}/ic_launcher_round.xml`, debugIcon],
  ['src/res/drawable/inu_splash_320.xml', splashIcon],
]

// legacy mipmap densities
const MIPMAP_DENSITIES: [string, number][] = [
  ['mdpi', 48],
  ['hdpi', 72],
  ['xhdpi', 96],
  ['xxhdpi', 144],
  ['xxxhdpi', 192],
]

for (const [density, size] of MIPMAP_DENSITIES) {
  const buf = rasterizeSvg(compositedLauncherSvg, size, size)
  targets.push([`worktree/TMessagesProj/src/main/res/mipmap-${density}/ic_launcher.png`, buf])
  targets.push([`worktree/TMessagesProj/src/main/res/mipmap-${density}/ic_launcher_round.png`, buf])
}

// playstore and web assets at TMessagesProj/src/main/
const storeBuf = rasterizeSvg(compositedLauncherSvg, 512, 512)
targets.push(['worktree/TMessagesProj/src/main/ic_launcher-playstore.png', storeBuf])
targets.push(['worktree/TMessagesProj/src/main/ic_launcher-web.png', storeBuf])

let dirty = false
for (const [rel, content] of targets) {
  if (await writeGenerated(rel, content)) dirty = true
}

await linkForkSource(worktreeDir).catch(() => {})

success(dirty ? 'Launcher icons generated' : 'Launcher icons already up to date')
