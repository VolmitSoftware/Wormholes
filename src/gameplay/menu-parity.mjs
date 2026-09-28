import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'

const PLAYER = 'GoldenQA'
const BASE = [2048, 80, 2048]
const STAND = at(0.5, 0, -2.5)
const CLEARED = [at(-6, 0, -6), at(8, 4, 4)]
const PLATFORMS = [[at(-6, -1, -6), at(8, -1, 4)]]
const PORTALS = [
  { name: 'Alpha', from: at(0, 0, 0), to: at(1, 2, 0), type: 'PORTAL', look: [0, 0, 1] },
  { name: 'Beta', from: at(3, 0, 0), to: at(4, 2, 0), type: 'PORTAL', look: [0, 0, 1] }
]
const DOOR_SUPPORT = at(-3, -1, -1)
const SETTLE_TICKS = 6
const SETTLE_QUIET_MS = 250
const SETTLE_MAX_MS = 8000

const LEVEL_NAME = 'minecraft:overworld'
const PEER = 'GoldenPeer'
const DEFAULT_GLINT_ITEMS = new Set([
  'minecraft:enchanted_golden_apple', 'minecraft:experience_bottle', 'minecraft:written_book', 'minecraft:nether_star',
  'minecraft:enchanted_book', 'minecraft:end_crystal', 'minecraft:debug_stick'
])

const NORMALIZATIONS = [
  {
    id: 'uuid',
    pattern: '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}',
    replacement: '<uuid>',
    reason: 'Portal, network and door identifiers are random per run'
  },
  {
    id: 'nether-name',
    pattern: '^(?:minecraft:overworld_nether|minecraft:the_nether)$',
    replacement: '<nether>',
    reason: 'Paper names the nether world after the level name while native servers identify it as minecraft:the_nether'
  },
  {
    id: 'end-name',
    pattern: '^(?:minecraft:overworld_the_end|minecraft:the_end)$',
    replacement: '<end>',
    reason: 'Paper names the end world after the level name while native servers identify it as minecraft:the_end'
  }
]

function at(x, y, z) {
  return [BASE[0] + x, BASE[1] + y, BASE[2] + z]
}

const LEFT = { button: 0, mode: 'PICKUP' }
const RIGHT = { button: 1, mode: 'PICKUP' }
const SHIFT_LEFT = { button: 0, mode: 'QUICK_MOVE' }
const SHIFT_RIGHT = { button: 1, mode: 'QUICK_MOVE' }
const MODE_IDS = { PICKUP: 0, QUICK_MOVE: 1, SWAP: 2, CLONE: 3, THROW: 4, QUICK_CRAFT: 5, PICKUP_ALL: 6 }

function click(slot, kind = LEFT) {
  return { type: 'click', slot, button: kind.button, mode: kind.mode }
}

function openPortal(portal) {
  return { type: 'open-portal', portal }
}

function chat(text) {
  return { type: 'chat', text }
}

function command(text) {
  return { type: 'command', command: text }
}

function both(text) {
  return { bukkit: text, native: text }
}

const HOME = openPortal('Alpha')

const PATHS = [
  {
    name: 'home',
    steps: [
      { id: 'home', action: HOME },
      { id: 'mode', action: click(24) },
      { id: 'home-from-mode-back', action: click(22) },
      { id: 'orientation', action: click(22) },
      { id: 'home-from-orientation-back', action: click(22) },
      { id: 'home-projection-off', action: click(13) },
      { id: 'home-projection-on', action: click(13) },
      { id: 'rename-prompt', action: click(20) },
      { id: 'home-after-rename-cancel', action: chat('cancel') }
    ]
  },
  {
    name: 'mode-escape',
    steps: [
      { id: 'home', action: HOME },
      { id: 'mode', action: click(24) },
      { id: 'mirror-rotate-without-mirror', action: click(17, RIGHT) },
      { id: 'home-after-mode-escape', action: { type: 'close' } }
    ]
  },
  {
    name: 'settings',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'advanced', action: click(14, SHIFT_LEFT) },
      { id: 'settings-from-advanced-back', action: click(40) },
      { id: 'settings-whitelist', action: click(10) },
      { id: 'settings-blacklist', action: click(10) },
      { id: 'settings-travel-outbound', action: click(12) },
      { id: 'settings-travel-inbound', action: click(12) },
      { id: 'settings-travel-locked', action: click(12) },
      { id: 'settings-travel-both', action: click(12) },
      { id: 'settings-sync-off', action: click(16) },
      { id: 'settings-sync-on', action: click(16) },
      { id: 'settings-range-up', action: click(20) },
      { id: 'settings-range-up-large', action: click(20, SHIFT_LEFT) },
      { id: 'settings-range-down', action: click(20, RIGHT) },
      { id: 'settings-range-down-large', action: click(20, SHIFT_RIGHT) },
      { id: 'settings-render-next', action: click(22) },
      { id: 'settings-render-back', action: click(22) },
      { id: 'settings-ambient-outline', action: click(24) },
      { id: 'settings-ambient-corners', action: click(24) },
      { id: 'settings-ambient-off', action: click(24) },
      { id: 'settings-ambient-sparks', action: click(24) },
      { id: 'settings-look-label-on', action: click(30) },
      { id: 'settings-look-label-off', action: click(30) },
      { id: 'home-from-settings-back', action: click(40) }
    ]
  },
  {
    name: 'settings-custom',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'settings-quality-performance', action: click(14) },
      { id: 'settings-quality-balanced', action: click(14) },
      { id: 'settings-quality-cinematic', action: click(14) },
      { id: 'settings-quality-custom', action: click(14) },
      { id: 'custom-depth-up', action: click(19) },
      { id: 'custom-heartbeat-up', action: click(21) },
      { id: 'custom-entities-up', action: click(23) },
      { id: 'custom-grace-up', action: click(25) },
      { id: 'custom-fallback-reset', action: click(31, RIGHT) },
      { id: 'advanced-from-custom', action: click(14, SHIFT_LEFT) },
      { id: 'custom-from-advanced-back', action: click(40) },
      { id: 'settings-quality-standard', action: click(14) }
    ]
  },
  {
    name: 'cosmetics',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'blackout-colors', action: click(18, RIGHT) },
      { id: 'blackout-colors-red', action: click(24) },
      { id: 'settings-from-blackout-back', action: click(31) },
      { id: 'settings-blackout-on', action: click(18) },
      { id: 'ambient-colors', action: click(24, RIGHT) },
      { id: 'ambient-red-up', action: click(11) },
      { id: 'ambient-green-up-large', action: click(13, SHIFT_LEFT) },
      { id: 'ambient-blue-down', action: click(15, RIGHT) },
      { id: 'ambient-wool-light-gray', action: click(27) },
      { id: 'settings-from-ambient-back', action: click(40) },
      { id: 'surface-skins', action: click(26, RIGHT) },
      { id: 'surface-skin-glass', action: click(12) },
      { id: 'surface-skin-clear', action: click(14) },
      { id: 'surface-skin-glass-again', action: click(12) },
      { id: 'settings-from-surface-back', action: click(22) },
      { id: 'settings-surface-cleared', action: click(26) },
      { id: 'settings-blackout-off', action: click(18) }
    ]
  },
  {
    name: 'cost',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'cost', action: click(32) },
      { id: 'cost-free', action: click(11) },
      { id: 'cost-vault-unavailable', action: click(15) },
      { id: 'settings-from-cost-back', action: click(31) }
    ]
  },
  {
    name: 'extensions',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'settings-from-extensions-back', action: click(22) },
      { id: 'extensions-again', action: click(34) },
      { id: 'transit', action: click(12) },
      { id: 'transit-momentum-next', action: click(11) },
      { id: 'transit-orientation-next', action: click(12) },
      { id: 'transit-membrane-toggle', action: click(13) },
      { id: 'transit-bounce-toggle', action: click(14) },
      { id: 'home-from-transit-back', action: click(22) }
    ]
  },
  {
    name: 'fidelity',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'fidelity', action: click(13) },
      { id: 'fidelity-atmosphere-next', action: click(10) },
      { id: 'fidelity-acoustics-next', action: click(12) },
      { id: 'fidelity-lod-next', action: click(14) },
      { id: 'fidelity-block-entities-next', action: click(16) },
      { id: 'fidelity-atmosphere-reset', action: click(10, SHIFT_LEFT) },
      { id: 'home-from-fidelity-back', action: click(22) }
    ]
  },
  {
    name: 'access',
    setup: { commands: [both('wormholes access key Alpha alpha')] },
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'access', action: click(9) },
      { id: 'access-unlisted', action: click(6) },
      { id: 'access-listed', action: click(6) }
    ]
  },
  {
    name: 'rules',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'rules-profile', action: click(10) },
      { id: 'rules-cooldown-up', action: click(1) },
      { id: 'rules-warmup-up-large', action: click(3, SHIFT_LEFT) },
      { id: 'rules-charges-up', action: click(7) },
      { id: 'rules-pushback-up', action: click(11) },
      { id: 'rules-sound-up', action: click(13) },
      { id: 'rules-default-toggle', action: click(15) },
      { id: 'rules-list', action: click(21) },
      { id: 'rules-profile-from-list-back', action: click(51) },
      { id: 'rules-templates', action: click(23) },
      { id: 'rules-profile-from-templates-back', action: click(51) }
    ]
  },
  {
    name: 'access-roles',
    setup: { commands: [both('wormholes access key Alpha alpha')], players: [PEER] },
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'access', action: click(9) },
      { id: 'access-add-prompt', action: click(3) },
      { id: 'access-with-peer', action: chat(PEER) },
      { id: 'access-peer-denied', action: click(9) },
      { id: 'access-peer-owner', action: click(9) },
      { id: 'access-peer-denied-again', action: click(9, RIGHT) },
      { id: 'access-peer-removed', action: click(9, SHIFT_LEFT) }
    ]
  },
  {
    name: 'rules-rule-page',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'rules-profile', action: click(10) },
      { id: 'rules-list', action: click(21) },
      { id: 'rules-add-rule-prompt', action: click(47) },
      { id: 'rules-list-with-rule', action: chat('golden') },
      { id: 'rules-rule-page', action: click(0) },
      { id: 'rules-add-line-prompt', action: click(47) },
      { id: 'rules-rule-page-with-line', action: chat('kind=PERMISSION;node=group.vip') },
      { id: 'rules-rule-page-line-removed', action: click(0, SHIFT_LEFT) },
      { id: 'rules-list-from-rule-back', action: click(51) },
      { id: 'rules-list-rule-removed', action: click(0, SHIFT_LEFT) }
    ]
  },
  {
    name: 'nexus-join',
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'network-unjoined', action: click(11) }
    ]
  },
  {
    name: 'nexus-dial',
    setup: {
      commands: [
        both('wormholes nexus delete Golden'),
        { bukkit: 'wormholes nexus create Golden visibility=public topology=mesh', native: 'wormholes nexus create Golden public mesh' },
        { bukkit: 'wormholes nexus add Golden Alpha address=ALPHA', native: 'wormholes nexus add Golden Alpha ALPHA' },
        { bukkit: 'wormholes nexus add Golden Beta address=BETA', native: 'wormholes nexus add Golden Beta BETA' }
      ]
    },
    steps: [
      { id: 'home', action: HOME },
      { id: 'settings', action: click(15) },
      { id: 'extensions', action: click(34) },
      { id: 'network', action: click(11) },
      { id: 'network-reciprocal-toggle', action: click(19) },
      { id: 'network-policy-next', action: click(21) },
      { id: 'network-redstone-next', action: click(23) },
      { id: 'dial', action: click(16) }
    ]
  },
  {
    name: 'destination',
    steps: [
      { id: 'home', action: HOME },
      { id: 'destinations', action: click(11) },
      { id: 'destinations-sort-name', action: click(47) },
      { id: 'destinations-sort-world', action: click(47) },
      { id: 'destinations-sort-distance', action: click(47) },
      { id: 'destinations-sort-smart', action: click(47) },
      { id: 'home-after-destination-escape', action: { type: 'close' } },
      { id: 'destinations-again', action: click(11) },
      { id: 'destination-linked', action: click(0) },
      { id: 'home-linked', action: HOME },
      { id: 'destinations-linked', action: click(11) },
      { id: 'destination-return-linked', action: click(51) },
      { id: 'home-return-linked', action: HOME }
    ]
  },
  {
    name: 'gateway',
    steps: [
      { id: 'home', action: HOME },
      { id: 'mode', action: click(24) },
      { id: 'mode-gateway-selected', action: click(13) },
      { id: 'home-gateway', action: HOME },
      { id: 'gateway-pair', action: click(11) },
      { id: 'home-from-gateway-back', action: click(22) },
      { id: 'gateway-pair-again', action: click(11) },
      { id: 'gateway-destinations', action: click(13) },
      { id: 'home-after-gateway-destination-escape', action: { type: 'close' } }
    ]
  },
  {
    name: 'wormhole-mirror',
    steps: [
      { id: 'home', action: HOME },
      { id: 'mode', action: click(24) },
      { id: 'mode-wormhole-selected', action: click(11) },
      { id: 'home-wormhole', action: HOME },
      { id: 'mode-wormhole', action: click(24) },
      { id: 'mode-mirror-selected', action: click(17) },
      { id: 'home-mirror', action: HOME },
      { id: 'mode-mirror', action: click(24) },
      { id: 'mirror-rotate-clockwise', action: click(17, RIGHT) },
      { id: 'mirror-rotate-counterclockwise', action: click(17, SHIFT_RIGHT) },
      { id: 'mode-portal-selected', action: click(9) }
    ]
  },
  {
    name: 'rtp',
    steps: [
      { id: 'home', action: HOME },
      { id: 'mode', action: click(24) },
      { id: 'mode-rtp-selected', action: click(15) },
      { id: 'home-rtp', action: HOME },
      { id: 'rtp-overview', action: click(11) },
      { id: 'rtp-destination', action: click(19), unordered: [[10, 12, 14, 16, 19, 21, 23, 25]] },
      { id: 'rtp-overview-from-destination', action: click(49) },
      { id: 'rtp-landing', action: click(21) },
      { id: 'rtp-overview-from-landing', action: click(49) },
      { id: 'rtp-routing', action: click(23) },
      { id: 'rtp-overview-from-routing', action: click(49) },
      { id: 'rtp-effects', action: click(25) },
      { id: 'rtp-overview-from-effects', action: click(49) },
      { id: 'home-from-rtp-back', action: click(49) }
    ]
  },
  {
    name: 'rtp-pages',
    steps: [
      { id: 'home', action: HOME },
      { id: 'mode', action: click(24) },
      { id: 'mode-rtp-selected', action: click(15) },
      { id: 'home-rtp', action: HOME },
      { id: 'rtp-overview', action: click(11) },
      { id: 'rtp-destination', action: click(19), unordered: [[10, 12, 14, 16, 19, 21, 23, 25]] },
      { id: 'rtp-min-radius', action: click(41) },
      { id: 'rtp-min-radius-up', action: click(24) },
      { id: 'rtp-min-radius-down-large', action: click(18) },
      { id: 'rtp-destination-from-numeric', action: click(49), unordered: [[10, 12, 14, 16, 19, 21, 23, 25]] },
      { id: 'rtp-biomes', action: click(52) },
      { id: 'rtp-destination-from-biomes', action: click(49), unordered: [[10, 12, 14, 16, 19, 21, 23, 25]] },
      { id: 'rtp-overview-from-destination', action: click(49) },
      { id: 'rtp-landing', action: click(21) },
      { id: 'rtp-lower-y', action: click(29) },
      { id: 'rtp-landing-from-numeric', action: click(49) },
      { id: 'rtp-overview-from-landing', action: click(49) },
      { id: 'rtp-routing', action: click(23) },
      { id: 'rtp-reroll-confirm', action: click(40) },
      { id: 'rtp-routing-from-confirm-cancel', action: click(24) },
      { id: 'rtp-overview-from-routing', action: click(49) }
    ]
  },
  {
    name: 'atlas',
    setup: { idleTicks: 140 },
    steps: [
      { id: 'atlas', action: command('atlas') },
      { id: 'atlas-sort-name', action: click(46) },
      { id: 'atlas-sort-world', action: click(46) },
      { id: 'atlas-sort-distance', action: click(46) },
      { id: 'atlas-sort-smart', action: click(46) },
      { id: 'atlas-favorite-first', action: click(0, RIGHT) },
      { id: 'atlas-favorites-only', action: click(47) },
      { id: 'atlas-all', action: click(47) },
      { id: 'atlas-recents-only', action: click(48) },
      { id: 'atlas-all-again', action: click(48) }
    ]
  },
  {
    name: 'door',
    setup: {
      commands: [{ bukkit: 'wormholes door type=personal', native: 'wormholes door personal' }],
      idleTicks: 40,
      actions: [{ type: 'use-item-on', item: 'minecraft:dark_oak_door', target: DOOR_SUPPORT, face: 'up' }]
    },
    steps: [
      { id: 'door-access', action: { type: 'sneak-use', target: [DOOR_SUPPORT[0], DOOR_SUPPORT[1] + 1, DOOR_SUPPORT[2]] } },
      { id: 'door-access-closed', action: click(4) },
      { id: 'door-access-open', action: click(4) },
      { id: 'door-access-projection-next', action: click(6) },
      { id: 'door-access-projection-next-again', action: click(6) }
    ]
  }
]

const NAMED_COLORS = {
  black: '#000000', dark_blue: '#0000aa', dark_green: '#00aa00', dark_aqua: '#00aaaa',
  dark_red: '#aa0000', dark_purple: '#aa00aa', gold: '#ffaa00', gray: '#aaaaaa',
  dark_gray: '#555555', blue: '#5555ff', green: '#55ff55', aqua: '#55ffff',
  red: '#ff5555', light_purple: '#ff55ff', yellow: '#ffff55', white: '#ffffff'
}
const HEX_TO_NAME = Object.fromEntries(Object.entries(NAMED_COLORS).map(([name, hex]) => [hex, name]))
const FLAGS = ['bold', 'italic', 'underlined', 'strikethrough', 'obfuscated']
const CONTEXTS = {
  title: { italic: false },
  name: { italic: true },
  itemName: { italic: false },
  lore: { italic: true, color: 'dark_purple' }
}

function nbtToJson(tag) {
  if (tag === null || tag === undefined || typeof tag !== 'object' || !('type' in tag)) return tag
  switch (tag.type) {
    case 'compound': {
      const result = {}
      for (const [key, value] of Object.entries(tag.value)) result[key] = nbtToJson(value)
      return result
    }
    case 'list':
      return tag.value.value.map(entry => nbtToJson({ type: tag.value.type, value: entry }))
    default:
      return tag.value
  }
}

function normalizeColor(color) {
  if (typeof color !== 'string') return undefined
  const lower = color.toLowerCase()
  return HEX_TO_NAME[lower] ?? lower
}

function inherit(parent, node) {
  const style = { color: parent.color, flags: { ...parent.flags } }
  if (node && typeof node === 'object' && !Array.isArray(node)) {
    const color = normalizeColor(node.color)
    if (color !== undefined) style.color = color
    for (const flag of FLAGS) {
      const value = node[flag]
      if (value !== undefined && value !== null) style.flags[flag] = value === true || value === 1 || value === '1' || value === 'true'
    }
  }
  return style
}

function flatten(node, style, output) {
  if (node === null || node === undefined) return
  if (typeof node !== 'object') {
    output.push({ text: String(node), style })
    return
  }
  if (Array.isArray(node)) {
    if (node.length === 0) return
    const parent = inherit(style, node[0])
    flatten(node[0], style, output)
    for (const child of node.slice(1)) flatten(child, parent, output)
    return
  }
  const own = inherit(style, node)
  let text = ''
  if (typeof node.text === 'string') text = node.text
  else if (typeof node.translate === 'string') {
    const rendered = []
    if (Array.isArray(node.with)) flatten(node.with, own, rendered)
    text = `<translate:${node.translate}${rendered.length ? ':' + rendered.map(entry => entry.text).join('|') : ''}>`
  } else if (typeof node.keybind === 'string') text = `<keybind:${node.keybind}>`
  output.push({ text, style: own })
  if (Array.isArray(node.extra)) for (const child of node.extra) flatten(child, own, output)
}

function normalizeText(text) {
  let result = text
  for (const rule of NORMALIZATIONS) result = result.replace(new RegExp(rule.pattern, 'g'), rule.replacement)
  return result
}

function canonicalText(component, context) {
  const defaults = CONTEXTS[context]
  const raw = []
  flatten(component, { color: undefined, flags: {} }, raw)
  const segments = []
  for (const entry of raw) {
    if (entry.text.length === 0) continue
    const segment = { text: entry.text }
    const color = entry.style.color ?? defaults.color
    if (color !== undefined && color !== defaults.color) segment.color = color
    for (const flag of FLAGS) {
      const value = entry.style.flags[flag] ?? (flag === 'italic' ? defaults.italic : false)
      if (value) segment[flag] = true
    }
    const previous = segments[segments.length - 1]
    if (previous && previous.color === segment.color && FLAGS.every(flag => Boolean(previous[flag]) === Boolean(segment[flag]))) {
      previous.text += segment.text
    } else {
      segments.push(segment)
    }
  }
  for (const segment of segments) segment.text = normalizeText(segment.text)
  return segments
}

function formatJson(value, indent = '') {
  const compact = JSON.stringify(value)
  if (value === null || typeof value !== 'object' || compact.length <= 120 || (!Array.isArray(value) && 'slot' in value)) return compact
  const inner = `${indent}  `
  if (Array.isArray(value)) return `[\n${value.map(entry => inner + formatJson(entry, inner)).join(',\n')}\n${indent}]`
  return `{\n${Object.entries(value).map(([key, entry]) => `${inner}${JSON.stringify(key)}: ${formatJson(entry, inner)}`).join(',\n')}\n${indent}}`
}

function component(item, type) {
  const entry = item.components?.find(candidate => candidate.type === type)
  return entry === undefined ? undefined : entry.data
}

function canonicalSlot(slot, item) {
  if (!item) return { slot, item: 'minecraft:air', count: 0, glint: false }
  const result = { slot, item: `minecraft:${item.name}`, count: item.count }
  const override = component(item, 'enchantment_glint_override')
  const enchantments = component(item, 'enchantments')
  const enchanted = enchantments !== undefined && JSON.stringify(nbtToJson(enchantments)) !== '{}' &&
    !(Array.isArray(enchantments?.enchantments) && enchantments.enchantments.length === 0) &&
    !(Array.isArray(enchantments) && enchantments.length === 0)
  const tracked = item.name === 'compass' && component(item, 'lodestone_tracker') !== undefined
  result.glint = override !== undefined ? Boolean(override) : enchanted || tracked || DEFAULT_GLINT_ITEMS.has(result.item)
  const customName = component(item, 'custom_name')
  const itemName = component(item, 'item_name')
  if (customName !== undefined) result.name = canonicalText(nbtToJson(customName), 'name')
  else if (itemName !== undefined) result.name = canonicalText(nbtToJson(itemName), 'itemName')
  const lore = component(item, 'lore')
  if (Array.isArray(lore) && lore.length > 0) result.lore = lore.map(line => canonicalText(nbtToJson(line), 'lore'))
  return result
}

export default {
  name: 'wormholes-menu-parity',
  description: 'Walk every Wormholes inventory menu and record canonical window dumps for native parity goldens.',
  async run(context) {
    const bot = context.bot
    const Vec3 = bot.entity.position.constructor
    context.expect(bot.username === PLAYER, `Menu parity capture requires --username ${PLAYER}`)
    context.expect(context.server.instance.startsWith('wh-menu-golden'), 'Menu parity capture requires a disposable wh-menu-golden instance')
    let sequence = 10
    let stateId = -1
    let lastWindowPacket = Date.now()
    let opened = 0
    let lastTitle
    bot._client.on('window_items', packet => { stateId = packet.stateId; lastWindowPacket = Date.now() })
    bot._client.on('set_slot', packet => { stateId = packet.stateId; lastWindowPacket = Date.now() })
    bot._client.on('open_window', packet => { opened++; lastTitle = packet.windowTitle; lastWindowPacket = Date.now() })
    bot._client.on('close_window', () => { lastWindowPacket = Date.now() })
    bot._client.on('system_chat', packet => {
      const message = nbtToJson(packet.content)
      if (message?.translate !== 'commands.time.query.gametime' || !Array.isArray(message.with)) return
      const value = message.with[0]
      const ticks = Array.isArray(value) ? value[0] * 4294967296 + (value[1] >>> 0) : Number(value)
      bot.emit('menuParityGameTime', ticks)
    })

    const vec = values => new Vec3(values[0], values[1], values[2])

    async function serverTick() {
      const reply = context.waitForEvent('menuParityGameTime', () => true, 5000)
      bot.chat('/time query gametime')
      const [ticks] = await reply
      return ticks
    }

    async function settle() {
      const started = await serverTick()
      while (await serverTick() < started + SETTLE_TICKS) await context.sleep(50)
      const quietStart = Date.now()
      while (Date.now() - lastWindowPacket < SETTLE_QUIET_MS) {
        if (Date.now() - quietStart > SETTLE_MAX_MS) throw new Error('Window traffic did not settle')
        await context.sleep(50)
      }
    }

    async function lookClick(cell) {
      await bot.lookAt(vec(cell).offset(0.5, 0.5, 0.5), true)
      bot._client.write('block_dig', { status: 0, location: vec(cell), face: 2, sequence: sequence++ })
    }

    async function teleport(position) {
      await context.command(`/tp @s ${position[0]} ${position[1]} ${position[2]}`, /teleported/i)
      await context.waitUntil(() => bot.entity.position.distanceTo(vec(position)) < 0.5, { timeoutMs: 5000, label: 'teleport arrival' })
    }

    async function closeWindow() {
      if (bot.currentWindow) {
        bot.closeWindow(bot.currentWindow)
        await settle()
      }
    }

    async function equipWand() {
      let wand = bot.inventory.items().find(item => item.name === 'blaze_rod')
      if (!wand) {
        await context.command('/wormholes wand', /Wand.*granted/i)
        wand = await context.waitUntil(() => bot.inventory.items().find(item => item.name === 'blaze_rod'), { timeoutMs: 5000, label: 'wand delivery' })
      }
      await bot.equip(wand, 'hand')
    }

    async function buildPortal(portal) {
      const from = vec(portal.from)
      const to = vec(portal.to)
      await context.command(`/fill ${portal.from.join(' ')} ${portal.to.join(' ')} stone`, /filled|changed|blocks|No blocks/i)
      await teleport([from.x + 0.5, from.y, from.z - 2.5])
      await context.waitUntil(() => bot.blockAt(from)?.name === 'stone' && bot.blockAt(to)?.name === 'stone', { timeoutMs: 10000, label: 'portal blocks received' })
      await equipWand()
      const cornerA = context.waitForMessage(/Corner A set/i, 5000)
      await lookClick(portal.from)
      await cornerA
      const cornerB = context.waitForMessage(/Selected|cells|blocks/i, 5000)
      cornerB.catch(() => {})
      await bot.lookAt(to.offset(0.5, 0.5, 0.5), true)
      await bot.activateBlock(bot.blockAt(to))
      await cornerB
      const built = context.waitForMessage(/Portal opened/i, 10000)
      await lookClick(portal.from)
      await built
      await settle()
      const menu = context.waitForEvent('windowOpen', window => window.slots.length > 36, 10000)
      await lookClick(portal.from)
      await menu
      await settle()
      const reopened = context.waitForEvent('windowOpen', window => window.slots.length > 36, 10000)
      await clickRaw(20, LEFT)
      await context.waitUntil(() => !bot.currentWindow, { timeoutMs: 5000, label: 'rename prompt' })
      bot.chat(portal.name)
      await reopened
      await settle()
      await closeWindow()
    }

    async function useItemOn(action) {
      const itemName = action.item.replace('minecraft:', '')
      const item = await context.waitUntil(() => bot.inventory.items().find(candidate => candidate.name === itemName), { timeoutMs: 5000, label: `${itemName} delivery` })
      await bot.equip(item, 'hand')
      const support = bot.blockAt(vec(action.target))
      const placed = context.waitUntil(() => bot.blockAt(vec(action.target).offset(0, 1, 0))?.name === itemName, { timeoutMs: 5000, label: `${itemName} placement` })
      await bot.activateBlock(support, new Vec3(0, 1, 0))
      await placed
    }

    async function sneakUse(action) {
      const empty = [...Array(9).keys()].find(index => !bot.inventory.slots[bot.inventory.hotbarStart + index])
      context.expect(empty !== undefined, 'No empty hotbar slot for an empty-hand interaction')
      bot.setQuickBarSlot(empty)
      bot.setControlState('sneak', true)
      await context.sleep(250)
      await bot.activateBlock(bot.blockAt(vec(action.target)))
      await context.sleep(250)
      bot.setControlState('sneak', false)
    }

    async function resetWorld(setup) {
      await closeWindow()
      await context.command('/wormholes admin deleteallportals', /Deleted/i)
      await context.command('/kill @e[type=!player]', /Killed|No entity/i)
      await context.command('/clear', /Removed|No items/i)
      if (bot.game.gameMode !== 'creative') {
        await context.command('/gamemode creative')
        await context.waitUntil(() => bot.game.gameMode === 'creative', { timeoutMs: 5000, label: 'creative mode' })
      }
      await teleport(STAND)
      await context.command(`/forceload add ${CLEARED[0][0]} ${CLEARED[0][2]} ${CLEARED[1][0]} ${CLEARED[1][2]}`, /force load/i)
      await context.waitUntil(async () => /filled|No blocks/i.test(await context.command(`/fill ${CLEARED[0].join(' ')} ${CLEARED[1].join(' ')} air`, /filled|changed|blocks|not loaded/i)),
        { timeoutMs: 10000, intervalMs: 500, label: 'capture area loaded' })
      for (const [from, to] of PLATFORMS) {
        await context.command(`/fill ${from.join(' ')} ${to.join(' ')} stone`, /filled|changed|blocks|No blocks/i)
      }
      for (const portal of PORTALS) await buildPortal(portal)
      await teleport(STAND)
      for (const name of setup.players) {
        if (!peers.has(name)) peers.set(name, await context.connectActor(name))
      }
      for (const entry of setup.commands) {
        bot.chat(`/${entry.bukkit}`)
        await context.sleep(600)
      }
      for (const action of setup.actions) {
        if (action.type === 'use-item-on') await useItemOn(action)
        else throw new Error(`Unknown setup action ${action.type}`)
      }
      await equipWand()
      await context.sleep(Math.max(500, setup.idleTicks * 50))
    }

    async function clickRaw(slot, kind) {
      const window = bot.currentWindow
      context.expect(window, `No open window to click slot ${slot}`)
      bot._client.write('window_click', {
        windowId: window.id,
        stateId,
        slot,
        mouseButton: kind.button,
        mode: MODE_IDS[kind.mode],
        changedSlots: [],
        cursorItem: { itemCount: 0, components: [], removeComponents: [] }
      })
    }

    async function perform(action) {
      switch (action.type) {
        case 'open-portal': {
          const portal = PORTALS.find(candidate => candidate.name === action.portal)
          await lookClick(portal.from)
          return
        }
        case 'click':
          await clickRaw(action.slot, action)
          return
        case 'chat':
          bot.chat(action.text)
          return
        case 'command':
          bot.chat(`/${action.command}`)
          return
        case 'close':
          bot.closeWindow(bot.currentWindow)
          return
        case 'sneak-use':
          await sneakUse(action)
          return
        default:
          throw new Error(`Unknown action ${action.type}`)
      }
    }

    function snapshot(windowBefore) {
      const window = bot.currentWindow
      if (!window) return { transition: 'closed', window: null }
      const transition = opened > 0 || window.id !== windowBefore ? 'opened' : 'updated'
      const size = window.inventoryStart
      const slots = []
      for (let slot = 0; slot < size; slot++) slots.push(canonicalSlot(slot, window.slots[slot]))
      return {
        transition,
        window: {
          menu: window.type,
          rows: size / 9,
          title: canonicalText(nbtToJson(lastTitle), 'title'),
          slots
        }
      }
    }

    const captured = []
    const peers = new Map()
    const write = () => {
      const output = process.env.WORMHOLES_MENU_PARITY_OUT
      if (!output) return
      const golden = {
        format: 'wormholes-menu-parity/1',
        capturedFrom: { platform: 'paper', minecraft: context.server.minecraftVersion ?? bot.version, levelName: LEVEL_NAME, scenario: 'src/gameplay/menu-parity.mjs' },
        player: PLAYER,
        canonicalForm: {
          segment: 'text plus color and style flags after resolving inherited style; unset flags and colors resolve to the vanilla default of the context',
          contexts: CONTEXTS,
          glint: 'visible foil: enchantment_glint_override when present, otherwise enchantments, a lodestone-tracked compass, or an item whose default components carry the glint override',
        defaultGlintItems: [...DEFAULT_GLINT_ITEMS],
          name: 'custom_name from the item patch, otherwise item_name from the item patch, otherwise absent',
          transition: 'opened when a new container was sent after the action, updated when the same container was refreshed, closed when no container is open',
        unordered: 'slot groups whose listing order follows platform world registration order; compared as a sorted multiset'
        },
        normalizations: NORMALIZATIONS,
        paths: captured
      }
      mkdirSync(dirname(output), { recursive: true })
      writeFileSync(output, `${formatJson(golden)}\n`)
    }
    const only = process.env.WORMHOLES_MENU_PARITY_PATHS ? process.env.WORMHOLES_MENU_PARITY_PATHS.split(',') : null
    for (const path of PATHS.filter(candidate => !only || only.includes(candidate.name))) {
      const setup = { commands: path.setup?.commands ?? [], actions: path.setup?.actions ?? [], players: path.setup?.players ?? [], idleTicks: path.setup?.idleTicks ?? 0 }
      await context.step(`path ${path.name}`, async () => {
        await resetWorld(setup)
        const steps = []
        for (const step of path.steps) {
          const windowBefore = bot.currentWindow?.id ?? 0
          opened = 0
          await perform(step.action)
          await settle()
          const result = snapshot(windowBefore)
          steps.push({ id: step.id, action: step.action, ...(step.unordered ? { unordered: step.unordered } : {}), ...result })
        }
        await closeWindow()
        captured.push({
          name: path.name,
          setup: {
            gameMode: 'creative',
            cleared: CLEARED,
            platforms: PLATFORMS,
            portals: PORTALS.map(portal => ({ name: portal.name, from: portal.from, to: portal.to, type: portal.type, look: portal.look })),
            player: STAND,
            players: setup.players,
            commands: setup.commands,
            actions: setup.actions,
            idleTicks: setup.idleTicks
          },
          steps
        })
        write()
      })
    }

    context.report.menuParity = { paths: captured.length, windows: captured.reduce((total, path) => total + path.steps.length, 0) }
  }
}
