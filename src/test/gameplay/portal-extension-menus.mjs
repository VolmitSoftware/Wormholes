import { createRequire } from 'node:module'

export default {
  name: 'portal-extension-menus',
  description: 'Opens the portal settings extension grid, checks Access details, and visits every feature submenu.',
  async run(context) {
    const require = createRequire(process.argv[1])
    const nbt = require('prismarine-nbt')
    const ChatMessage = require('prismarine-chat')(context.bot.version)
    const evidence = { jarSha256: process.env.WORMHOLES_JAR_SHA256, windows: [] }
    context.report.portalMenus = evidence

    function plain(value) {
      if (value === null || value === undefined) return ''
      const component = value.type ? nbt.simplify(value) : value
      return new ChatMessage(component).toString().replace(/§./g, '')
    }

    function items() {
      const window = context.bot.currentWindow
      if (!window) return []
      return window.slots.slice(0, window.inventoryStart).flatMap((item, slot) => {
        if (!item) return []
        const name = plain(item.customName) || item.displayName
        const lore = (item.componentMap?.get('lore')?.data ?? []).map(plain)
        return [{ slot, material: item.name, name, lore }]
      })
    }

    async function waitForItems(patterns, description) {
      const deadline = Date.now() + 7000
      while (Date.now() < deadline) {
        const current = items()
        if (patterns.every(pattern => current.some(item => pattern.test(item.name)))) {
          context.expect(current.every(item => !/\{(?:portal|owner|count|key|name|address)\}/.test([item.name, ...item.lore].join(' '))),
            'Inventory text contains no unresolved placeholders', current)
          evidence.windows.push({ description, title: plain(context.bot.currentWindow.title), items: current })
          return current
        }
        await context.sleep(50)
      }
      context.expect(false, `Inventory did not show ${description}`, items())
    }

    async function click(pattern, expected, description) {
      const item = items().find(candidate => pattern.test(candidate.name))
      context.expect(Boolean(item), `Inventory contains ${pattern}`, items())
      await context.bot.clickWindow(item.slot, 0, 0)
      return waitForItems(expected, description)
    }

    async function openExtensions() {
      if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
      context.bot.chat('/whtest menu')
      await waitForItems([/^Settings$/], 'portal menu')
      await click(/^Settings$/, [/^More settings$/], 'portal settings')
      return click(/^More settings$/, [/^Access$/, /^Network$/, /^Rules$/, /^Transit$/, /^Fidelity$/], 'all five extension tiles')
    }

    try {
      await context.step('Create a manageable fixture portal', async () => {
        await context.command('/whtest setup', /FIXTURE ready/, 10000)
        await context.command('/whtest stage', /FIXTURE staged true/, 10000)
      })
      await context.step('Open Settings and More settings', openExtensions)
      await context.step('Open Access and verify role, owner, key, and group controls', async () => {
        const current = await click(/^Access$/, [/^Access: /, /^Add a player$/, /^Permission key$/, /^Allowed groups$/, /^Public directory$/], 'Access submenu')
        const placard = current.find(item => /^Access: /.test(item.name))
        context.expect(placard.lore.some(line => line.includes(`Owner: ${context.bot.username}`)), 'Access header names the portal owner', placard)
        context.expect(placard.lore.some(line => /Roles: 0/.test(line)), 'Access header renders the role count', placard)
        context.expect(placard.lore.some(line => /Key: \S+/.test(line)), 'Access header renders the stable permission key', placard)
      })
      for (const entry of [
        { label: /^Network$/, expected: [/^Network: /, /^Join a network$/], name: 'Network submenu' },
        { label: /^Rules$/, expected: [/^Cooldown:/, /^Warmup:/], name: 'Rules submenu' },
        { label: /^Transit$/, expected: [/^Momentum:/, /^Orientation:/], name: 'Transit submenu' },
        { label: /^Fidelity$/, expected: [/^Projection fidelity$/, /^Atmosphere:/, /^Acoustics:/], name: 'Fidelity submenu' }
      ]) {
        await context.step(`Open ${entry.name}`, async () => {
          await openExtensions()
          await click(entry.label, entry.expected, entry.name)
        })
      }
      context.expect(!context.bot._client.ended, 'Player remains connected after every menu')
    } finally {
      if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
      context.bot.clearControlStates()
    }
  }
}
