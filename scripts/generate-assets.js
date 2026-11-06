/**
@author Oli Gray
Builds the app icon and splash images from the 20x20 pixel frog
@version 2.0

Version    Author    Comments
1.0        CL        Initial version
2.0        CL        Drawn from assets/frog-20.png at whole-number scales; resizing the site's icons blurred the pixel frog
*/

// Run from android-shell with `node scripts/generate-assets.js`. Uses the backend's copy of sharp.
//
// The frog is 20x20 pixels (assets/frog-20.png, white on transparent). It is drawn with nearest-neighbour at the largest whole-number
// scale that fits, centred, so every edge stays sharp. Never resize an icon made from it: a smoothly resized pixel frog goes blurry.
// The website's icons (frontend/public) are made the same way, so this doesn't read them.
const fs = require('fs')
const path = require('path')
const sharp = require('../../backend/node_modules/sharp')

const res = path.join(__dirname, '../android/app/src/main/res')
const devRes = path.join(__dirname, '../android/app/src/dev/res')
const frogFile = path.join(__dirname, '../assets/frog-20.png')
const blueprint = path.join(__dirname, '../assets/dev-icon-background.png')
const badge = path.join(__dirname, '../../frontend/public/badge-96x96.png')
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }
// Zoomed in, because the fine grid lines blur into plain blue at launcher size.
const BLUEPRINT_CROP = { left: 200, top: 200, width: 340, height: 340 }
const N = 20
const BLACK = { r: 0, g: 0, b: 0, alpha: 1 }
const CLEAR = { r: 0, g: 0, b: 0, alpha: 0 }

// The furthest corner of any frog pixel from the frog's centre, in pixels: what has to stay inside an icon mask's circle.
async function frogRadius() {
  const { data } = await sharp(frogFile).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  let radius = 0
  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      if (data[(y * N + x) * 4 + 3] === 0) continue
      for (const [dx, dy] of [[0, 0], [1, 0], [0, 1], [1, 1]]) radius = Math.max(radius, Math.hypot(x + dx - N / 2, y + dy - N / 2))
    }
  }
  return radius
}

// The frog at a whole-number scale, centred on a width x height canvas (a colour, or transparent).
async function frogOn(width, height, scale, background) {
  const art = await sharp(frogFile).resize(N * scale, N * scale, { kernel: 'nearest' }).png().toBuffer()
  const left = Math.round(width / 2 - (N * scale) / 2)
  const top = Math.round(height / 2 - (N * scale) / 2)
  return sharp({ create: { width, height, channels: 4, background } }).composite([{ input: art, left, top }]).png().toBuffer()
}

// The same frog over the dev app's blueprint grid, so the two apps can be told apart on the home screen.
async function frogOnBlueprint(size, scale) {
  const grid = await sharp(blueprint).extract(BLUEPRINT_CROP).resize(size).png().toBuffer()
  const art = await sharp(frogFile).resize(N * scale, N * scale, { kernel: 'nearest' }).png().toBuffer()
  const at = Math.round(size / 2 - (N * scale) / 2)
  return sharp(grid).composite([{ input: art, left: at, top: at }]).png().toBuffer()
}

async function writeIcons(root, draw, radius) {
  for (const [density, density_scale] of Object.entries(DENSITIES)) {
    const dir = path.join(root, `mipmap-${density}`)
    fs.mkdirSync(dir, { recursive: true })
    const legacy = Math.round(48 * density_scale)
    const foreground = Math.round(108 * density_scale)
    // The adaptive foreground is cropped by the launcher's mask, which always shows at least a 33dp circle of its 108dp: stay inside 34.
    fs.writeFileSync(path.join(dir, 'ic_launcher_foreground.png'), await draw(foreground, Math.max(1, Math.floor((foreground * 34 / 108) / radius))))
    // The plain icon fills 80% of its width; the round one sits inside its circle.
    fs.writeFileSync(path.join(dir, 'ic_launcher.png'), await draw(legacy, Math.max(1, Math.floor((legacy * 0.8) / N))))
    const round = await draw(legacy, Math.max(1, Math.floor((legacy / 2) * 0.92 / radius)), true)
    const circle = Buffer.from(`<svg width="${legacy}" height="${legacy}"><circle cx="${legacy / 2}" cy="${legacy / 2}" r="${legacy / 2}"/></svg>`)
    await sharp(round).composite([{ input: circle, blend: 'dest-in' }]).png().toFile(path.join(dir, 'ic_launcher_round.png'))
  }
}

async function main() {
  const radius = await frogRadius()
  // The live app's launcher: the frog on black. The round icon needs an alpha channel for its circle.
  await writeIcons(res, (size, scale, round) => frogOn(size, size, scale, BLACK).then((png) => (round ? sharp(png).ensureAlpha().png().toBuffer() : sharp(png).removeAlpha().png().toBuffer())), radius)
  await writeIcons(devRes, (size, scale) => frogOnBlueprint(size, scale), radius)

  // Splashes (the theme's window background; Android 12+ builds its own from the launcher icon): the frog at 28% of the shorter side.
  const splashes = fs.readdirSync(res).filter((d) => d.startsWith('drawable')).map((d) => path.join(res, d, 'splash.png')).filter((f) => fs.existsSync(f))
  for (const file of splashes) {
    const { width, height } = await sharp(file).metadata()
    const scale = Math.max(1, Math.round((Math.min(width, height) * 0.28) / N))
    fs.writeFileSync(file, await frogOn(width, height, scale, BLACK))
  }

  // The notification icon is the status bar's shape, white on transparent, and the site's badge is already drawn the same way.
  fs.copyFileSync(badge, path.join(res, 'drawable/ic_notification.png'))

  const colours = path.join(res, 'values/ic_launcher_background.xml')
  fs.writeFileSync(colours, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#000000</color>\n</resources>\n')
  console.log('icons written, splashes', splashes.length)
}

main().catch((e) => { console.error(e.message); process.exit(1) })
