import { createRequire } from 'node:module'

export default {
  name: 'native-menu-chest-fallback',
  description: 'Opens the portal menu from a vanilla client and verifies the server sends the chest inventory instead of a native menu.',
  async run(context) {
    const require = createRequire(process.argv[1])
    const nbt = require('prismarine-nbt')
    const ChatMessage = require('prismarine-chat')(context.bot.version)
    const bot = context.bot
    const payloads = []
    const evidence = { jarSha256: process.env.WORMHOLES_JAR_SHA256, wormholesPayloads: payloads }
    context.report.nativeMenuFallback = evidence

    function plain(value) {
      if (value === null || value === undefined) return ''
      const component = value.type ? nbt.simplify(value) : value
      return new ChatMessage(component).toString().replace(/§./g, '')
    }

    function items() {
      const window = bot.currentWindow
      if (!window) return []
      return window.slots.slice(0, window.inventoryStart).flatMap((item, slot) => {
        if (!item) return []
        return [{ slot, material: item.name, name: plain(item.customName) || item.displayName }]
      })
    }

    function decode(value) {
      return decodeURIComponent(value.replace(/\+/g, ' '))
    }

    async function query(command, prefix) {
      const message = await context.command(command, new RegExp('^\\[whqa\\] (?:error |' + prefix.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ' )'), 5000)
      context.expect(!message.startsWith('[whqa] error '), `${command} succeeded`, message)
      return message.slice(`[whqa] ${prefix} `.length)
    }

    const onPayload = (packet) => {
      if (String(packet.channel).startsWith('wormholes:')) payloads.push(String(packet.channel))
    }
    bot._client.on('custom_payload', onPayload)

    try {
      await context.step('Create and stage the fixture portal', async () => {
        await context.command('/whtest setup', /FIXTURE ready/, 10000)
        await context.command('/whtest stage', /FIXTURE staged true/, 10000)
      })

      const portal = await context.step('Read the fixture portal through the tagged query replies', async () => {
        const [count, ...entries] = (await query('/whtest portal list', 'portal-list')).split(' ')
        context.expect(Number(count) === entries.length && entries.length > 0, 'Portal list announces every listed portal', { count, entries })
        const portals = entries.map((entry) => {
          const [id, mode, owner, name] = entry.split(',')
          return { id, mode, owner, name: decode(name) }
        })
        const fixture = portals.find((candidate) => candidate.name.startsWith('Transfer fixture '))
        context.expect(Boolean(fixture), 'Portal list contains the fixture portal', portals)
        context.expect(fixture.mode === 'GATEWAY', 'Fixture portal reports GATEWAY mode', fixture)
        const projection = await query(`/whtest portal get ${fixture.id} projection`, `portal-get ${fixture.id} projection`)
        context.expect(projection === 'off', 'Fixture portal reports projection off', projection)
        const permitted = await query('/whtest perm wormholesfixture.use', 'perm wormholesfixture.use')
        context.expect(permitted === 'true', 'Bot holds the fixture permission', permitted)
        evidence.portal = { ...fixture, projection }
        return fixture
      })

      const window = await context.step('Open the portal menu', async () => {
        if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
        const opened = context.waitForEvent('windowOpen', (candidate) => candidate.slots.length > 36, 10000)
        bot.chat('/whtest menu')
        const [candidate] = await opened
        return candidate
      })

      await context.step('Verify the chest inventory contents', async () => {
        context.expect(bot.currentWindow === window, 'The opened chest is the current window', { type: window.type })
        context.expect(window.slots.length > 36, 'Portal menu window has more than 36 slots', { slots: window.slots.length, type: window.type })
        context.expect(window.inventoryStart >= 27, 'Portal menu window has a chest-sized container section', { inventoryStart: window.inventoryStart })
        const current = await context.waitUntil(() => {
          const listed = items()
          return listed.some((item) => /^Settings$/.test(item.name)) ? listed : null
        }, { timeoutMs: 7000, label: 'portal menu items' })
        evidence.window = { type: window.type, title: plain(window.title), slots: window.slots.length, inventoryStart: window.inventoryStart, items: current }
        context.expect(evidence.window.title.length > 0, 'Portal menu window has a title', evidence.window)
      })

      await context.step('Confirm no native menu payload reached the vanilla client', async () => {
        await context.sleep(1000)
        context.expect(payloads.length === 0, 'No wormholes: custom payload reached the vanilla client', payloads)
        context.expect(bot.currentWindow === window, 'The chest stays open without a native menu replacing it', { portal: portal.id })
      })

      context.expect(!bot._client.ended, 'Player remains connected after the menu opened')
    } finally {
      bot._client.removeListener('custom_payload', onPayload)
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      bot.clearControlStates()
    }
  }
}
