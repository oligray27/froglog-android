/**
@author Oli Gray
Builds FrogLog Dev, which loads this PC's dev server, and installs it on the phone
@version 1.0

Version    Author    Comments
1.0        CL        Initial version
*/

// Run from android-shell with `npm run dev-app`, or `npm run dev-app -- http://host:port` for another address.
const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawnSync } = require('child_process')

const PORT = 5173
const app = path.join(__dirname, '../android/app')
const dev = path.join(app, 'src/dev')
const sdk = process.env.ANDROID_HOME || path.join(process.env.LOCALAPPDATA, 'Android/Sdk')
const env = { ...process.env, ANDROID_HOME: sdk, JAVA_HOME: 'C:/Program Files/Android/Android Studio/jbr' }

// VirtualBox and WSL adapters are also listed, and the phone can't reach either.
function lanAddress() {
  const addresses = Object.values(os.networkInterfaces()).flat()
  const lan = addresses.find((a) => a.family === 'IPv4' && !a.internal && !a.address.startsWith('172.') && !a.address.startsWith('192.168.56.'))
  if (!lan) throw new Error('No network address found. Pass one: npm run dev-app -- http://host:5173')
  return `http://${lan.address}:${PORT}`
}

function writeJson(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true })
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`)
}

function writeDevConfig(url) {
  const config = JSON.parse(fs.readFileSync(path.join(app, 'src/main/assets/capacitor.config.json'), 'utf8'))
  config.appName = 'FrogLog Dev'
  config.server = { ...config.server, url, cleartext: true }
  writeJson(path.join(dev, 'assets/capacitor.config.json'), config)
}

// The build needs an entry for the dev package. A real one from Firebase goes in this file and is kept.
function ensureFirebaseConfig() {
  const file = path.join(dev, 'google-services.json')
  if (fs.existsSync(file)) return
  const services = JSON.parse(fs.readFileSync(path.join(app, 'google-services.json'), 'utf8'))
  for (const client of services.client) client.client_info.android_client_info.package_name = 'uk.co.froglog.app.dev'
  writeJson(file, services)
}

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.status !== 0) throw new Error(`${command} failed`)
}

try {
  const url = process.argv[2] || lanAddress()
  writeDevConfig(url)
  ensureFirebaseConfig()
  run(`"${path.join(app, '../gradlew.bat')}"`, ['assembleDevDebug'], path.join(app, '..'))
  run(`"${path.join(sdk, 'platform-tools/adb')}"`, ['install', '-r', `"${path.join(app, 'build/outputs/apk/dev/debug/app-dev-debug.apk')}"`])
  console.log(`FrogLog Dev installed, loading ${url}`)
} catch (e) {
  console.error(e.message)
  process.exit(1)
}
