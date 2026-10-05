import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { portalWorkflow } from './support/portal-workflow.mjs'

export default {
  name: 'wormholes-atlas-persistence',
  description: 'Discover linked portals, cross both ways, pin a destination, and retain favorites, recents, and guidance after reconnecting.',
  async run(initial) {
    let context = initial
    let workflow = portalWorkflow(context)
    const prefix = `qa_atlas_${Date.now().toString(36)}`
    const names = [`${prefix}_a`, `${prefix}_b`]
    const evidence = { names, crossings: [] }
    context.report.atlasPersistence = evidence
    const stateFile = path.join(context.server.directory, 'plugins/Wormholes/atlas/players', `${context.bot.player.uuid}.json`)
    const saved = async () => JSON.parse(await readFile(stateFile, 'utf8'))
    const open = async (command, expected) => {
      if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
      context.bot.chat(command)
      await workflow.p.wait(() => expected.every(name => workflow.p.items().some(item => item.name === name)), command)
      context.expect(/Atlas/.test(workflow.p.text(context.bot.currentWindow.title)), 'Atlas menu is open')
    }
    try {
      await context.step('prepare loaded isolated arena', async () => {
        if (context.bot.game.gameMode !== 'creative') await context.command('/gamemode creative @s', /creative/i)
        await context.command('/tp @s 0.5 202 0.5', /Teleported/)
        await context.waitUntil(() => [-8, 40].every(x => [-8, 12].every(z =>
          context.bot.blockAt(workflow.p.point(x, 199, z)))), { timeoutMs: 30000, label: 'arena chunks loaded' })
        await workflow.p.arena()
        await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
        await context.command('/wh wand rune=false', /wand/i)
      })
      for (const [index, x] of [0, 24].entries()) {
        await context.step(`build portal ${index + 1}`, () => workflow.build(x, names[index]))
      }
      await context.step('link both directions and select Wormhole mode', async () => {
        await workflow.link(0, names[1])
        await workflow.link(24, names[0])
        await workflow.wormhole(0)
        await workflow.wormhole(24)
      })
      for (const [source, destination] of [[0, 24], [24, 0]]) {
        await context.step(`cross portal at ${source}`, async () => {
          await workflow.p.stage(source + 0.5, 200, 3.5)
          await workflow.p.emptyHand()
          await workflow.p.walk(0, () => Math.abs(context.bot.entity.position.x - destination - 0.5) < 2, 'portal arrival')
          evidence.crossings.push({ source, destination, position: { ...context.bot.entity.position } })
          await context.sleep(700)
          context.expect(Math.abs(context.bot.entity.position.x - destination - 0.5) < 2, 'Traveler remains at destination')
        })
      }
      await context.step('discover, favorite, and guide through the atlas', async () => {
        await open('/atlas', names)
        const row = workflow.p.items().find(item => item.name === names[1])
        await workflow.p.bounded(() => context.bot.clickWindow(row.slot, 1, 0), 'pin destination')
        await open('/atlas favorites', [names[1]])
        context.expect(!workflow.p.items().some(item => item.name === names[0]), 'Favorites excludes unpinned portal')
        context.bot.closeWindow(context.bot.currentWindow)
        await context.command(`/atlas guide ${names[1]}`, /Guiding to/)
        await context.waitUntil(async () => {
          try {
            const state = await saved()
            return state.favorites.length === 1 && state.recents.length === 2 && state.guide === state.favorites[0]
          } catch { return false }
        }, { timeoutMs: 15000, label: 'dirty atlas saved' })
        evidence.beforeReconnect = await saved()
      })
      await context.step('reconnect and restore favorites, recents, and guide', async () => {
        const created = [...workflow.created]
        const previous = context
        context = await previous.reconnectAfter(() => previous.bot.quit('Atlas persistence reconnect'))
        workflow = portalWorkflow(context)
        workflow.created.push(...created)
        await open('/atlas favorites', [names[1]])
        context.expect(workflow.p.items().some(item => item.name.includes(`Guide: ${names[1]}`)), 'Guide target restored')
        await open('/atlas recents', names)
        evidence.afterReconnect = await saved()
        context.expect(JSON.stringify(evidence.beforeReconnect) === JSON.stringify(evidence.afterReconnect), 'Saved state survives reconnect unchanged')
        context.bot.closeWindow(context.bot.currentWindow)
        await context.command('/atlas guide off', /Guide off/)
        await context.waitUntil(async () => !(await saved()).guide, { timeoutMs: 15000, label: 'cleared guide saved' })
      })
    } finally {
      await workflow.cleanup()
    }
  }
}
