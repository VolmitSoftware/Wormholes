import path from 'node:path'
import { portalWorkflow } from './support/portal-workflow.mjs'
import { publicPlayer } from './support/public-player.mjs'
import { storedPortals } from './support/portal-storage.mjs'

export default {
  name: 'wormholes-permission-rejections',
  description: 'Reject ordinary-player portal control and non-flat selections, and verify survival rune refunds.',
  async run(context) {
    const workflow = portalWorkflow(context)
    const { p, build, open, cleanup } = workflow
    const directory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    try {
      await context.step('owner constructs a public-workflow portal', async () => {
        await p.arena()
        await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
        await context.command('/wh wand rune=false', /wand/i)
        await build(0, `qa_access_${Date.now().toString(36)}`)
        await open(0)
        p.bot.closeWindow(p.bot.currentWindow)
      })
      await context.step('ordinary non-owner cannot obtain tools or edit the portal', async () => {
        const actor = await context.connectActor('WhOtherOwner')
        const other = publicPlayer(actor, { controller: context })
        await actor.command('/wh wand rune=false', /permission/i)
        await actor.command('/wh door type=pair', /permission/i)
        await other.stage(0.5, 200, 3.5)
        await p.equip(/^Portal Wand$/)
        await context.command(`/item replace entity ${actor.bot.username} weapon.mainhand from entity @s weapon.mainhand`, /Replaced|Set/i)
        await other.equip(/^Portal Wand$/)
        const denied = actor.waitForMessage(/Only the portal owner or an administrator can edit/i, 10000)
        await actor.bot.lookAt(other.point(0.5, 201, 0.5), true)
        actor.bot.activateItem()
        await denied
        context.expect(!actor.bot.currentWindow, 'Denied actor must not receive an editing menu')
        await open(0)
        context.expect(p.items().some(item => item.name === 'Delete Portal'), 'Owner retains portal control')
        p.bot.closeWindow(p.bot.currentWindow)
      })
      await context.step('non-flat wand selection leaves blocks and portal set unchanged', async () => {
        await p.stage(12.5, 200, 3.5)
        for (const point of [p.point(12, 200, 0), p.point(13, 200, 1), p.point(13, 201, 1)]) {
          await p.place(point, /^Glass$/, 'glass')
        }
        const before = (await storedPortals(directory)).map(portal => portal.id).sort()
        await p.equip(/^Portal Wand$/)
        await p.leftClick(p.point(12, 200, 0))
        await p.rightClick(p.point(13, 201, 1))
        const rejected = context.waitForMessage(/Selection must be one block thick/i, 10000)
        await p.leftClick(p.point(12, 200, 0))
        await rejected
        for (const point of [p.point(12, 200, 0), p.point(13, 200, 1), p.point(13, 201, 1)]) {
          await p.serverBlock(point, 'minecraft:glass')
        }
        context.expect(JSON.stringify(before) === JSON.stringify((await storedPortals(directory)).map(portal => portal.id).sort()),
          'Rejected selection must not persist a portal')
      })
      await context.step('survival destruction refunds exactly one tracked rune', async () => {
        await context.command('/wh wand', /wand/i)
        await context.command('/give @s minecraft:diamond_pickaxe', /Gave|Given/i)
        await p.stage(20.5, 200, 2.5)
        await context.command('/gamemode survival @s', /survival/i)
        const count = () => p.bot.inventory.items().filter(item => p.itemName(item) === 'Wormhole Rune')
          .reduce((sum, item) => sum + item.count, 0)
        const before = count()
        context.expect(before === 1, 'Fixture starts with exactly one rune')
        await p.place(p.point(20, 200, 0), /^Wormhole Rune$/, 'dark_prismarine')
        await p.wait(() => count() === 0, 'survival placement consumes its rune')
        await p.equip(/^Diamond Pickaxe$/)
        await p.bounded(() => p.bot.dig(p.bot.blockAt(p.point(20, 200, 0))), 'break tracked rune')
        await p.serverBlock(p.point(20, 200, 0), 'minecraft:air')
        await p.walk(0, () => count() === 1, 'collect refunded rune', 10000)
        context.expect(count() === 1, 'Breaking the rune returns exactly one original product')
        context.report.runeRefund = { placed: 1, consumed: 1, collected: count() }
      })
    } finally {
      await context.command('/gamemode creative @s', /creative|already/i)
      await cleanup()
    }
  }
}
