import path from 'node:path'
import { publicPlayer } from './support/public-player.mjs'
import { storedPortals } from './support/portal-storage.mjs'

const EDGE = 7
const SOURCE_X = 2
const DESTINATION_X = 20
const FLOOR_Y = 200
const PLANE_Z = 0
const STORED_CIRCLE = 'circle(radius=1)'

export default {
  name: 'wormholes-aperture-shape-mask',
  description: 'Wand-build a 7x7 portal pair, set a circle aperture shape by command, and walk through a corner cell and the center.',
  async run(context) {
    const p = publicPlayer(context)
    const bot = p.bot
    const portalDirectory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const evidence = { portals: {}, stored: null, corner: null, center: null }
    context.report.apertureShape = evidence

    async function setBlock(x, y, z, block) {
      await context.command(`/setblock ${x} ${y} ${z} ${block}`, /Changed the block|Could not set the block/i)
      await p.serverBlock(p.point(x, y, z), block)
    }

    async function build(x) {
      const before = new Set((await storedPortals(portalDirectory)).map(portal => portal.id))
      const low = p.point(x, FLOOR_Y, PLANE_Z)
      const high = p.point(x + EDGE - 1, FLOOR_Y + EDGE - 1, PLANE_Z)
      const ledge = p.point(x + EDGE - 1, FLOOR_Y + EDGE - 4, PLANE_Z + 3)
      await setBlock(low.x, low.y, low.z, 'minecraft:glass')
      await setBlock(high.x, high.y, high.z, 'minecraft:glass')
      await setBlock(ledge.x, ledge.y, ledge.z, 'minecraft:stone')
      await p.stage(x + 0.5, FLOOR_Y, 3.5)
      await p.equip(/^Portal Wand$/)
      await context.sleep(250)
      await expectReply(/Corner A set/i, () => p.leftClick(low))
      await p.stage(ledge.x + 0.5, ledge.y + 1, ledge.z + 0.5)
      await p.equip(/^Portal Wand$/)
      await expectReply(/Selected|cells|blocks/i, async () => {
        await bot.lookAt(high.offset(0.5, 0.5, 0.5), true)
        await p.rightClick(high)
      })
      await p.stage(x + 0.5, FLOOR_Y, 3.5)
      await p.equip(/^Portal Wand$/)
      await expectReply(/Portal opened/i, () => p.leftClick(low))
      let created
      await p.wait(async () => {
        const fresh = (await storedPortals(portalDirectory)).filter(portal => !before.has(portal.id))
        created = fresh[0]
        return fresh.length === 1
      }, `portal at ${x} is stored`, 30000)
      for (const position of [low, high, ledge]) {
        await setBlock(position.x, position.y, position.z, 'minecraft:air')
      }
      context.expect(created.data.structure.blocks.length === EDGE * EDGE, 'Wand built a full 7x7 aperture', created.data.structure)
      return created.id
    }

    async function expectReply(pattern, action) {
      for (let attempt = 1; ; attempt++) {
        const reply = context.waitForMessage(pattern, 5000)
        reply.catch(() => {})
        await context.sleep(150)
        await action()
        try {
          return await reply
        } catch (error) {
          if (attempt >= 3) throw error
        }
      }
    }

    async function storedSource(id) {
      return (await storedPortals(portalDirectory)).find(portal => portal.id === id)?.data
    }

    async function detour(fromX) {
      await p.stage(SOURCE_X - 4.5, FLOOR_Y, PLANE_Z - 1.5)
      await p.stage(SOURCE_X - 4.5, FLOOR_Y, 3.5)
      await p.stage(fromX, FLOOR_Y, 3.5)
    }

    const teleported = () => bot.entity.position.x > DESTINATION_X - 2

    await context.step('prepare the arena and the wand', async () => {
      await p.arena()
      await context.command('/wh wand rune=false', /wand/i)
    })
    await context.step('wand-build a 7x7 portal pair and link it', async () => {
      evidence.portals.source = await build(SOURCE_X)
      evidence.portals.destination = await build(DESTINATION_X)
      await context.command(`/wormholes admin portals retarget ${evidence.portals.source} ${evidence.portals.destination}`, /now points at/i)
    })
    await context.step('set a circle aperture shape by command', async () => {
      await context.command(`/wormholes admin portals shape ${evidence.portals.source} shape=circle`, /aperture shape set to/i)
      await context.command(`/wormholes admin portals shape ${evidence.portals.source}`, /aperture shape: circle\(radius=1\)/i)
      await p.wait(async () => (await storedSource(evidence.portals.source))?.apertureShape === STORED_CIRCLE,
        'stored portal carries the circle shape', 30000)
      const stored = await storedSource(evidence.portals.source)
      evidence.stored = { apertureShape: stored.apertureShape, blocks: stored.structure.blocks.length }
      context.expect(stored.structure.blocks.length === EDGE * EDGE, 'Built cells persist unchanged under the shape', evidence.stored)
    })
    await context.step('walk through a corner cell outside the circle', async () => {
      await p.emptyHand()
      await p.stage(SOURCE_X + 0.3, FLOOR_Y, 3.5)
      await p.walk(0, () => bot.entity.position.z < PLANE_Z - 0.6 || teleported(), 'walker passes the portal plane at the corner')
      await context.sleep(600)
      evidence.corner = { ...bot.entity.position }
      context.expect(!teleported(), 'A walk through the corner cell outside the circle teleported the player', evidence.corner)
      context.expect(bot.entity.position.z < PLANE_Z, 'The corner walker continued past the portal plane', evidence.corner)
    })
    await context.step('walk through the center of the circle', async () => {
      await detour(SOURCE_X + EDGE * 0.5)
      await p.walk(0, teleported, 'walker through the center arrives at the destination')
      evidence.center = { ...bot.entity.position }
      context.expect(Math.abs(bot.entity.position.x - (DESTINATION_X + EDGE * 0.5)) < 2,
        'The center walker arrived inside the destination aperture', evidence.center)
    })
  }
}
