import path from 'node:path'
import { publicPlayer } from './support/public-player.mjs'
import { storedPortals } from './support/portal-storage.mjs'

const SMALL = 3
const LARGE = 9
const SMALL_X = 2
const LARGE_X = 20
const FLOOR_Y = 200
const PLANE_Z = 0
const ENTRY_OFFSET = 1
const MODIFIER = 'wormholes:portal_scale'

export default {
  name: 'wormholes-scaled-travel',
  description: 'Wand-build a 3x3 and a 9x9 portal, set both to the ratio scale rule, walk through and back, and reset the size by command.',
  async run(context) {
    const p = publicPlayer(context)
    const bot = p.bot
    const portalDirectory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const evidence = { portals: {}, packets: [], grown: null, returned: null, reset: null }
    context.report.scaledTravel = evidence

    bot._client.on('packet', (data, meta) => {
      if (!/attributes/.test(meta.name) || data.entityId !== bot.entity?.id) return
      evidence.packets.push(JSON.parse(JSON.stringify(data, (key, value) => typeof value === 'bigint' ? value.toString() : value)))
    })

    async function setBlock(x, y, z, block) {
      await context.command(`/setblock ${x} ${y} ${z} ${block}`, /Changed the block|Could not set the block/i)
      await p.serverBlock(p.point(x, y, z), block)
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

    async function build(x, edge) {
      const before = new Set((await storedPortals(portalDirectory)).map(portal => portal.id))
      const low = p.point(x, FLOOR_Y, PLANE_Z)
      const high = p.point(x + edge - 1, FLOOR_Y + edge - 1, PLANE_Z)
      const ledge = p.point(x + edge - 1, FLOOR_Y + Math.max(0, edge - 4), PLANE_Z + 3)
      await setBlock(low.x, low.y, low.z, 'minecraft:glass')
      await setBlock(high.x, high.y, high.z, 'minecraft:glass')
      if (ledge.y > FLOOR_Y) await setBlock(ledge.x, ledge.y, ledge.z, 'minecraft:stone')
      await p.stage(x + 0.5, FLOOR_Y, 3.5)
      await p.equip(/^Portal Wand$/)
      await context.sleep(250)
      await expectReply(/Corner A set/i, () => p.leftClick(low))
      await p.stage(ledge.x + 0.5, ledge.y > FLOOR_Y ? ledge.y + 1 : FLOOR_Y, ledge.z + 0.5)
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
      for (const position of [low, high]) {
        await setBlock(position.x, position.y, position.z, 'minecraft:air')
      }
      if (ledge.y > FLOOR_Y) await setBlock(ledge.x, ledge.y, ledge.z, 'minecraft:air')
      context.expect(created.data.structure.blocks.length === edge * edge, `Wand built a full ${edge}x${edge} aperture`, created.data.structure)
      return created.id
    }

    function portalModifier() {
      let scaleKey = null
      let current = null
      for (const packet of evidence.packets) {
        for (const property of packet.properties ?? []) {
          const modifier = (property.modifiers ?? []).find(candidate => String(candidate.id ?? candidate.uuid) === MODIFIER)
          if (modifier) {
            scaleKey = property.key
            current = modifier
          } else if (scaleKey !== null && property.key === scaleKey) {
            current = null
          }
        }
      }
      return current
    }

    async function serverScale() {
      const reply = await context.command(`/attribute ${bot.username} minecraft:scale get`, /attribute Scale for entity/i)
      const value = Number(/is (-?[0-9.]+)/.exec(String(reply))?.[1])
      context.expect(Number.isFinite(value), 'The server reports the scale attribute', { reply })
      return value
    }

    const inLarge = () => bot.entity.position.x > LARGE_X - 1
    const inSmall = () => bot.entity.position.x < SMALL_X + SMALL + 1

    await context.step('prepare the arena and the wand', async () => {
      await p.arena()
      await context.command('/wh wand rune=false', /wand/i)
    })
    await context.step('wand-build a 3x3 and a 9x9 portal and link them both ways', async () => {
      evidence.portals.small = await build(SMALL_X, SMALL)
      evidence.portals.large = await build(LARGE_X, LARGE)
      await context.command(`/wormholes admin portals retarget ${evidence.portals.small} ${evidence.portals.large}`, /now points at/i)
      await context.command(`/wormholes admin portals retarget ${evidence.portals.large} ${evidence.portals.small}`, /now points at/i)
    })
    await context.step('set both ends to the ratio scale rule', async () => {
      for (const id of [evidence.portals.small, evidence.portals.large]) {
        await context.command(`/wormholes admin portals scale ${id} mode=ratio min=0.25 max=4`, /traveller scale set to ratio/i)
        await context.command(`/wormholes admin portals scale ${id}`, /traveller scale: ratio \(0\.25-4\)/i)
      }
    })
    await context.step('walk through the 3x3 and grow three times', async () => {
      await p.emptyHand()
      await p.stage(SMALL_X + SMALL * 0.5 + ENTRY_OFFSET, FLOOR_Y, 2.5)
      const entryOffset = bot.entity.position.x - (SMALL_X + SMALL * 0.5)
      await p.walk(0, inLarge, 'walker through the 3x3 arrives at the 9x9')
      await p.wait(() => Math.abs(Number(portalModifier()?.amount ?? 0) - 2) < 1e-6, 'the client receives the wormholes:portal_scale modifier', 10000)
      const arrivedOffset = bot.entity.position.x - (LARGE_X + LARGE * 0.5)
      evidence.grown = { entryOffset, arrivedOffset, modifier: portalModifier(), value: await serverScale(), position: { ...bot.entity.position } }
      context.expect(Math.abs(evidence.grown.value - 3) < 1e-6, 'The server scale attribute is 3 after the crossing', evidence.grown)
      context.expect(Math.abs(arrivedOffset - 3 * entryOffset) < 0.75, 'The arrival offset is three times the entry offset', evidence.grown)
    })
    await context.step('walk back through the 9x9 and shrink to normal', async () => {
      await p.stage(LARGE_X + LARGE * 0.5 + 3 * ENTRY_OFFSET, FLOOR_Y, -3.5, 180)
      await p.walk(Math.PI, inSmall, 'walker back through the 9x9 arrives at the 3x3')
      await p.wait(() => portalModifier() === null, 'the client loses the portal modifier', 10000)
      evidence.returned = { value: await serverScale(), position: { ...bot.entity.position } }
      context.expect(Math.abs(evidence.returned.value - 1) < 1e-6, 'The return trip restored the default scale', evidence.returned)
    })
    await context.step('grow again and reset the size by command', async () => {
      await p.stage(SMALL_X - 4.5, FLOOR_Y, 6.5)
      await context.sleep(1500)
      await p.stage(SMALL_X + SMALL * 0.5, FLOOR_Y, 3.5)
      await p.walk(0, inLarge, 'walker through the 3x3 arrives at the 9x9 again')
      await p.wait(() => portalModifier() !== null, 'the client receives the portal modifier again', 10000)
      await context.command(`/wormholes admin scale reset target=${bot.username}`, /Restored the default size of 1 entit/i)
      await p.wait(() => portalModifier() === null, 'the reset removes the portal modifier on the client', 10000)
      evidence.reset = { value: await serverScale() }
      context.expect(Math.abs(evidence.reset.value - 1) < 1e-6, 'The reset restored the default scale', evidence.reset)
    })
  }
}
