import { portalWorkflow } from './support/portal-workflow.mjs'
import { storedPortals } from './support/portal-storage.mjs'
import { readFile, readdir } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'wormholes-public-bulk-delete',
  description: 'Build two portals with the wand, join a network, and verify bulk deletion clears persisted portals and membership.',
  async run(context) {
    const { p, build } = portalWorkflow(context)
    const prefix = `bulk_${Date.now().toString(36)}`
    const folder = path.join(context.server.directory, 'plugins', 'Wormholes')
    const networks = path.join(folder, 'atlas', 'networks')
    const readNetwork = async () => {
      for (const name of await readdir(networks)) {
        if (!name.endsWith('.json')) continue
        const value = JSON.parse(await readFile(path.join(networks, name), 'utf8'))
        if (value.name === prefix) return value
      }
      return null
    }
    try {
      await context.step('create two portals through the public wand workflow', async () => {
        await p.arena()
        context.expect((await storedPortals(path.join(folder, 'portals'))).length === 0,
          'Bulk-deletion acceptance requires an isolated instance with no existing portals')
        await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
        await context.command('/wh wand rune=false', /wand/i)
        await build(0, `${prefix}_a`)
        await build(24, `${prefix}_b`)
      })
      await context.step('join both portals to a persisted portal network', async () => {
        await context.command(`/wh nexus create name=${prefix}`, /created/i)
        await context.command(`/wh nexus add network=${prefix} portal=${prefix}_a`, /added|joined/i)
        await context.command(`/wh nexus add network=${prefix} portal=${prefix}_b`, /added|joined/i)
        await context.waitUntil(async () => (await readNetwork())?.members?.length === 2,
          { label: 'network persists two portal members', timeoutMs: 10000 })
      })
      await context.step('bulk delete removes portal files and network membership', async () => {
        await context.command('/wh admin deleteallportals', /deleted.*2|2.*portal/i)
        await context.waitUntil(async () => (await storedPortals(path.join(folder, 'portals'))).length === 0
          && (await readNetwork())?.members?.length === 0,
          { label: 'portal and membership cleanup', timeoutMs: 10000 })
        await context.sleep(1500)
        context.expect((await storedPortals(path.join(folder, 'portals'))).length === 0, 'Deleted portal files stay absent')
        const network = await readNetwork()
        context.expect(network !== null, 'Bulk portal deletion preserves the network itself')
        context.report.bulkDeletion = { portals: 2, network }
      })
    } finally {
      p.bot.clearControlStates()
      if (p.bot.currentWindow) p.bot.closeWindow(p.bot.currentWindow)
    }
  }
}
