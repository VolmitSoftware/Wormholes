import { crossWorldPortals, dimensionName, END, OVERWORLD } from './support/cross-world-portals.mjs'

const ENTER_TIMEOUT_MS = 10000
const SETTLE_TIMEOUT_MS = 6000
const STAY_MS = 2500

export default {
  name: 'wormholes-horizontal-portal-crossing',
  description: 'Wand-build a floor portal with a solid block under its opening and a ceiling portal with a cleared opening, link them both ways, walk from the rim into the floor portal and jump up through the ceiling portal.',
  async run(context) {
    const portals = crossWorldPortals(context)
    const { p, bot } = portals
    const prefix = `h${Date.now().toString(36)}`
    const floor = portals.aperture({ dimension: OVERWORLD, x: 0, z: 0, name: `${prefix}_floor`, horizontal: true })
    const ceiling = {
      dimension: END, x: 24, z: 0, name: `${prefix}_ceiling`, horizontal: false,
      first: p.point(24, 202, -1), second: p.point(26, 202, 1), look: p.point(25.5, 202.5, 0.5), stand: { x: 25.5, y: 200, z: 3.5 }
    }
    const evidence = { floor: floor.name, ceiling: ceiling.name, phases: [] }
    context.report.horizontalPortalCrossing = evidence
    const position = () => ({ x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z })
    const inDimension = dimension => dimensionName(bot.game.dimension) === dimensionName(dimension)

    function solidAt(x, y, z) {
      const block = bot.blockAt(p.point(Math.floor(x), Math.floor(y), Math.floor(z)))
      return Boolean(block && block.boundingBox === 'block')
    }

    function embedded() {
      const { x, y, z } = bot.entity.position
      const cells = []
      for (const dx of [-0.29, 0.29]) {
        for (const dz of [-0.29, 0.29]) {
          for (const dy of [0.01, 0.9, 1.79]) {
            if (solidAt(x + dx, y + dy, z + dz)) cells.push({ x: Math.floor(x + dx), y: Math.floor(y + dy), z: Math.floor(z + dz) })
          }
        }
      }
      return cells
    }

    async function serverPosition() {
      const marker = `QA_HPC_${bot.username}`
      const components = [{ text: `${marker} ` }, { nbt: 'Dimension', entity: '@s' }, { text: ' ' }, { nbt: 'Pos', entity: '@s' }]
      return context.command(`/tellraw @s ${JSON.stringify(components)}`, new RegExp(marker), 3000)
        .catch(error => `unavailable: ${error.message}`)
    }

    async function travel(record, destination, label, start) {
      let forced = 0
      const onForced = () => { forced++ }
      bot.on('forcedMove', onForced)
      const started = Date.now()
      const samples = []
      const sampler = setInterval(() => {
        if (samples.length < 300) {
          samples.push({ t: Date.now() - started, dimension: bot.game.dimension, onGround: bot.entity.onGround, velocityY: bot.entity.velocity.y,
            ...position() })
        }
      }, 50)
      try {
        await start()
        await p.wait(() => inDimension(destination), `${label} reaches ${destination}`, ENTER_TIMEOUT_MS)
        record.crossedMs = Date.now() - started
        record.arrival = position()
        record.arrivalEmbedded = embedded()
      } catch (error) {
        record.failedAt = { dimension: bot.game.dimension, position: position(), server: await serverPosition() }
        throw error
      } finally {
        clearInterval(sampler)
        bot.removeListener('forcedMove', onForced)
        bot.clearControlStates()
        record.samples = samples
        record.forcedMoves = forced
      }
    }

    async function settle(record, destination, label, near) {
      const health = bot.health
      try {
        await p.wait(() => inDimension(destination) && bot.entity.onGround && embedded().length === 0, `${label} settles clear of blocks`,
          SETTLE_TIMEOUT_MS)
      } catch (error) {
        record.unsettled = { dimension: bot.game.dimension, position: position(), onGround: bot.entity.onGround, embedded: embedded(),
          server: await serverPosition() }
        throw error
      }
      await context.sleep(STAY_MS)
      record.settled = position()
      record.server = await serverPosition()
      record.healthLost = health - bot.health
      context.expect(inDimension(destination), `${label} stays in ${destination}`, record)
      context.expect(Math.abs(bot.entity.position.x - near.x) < 3 && Math.abs(bot.entity.position.z - near.z) < 3,
        `${label} stays at the arrival portal`, record)
      context.expect(embedded().length === 0, `${label} leaves the traveller clear of solid blocks`, record)
      context.expect(bot.health >= health, `${label} costs the traveller no health`, record)
    }

    try {
      await context.step('prepare Overworld and End arenas', async () => {
        await portals.reset()
        await portals.arena(OVERWORLD, 0)
        await portals.supply()
        await portals.arena(END, 0)
      })
      await context.step('wand-build the floor portal and put a solid block under its opening', async () => {
        await portals.build(floor)
        await context.command('/fill 0 198 -1 2 198 1 minecraft:stone', /filled|changed|blocks/i)
        await p.serverBlock(p.point(1, 198, 0), 'minecraft:stone')
      })
      await context.step('wand-build the ceiling portal with a cleared opening', () => portals.build(ceiling))
      await context.step('link the floor portal to the ceiling portal and back', async () => {
        evidence.pairing = await portals.linkAndReturn(floor, ceiling)
      })
      await context.step('floor-enter: walk from the rim into the floor portal', async () => {
        const record = { phase: 'floor-enter' }
        evidence.phases.push(record)
        await portals.emptyHand()
        await portals.standAt(floor)
        await context.sleep(1000)
        await travel(record, END, 'floor-enter', async () => {
          await bot.look(0, 0, true)
          bot.setControlState('forward', true)
          await p.wait(() => bot.entity.position.z < 1.6, 'floor-enter steps off the rim', ENTER_TIMEOUT_MS)
          bot.clearControlStates()
        })
        context.expect(record.arrival.x >= 24 && record.arrival.x <= 27 && record.arrival.z >= -1 && record.arrival.z <= 2
          && record.arrival.y >= 200 && record.arrival.y < 202.5, 'floor-enter arrives under the ceiling opening, below its plane', record)
        await settle(record, END, 'floor-enter arrival under the ceiling portal', { x: 25.5, z: 0.5 })
      })
      await context.step('ceiling-enter: jump up through the ceiling portal', async () => {
        const record = { phase: 'ceiling-enter' }
        evidence.phases.push(record)
        await portals.enter(END, 25.5, 200, 0.5)
        await context.command('/effect give @s minecraft:jump_boost 15 4 true', /Applied effect|Gave/i)
        await context.sleep(1000)
        await travel(record, OVERWORLD, 'ceiling-enter', async () => {
          bot.setControlState('jump', true)
        })
        context.expect(record.arrival.x >= 0 && record.arrival.x <= 3 && record.arrival.z >= -1 && record.arrival.z <= 2
          && record.arrival.y >= 199.5, 'ceiling-enter arrives above the floor plane, clear of the block under the opening', record)
        await context.sleep(500)
        record.afterArrival = { dimension: bot.game.dimension, position: position(), server: await serverPosition() }
        context.expect(inDimension(OVERWORLD), 'ceiling-enter keeps the traveller at the floor portal after arriving', record)
        await context.command('/effect clear @s', /Removed|No effect|Had no/i)
        await portals.standAt(floor)
      })
      await context.step('delete the pair', () => portals.cleanup())
    } finally {
      bot.clearControlStates()
    }
  }
}
