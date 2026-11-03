/**
@author Oli Gray
Writes the widgets' theme table from the site's theme files
@version 1.1

Version    Author    Comments
1.0        CL        Initial version
1.1        CL        Reads background, border and input as well, with colour-mix and alpha, for the buttons
*/

// Run from android-shell with `node scripts/generate-widget-themes.js` after a theme changes or is added.
// It reads the colours the widgets use from frontend/src/styles, so the widgets and the site cannot drift
// apart, and writes WidgetThemes.java. Themes come from utils/themes.js in the picker's order.
const fs = require('fs')
const path = require('path')

const frontend = path.join(__dirname, '../../frontend/src')
const out = path.join(__dirname, '../android/app/src/main/java/uk/co/froglog/app/widgets/WidgetThemes.java')

// The tokens a widget uses, as the site's shadcn variables.
const TOKENS = {
  card: '--card',
  cardForeground: '--card-foreground',
  mutedForeground: '--muted-foreground',
  secondary: '--secondary',
  secondaryForeground: '--secondary-foreground',
  primary: '--primary',
  primaryForeground: '--primary-foreground',
  background: '--background',
  border: '--border',
  input: '--input',
}

function stripComments(css) {
  return css.replace(/\/\*[\s\S]*?\*\//g, '')
}

// Every block with no block inside it: its selectors, and its body.
function leafRules(css) {
  const rules = []
  const stack = []
  let start = 0
  for (let i = 0; i < css.length; i++) {
    if (css[i] === '{') {
      stack.push({ head: css.slice(start, i).trim(), open: i, hasChild: false })
      if (stack.length > 1) stack[stack.length - 2].hasChild = true
      start = i + 1
    } else if (css[i] === '}') {
      const block = stack.pop()
      if (block && !block.hasChild) rules.push({ selectors: block.head.split(',').map((s) => s.trim()), body: css.slice(block.open + 1, i) })
      start = i + 1
    }
  }
  return rules
}

function variables(body) {
  const found = {}
  for (const match of body.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) found[match[1]] = match[2].trim()
  return found
}

const files = [path.join(frontend, 'styles/tokens.css'), ...fs.readdirSync(path.join(frontend, 'styles/themes')).map((f) => path.join(frontend, 'styles/themes', f))]
const rules = files.flatMap((file) => leafRules(stripComments(fs.readFileSync(file, 'utf8'))))

function variablesFor(selectors) {
  const merged = {}
  for (const rule of rules) {
    if (rule.selectors.some((s) => selectors.includes(s))) Object.assign(merged, variables(rule.body))
  }
  return merged
}

const base = variablesFor([':root'])

function selectorsFor(id) {
  if (id === 'light') return [':root', '[data-theme="light"]']
  if (id === 'dark') return ['.dark', '[data-theme="dark"]']
  return [`[data-theme="${id}"]`]
}

function oklch(l, c, hDeg, a) {
  const h = (hDeg * Math.PI) / 180
  const x = c * Math.cos(h)
  const y = c * Math.sin(h)
  const l_ = (l + 0.3963377774 * x + 0.2158037573 * y) ** 3
  const m_ = (l - 0.1055613458 * x - 0.0638541728 * y) ** 3
  const s_ = (l - 0.0894841775 * x - 1.291485548 * y) ** 3
  const linear = [
    4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_,
    -1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_,
    -0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_,
  ]
  const [r, g, b] = linear.map((v) => {
    const clamped = Math.min(1, Math.max(0, v))
    return Math.round((clamped <= 0.0031308 ? 12.92 * clamped : 1.055 * clamped ** (1 / 2.4) - 0.055) * 255)
  })
  return { r, g, b, a }
}

// Splits on the commas that are not inside brackets.
function splitTop(text) {
  const parts = []
  let depth = 0
  let start = 0
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '(') depth++
    else if (text[i] === ')') depth--
    else if (text[i] === ',' && depth === 0) {
      parts.push(text.slice(start, i).trim())
      start = i + 1
    }
  }
  parts.push(text.slice(start).trim())
  return parts
}

const alphaOf = (text) => (text === undefined ? 1 : text.endsWith('%') ? Number(text.slice(0, -1)) / 100 : Number(text))

// A colour as { r, g, b, a }, whatever form the site wrote it in. The mix is done in sRGB, a little off from oklch's but close.
function colour(value, theme, depth = 0) {
  if (depth > 20) throw new Error(`${value} loops`)
  const text = value.trim()
  const named = { white: { r: 255, g: 255, b: 255, a: 1 }, black: { r: 0, g: 0, b: 0, a: 1 }, transparent: { r: 0, g: 0, b: 0, a: 0 } }[text.toLowerCase()]
  if (named) return { ...named }
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(text)
  if (hex) {
    const digits = hex[1].length === 3 ? [...hex[1]].map((d) => d + d).join('') : hex[1]
    return { r: parseInt(digits.slice(0, 2), 16), g: parseInt(digits.slice(2, 4), 16), b: parseInt(digits.slice(4, 6), 16), a: 1 }
  }
  const ok = /^oklch\(\s*([\d.]+)(%?)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+%?))?\s*\)$/i.exec(text)
  if (ok) return oklch(Number(ok[1]) / (ok[2] ? 100 : 1), Number(ok[3]), Number(ok[4]), alphaOf(ok[5]))
  const reference = /^var\(\s*(--[\w-]+)\s*(?:,\s*(.+))?\)$/.exec(text)
  if (reference) {
    const raw = theme[reference[1]] ?? base[reference[1]]
    if (raw !== undefined) return colour(raw, theme, depth + 1)
    if (reference[2]) return colour(reference[2], theme, depth + 1)
    return null
  }
  const mix = /^color-mix\(\s*in\s+[\w-]+\s*,\s*([\s\S]+)\)$/.exec(text)
  if (mix) {
    const [first, second] = splitTop(mix[1]).map((part) => {
      const percent = /\s(\d+(?:\.\d+)?)%$/.exec(part)
      return { colour: colour(percent ? part.slice(0, percent.index) : part, theme, depth + 1), percent: percent ? Number(percent[1]) : undefined }
    })
    if (!first.colour || !second.colour) return null
    const p1 = first.percent ?? (second.percent === undefined ? 50 : 100 - second.percent)
    const p2 = second.percent ?? 100 - p1
    const total = p1 + p2
    const w1 = p1 / total
    const w2 = p2 / total
    const channel = (k) => Math.round(first.colour[k] * w1 + second.colour[k] * w2)
    return { r: channel('r'), g: channel('g'), b: channel('b'), a: first.colour.a * w1 + second.colour.a * w2 }
  }
  return null
}

const themesSource = fs.readFileSync(path.join(frontend, 'utils/themes.js'), 'utf8')
const themes = [...themesSource.matchAll(/\{ id: '([^']+)', label: '([^']+)', surface: '[^']+', light: (true|false) \}/g)]
  .map((m) => ({ id: m[1], label: m[2], light: m[3] === 'true' }))
if (!themes.length) throw new Error('no themes found in utils/themes.js')

const byte = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0').toUpperCase()
const argb = (c) => `0x${byte(c.a * 255)}${byte(c.r)}${byte(c.g)}${byte(c.b)}`

const entries = themes.map((theme) => {
  const merged = variablesFor(selectorsFor(theme.id))
  const colours = Object.entries(TOKENS).map(([key, variable]) => {
    const raw = merged[variable] ?? base[variable]
    const found = raw === undefined ? null : colour(raw, merged)
    if (!found) throw new Error(`${theme.id}: ${variable} did not resolve to a colour (${raw})`)
    return argb(found)
  })
  return `        new Entry("${theme.id}", "${theme.label.replace(/"/g, '\\"')}", ${theme.light}, ${colours.join(', ')})`
})

fs.writeFileSync(out, `package uk.co.froglog.app.widgets;

/**
 * The site's built-in themes as the widgets use them. Generated by scripts/generate-widget-themes.js from
 * frontend/src/styles and utils/themes.js: do not edit by hand. Colours are 0xAARRGGBB, since --input is translucent in some.
 */
final class WidgetThemes {
    private WidgetThemes() {}

    static final class Entry {
        final String id;
        final String label;
        final boolean light;
        final int card, cardForeground, mutedForeground, secondary, secondaryForeground, primary, primaryForeground, background, border, input;

        Entry(String id, String label, boolean light, int card, int cardForeground, int mutedForeground, int secondary, int secondaryForeground, int primary, int primaryForeground, int background, int border, int input) {
            this.id = id;
            this.label = label;
            this.light = light;
            this.card = card;
            this.cardForeground = cardForeground;
            this.mutedForeground = mutedForeground;
            this.secondary = secondary;
            this.secondaryForeground = secondaryForeground;
            this.primary = primary;
            this.primaryForeground = primaryForeground;
            this.background = background;
            this.border = border;
            this.input = input;
        }
    }

    // In the theme picker's order. "dark" is what the widgets were designed in.
    static final Entry[] ALL = {
${entries.join(',\n')}
    };

    static Entry find(String id) {
        for (Entry entry : ALL) if (entry.id.equals(id)) return entry;
        return null;
    }
}
`)
console.log(`${themes.length} themes written`)
for (const e of entries.slice(0, 2)) console.log(e.trim())
