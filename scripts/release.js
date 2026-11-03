/**
@author Oli Gray
Builds a signed release APK of the live app and, with --publish, puts it on GitHub for Obtainium
@version 1.0

Version    Author    Comments
1.0        CL        Initial version
*/

// Run from android-shell: `npm run release` (patch bump), `npm run release -- minor`, `-- 2.0.0`, and add `--publish` to
// commit, tag, push and create the GitHub release. Without --publish it only builds, into dist/.
// It needs android/keystore.properties (the release key, kept out of the repo) and android/app/google-services.json.
const fs = require('fs')
const os = require('os')
const path = require('path')
const crypto = require('crypto')
const { spawnSync } = require('child_process')

const root = path.join(__dirname, '..')
const android = path.join(root, 'android')
const versionFile = path.join(android, 'version.properties')
const sdk = process.env.ANDROID_HOME || path.join(process.env.LOCALAPPDATA || os.homedir(), 'Android/Sdk')
const env = { ...process.env, ANDROID_HOME: sdk, JAVA_HOME: process.env.JAVA_HOME || 'C:/Program Files/Android/Android Studio/jbr' }

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { cwd: root, env, stdio: 'inherit', shell: process.platform === 'win32', ...options })
  if (result.status !== 0) throw new Error(`${command} ${args[0] || ''} failed`)
}

function read() {
  const props = Object.fromEntries(fs.readFileSync(versionFile, 'utf8').split(/\r?\n/).filter(Boolean).map((line) => line.split('=')))
  return { code: Number(props.versionCode), name: props.versionName }
}

function nextName(current, bump) {
  if (/^\d+\.\d+\.\d+$/.test(bump)) return bump
  const [major, minor, patch] = current.split('.').map(Number)
  if (bump === 'major') return `${major + 1}.0.0`
  if (bump === 'minor') return `${major}.${minor + 1}.0`
  return `${major}.${minor}.${patch + 1}`
}

function main() {
  const args = process.argv.slice(2)
  const publish = args.includes('--publish')
  const bump = args.find((a) => !a.startsWith('--')) || 'patch'
  if (!fs.existsSync(path.join(android, 'keystore.properties'))) throw new Error('android/keystore.properties is missing: there is no release key, and an unsigned APK cannot update an installed app')
  if (!fs.existsSync(path.join(android, 'app/google-services.json'))) throw new Error('android/app/google-services.json is missing: the app would build without push notifications')

  const current = read()
  const version = nextName(current.name, bump)
  fs.writeFileSync(versionFile, `versionCode=${current.code + 1}\nversionName=${version}\n`)
  console.log(`Building ${version} (versionCode ${current.code + 1})`)

  run(`"${path.join(android, 'gradlew.bat')}"`, ['assembleLiveRelease'], { cwd: android })
  const built = path.join(android, 'app/build/outputs/apk/live/release/app-live-release.apk')
  fs.mkdirSync(path.join(root, 'dist'), { recursive: true })
  const apk = path.join(root, 'dist', `froglog-${version}.apk`)
  fs.copyFileSync(built, apk)

  // The signature has to check out, or Android refuses it as an update.
  const tools = path.join(sdk, 'build-tools')
  const newest = fs.readdirSync(tools).sort((a, b) => a.localeCompare(b, undefined, { numeric: true })).pop()
  run(`"${path.join(tools, newest, 'apksigner.bat')}"`, ['verify', '--print-certs', apk])
  const sha = crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex')
  console.log(`\n${apk}\nsha256 ${sha}`)

  if (!publish) {
    console.log('\nBuilt only. Add --publish to commit, tag and release it.')
    return
  }
  run('git', ['add', '-A'])
  run('git', ['commit', '-m', `Release ${version}`])
  run('git', ['tag', `v${version}`])
  run('git', ['push', 'origin', 'HEAD', '--tags'])
  run('gh', ['release', 'create', `v${version}`, apk, '--title', `FrogLog ${version}`, '--generate-notes'])
}

try {
  main()
} catch (e) {
  console.error(e.message)
  process.exit(1)
}
