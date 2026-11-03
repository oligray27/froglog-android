/**
@author Oli Gray
Builds the app icon and splash images from the site's PWA icons
@version 1.0

Version    Author    Comments
1.0        CL        Initial version
*/

// Run from android-shell with `node scripts/generate-assets.js`. Uses the backend's copy of sharp.
const fs = require('fs')
const path = require('path')
const sharp = require('../../backend/node_modules/sharp')

const res = path.join(__dirname, '../android/app/src/main/res')
const maskable = path.join(__dirname, '../../frontend/public/pwa-maskable-512x512.png')
const logo = path.join(__dirname, '../../frontend/public/pwa-512x512.png')
const badge = path.join(__dirname, '../../frontend/public/badge-96x96.png')
const devRes = path.join(__dirname, '../android/app/src/dev/res')
const blueprint = path.join(__dirname, '../assets/dev-icon-background.png')
const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 }
// Zoomed in, because the fine grid lines blur into plain blue at launcher size.
const BLUEPRINT_CROP = { left: 200, top: 200, width: 340, height: 340 }

const liveIcon = (size) => sharp(maskable).resize(size).png().toBuffer()

// The maskable icon is a white frog on black, so its brightness works as the frog's alpha.
async function devIcon(size) {
  const grid = await sharp(blueprint).extract(BLUEPRINT_CROP).resize(size).png().toBuffer()
  const alpha = await sharp(maskable).resize(size).extractChannel(0).png().toBuffer()
  const frog = await sharp({ create: { width: size, height: size, channels: 3, background: '#ffffff' } }).joinChannel(alpha).png().toBuffer()
  return sharp(grid).composite([{ input: frog }]).png().toBuffer()
}

async function writeIcons(root, render) {
  for (const [density, scale] of Object.entries(DENSITIES)) {
    const dir = path.join(root, `mipmap-${density}`)
    fs.mkdirSync(dir, { recursive: true })
    const legacy = Math.round(48 * scale)
    fs.writeFileSync(path.join(dir, 'ic_launcher_foreground.png'), await render(Math.round(108 * scale)))
    fs.writeFileSync(path.join(dir, 'ic_launcher.png'), await render(legacy))
    const circle = Buffer.from(`<svg width="${legacy}" height="${legacy}"><circle cx="${legacy / 2}" cy="${legacy / 2}" r="${legacy / 2}"/></svg>`)
    await sharp(await render(legacy)).composite([{ input: circle, blend: 'dest-in' }]).png().toFile(path.join(dir, 'ic_launcher_round.png'))
  }
}

async function main() {
  await writeIcons(res, liveIcon)
  await writeIcons(devRes, devIcon)

  const splashes = fs.readdirSync(res).filter((d) => d.startsWith('drawable')).map((d) => path.join(res, d, 'splash.png')).filter((f) => fs.existsSync(f))
  for (const file of splashes) {
    const { width, height } = await sharp(file).metadata()
    const size = Math.round(Math.min(width, height) * 0.35)
    const art = await sharp(logo).resize(size).png().toBuffer()
    const canvas = await sharp({ create: { width, height, channels: 3, background: '#000000' } }).composite([{ input: art }]).png().toBuffer()
    fs.writeFileSync(file, canvas)
  }

  fs.copyFileSync(badge, path.join(res, 'drawable/ic_notification.png'))

  const colours = path.join(res, 'values/ic_launcher_background.xml')
  fs.writeFileSync(colours, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#000000</color>\n</resources>\n')
  console.log('icons written, splashes', splashes.length)
}

main().catch((e) => { console.error(e.message); process.exit(1) })
