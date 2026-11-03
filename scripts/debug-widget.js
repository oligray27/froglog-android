/**
@author Oli Gray
Puts made-up sessions into the Quick Session widget on the phone, to see how each looks
@version 1.0

Version    Author    Comments
1.0        CL        Initial version
*/

// Run from android-shell: `npm run widget-state -- steam`, or `npm run widget-state -- cycle 6` to step through every state.
// It drives Current Activity and Online Now, and themes all three widgets. Only FrogLog Dev has the receiver, and nothing is written to the server or the database: the widget just shows what
// it is told until you run `clear`. The widget must already be on the home screen, signed in.
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

// The first ten are for Current Activity, the last four for Online Now.
const STATES = ['idle', 'manual', 'paused', 'lilypad', 'lilypad_paused', 'lilypad_view', 'steam', 'psn', 'xbox', 'switch', 'friends', 'friends_three', 'friends_one', 'friends_none']
// Every widget's theme: theme_<id> for the site's themes (from utils/themes.js), or theme_wallpaper.
const THEMES = [...fs.readFileSync(path.join(__dirname, '../../frontend/src/utils/themes.js'), 'utf8').matchAll(/id: '([^']+)', label/g)].map((m) => m[1])
const COMPONENT = 'uk.co.froglog.app.dev/uk.co.froglog.app.widgets.DebugSessionReceiver'

function send(type) {
  const result = spawnSync('adb', ['shell', 'am', 'broadcast', '-n', COMPONENT, '--es', 'type', type], { encoding: 'utf8', shell: process.platform === 'win32' })
  if (result.status !== 0 || /Permission Denial|Error/.test(result.stdout + result.stderr)) {
    throw new Error(`adb failed for ${type}: ${(result.stdout + result.stderr).trim()}`)
  }
  console.log(type)
}

async function main() {
  const [what, seconds] = process.argv.slice(2)
  if (what === 'cycle') {
    for (const state of STATES) {
      send(state)
      await new Promise((resolve) => setTimeout(resolve, (Number(seconds) || 6) * 1000))
    }
    send('clear')
  } else if (what === 'themes') {
    for (const theme of [...THEMES, 'wallpaper']) {
      send(`theme_${theme}`)
      await new Promise((resolve) => setTimeout(resolve, (Number(seconds) || 6) * 1000))
    }
    send('theme_dark')
  } else if (what === 'clear' || STATES.includes(what) || (what || '').startsWith('theme_')) {
    send(what)
  } else {
    console.log(`Usage: npm run widget-state -- <${[...STATES, 'clear', 'cycle [seconds]', 'theme_<id>|theme_wallpaper', 'themes [seconds]'].join('|')}>
Themes: ${THEMES.join(', ')}, wallpaper`)
    process.exit(1)
  }
}

main().catch((e) => { console.error(e.message); process.exit(1) })
