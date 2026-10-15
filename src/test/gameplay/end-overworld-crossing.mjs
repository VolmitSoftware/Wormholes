import { crossWorldPortals, dimensionName, END, OVERWORLD } from './support/cross-world-portals.mjs'

const LAYOUTS = {
  vertical: { z: 0, horizontal: false },
  horizontal: { z: 200, horizontal: true }
}

export default {
  name: 'wormholes-end-overworld-crossing',
  description: 'Wand-build 3x3 End and Overworld portals, pair them from the End, and cross End to Overworld and back from both starting worlds.',
  async run(context) {
    const portals = crossWorldPortals(context)
    const { p, bot } = portals
    const selected = (context.options.command ?? 'vertical,horizontal').split(',').map(entry => entry.trim()).filter(Boolean)
    for (const layout of selected) {
      context.expect(Object.hasOwn(LAYOUTS, layout), 'Layout is vertical or horizontal', { layout })
    }
    const evidence = { layouts: [] }
    context.report.endOverworldCrossing = evidence

    async function cross(source, destination, label) {
      const record = { label, source: source.name, destination: destination.name }
      evidence.current.crossings.push(record)
      await portals.emptyHand()
      if (source.horizontal) {
        await portals.enter(source.dimension, source.x + 1.5, 201, source.z + 0.5, { settle: false })
      } else {
        await portals.standAt(source)
        await context.sleep(1000)
        await bot.look(0, 0, true)
        bot.setControlState('forward', true)
      }
      try {
        await p.wait(() => dimensionName(bot.game.dimension) === dimensionName(destination.dimension)
          && Math.abs(bot.entity.position.x - (destination.x + 1.5)) < 3, `${label} arrival`, 15000)
      } catch (error) {
        record.failedAt = { dimension: bot.game.dimension, position: { ...bot.entity.position } }
        throw error
      } finally {
        bot.clearControlStates()
      }
      await context.sleep(1500)
      record.arrival = { dimension: bot.game.dimension, position: { ...bot.entity.position } }
      context.expect(dimensionName(bot.game.dimension) === dimensionName(destination.dimension),
        `${label} keeps the traveller at the destination`, record)
    }

    async function returnFromArrival(source, destination, label) {
      const behind = bot.entity.position.z < source.z + 0.5
      const record = { label, source: source.name, destination: destination.name, face: behind ? 'back' : 'front' }
      evidence.current.crossings.push(record)
      await portals.emptyHand()
      await bot.look(behind ? 0 : Math.PI, 0, true)
      bot.setControlState('forward', true)
      await p.wait(() => behind ? bot.entity.position.z < source.z - 3 : bot.entity.position.z > source.z + 4,
        `${label} clears the arrival aperture`, 5000)
      bot.clearControlStates()
      await context.sleep(500)
      await bot.look(behind ? Math.PI : 0, 0, true)
      bot.setControlState('forward', true)
      try {
        await p.wait(() => dimensionName(bot.game.dimension) === dimensionName(destination.dimension)
          && Math.abs(bot.entity.position.x - (destination.x + 1.5)) < 3, `${label} arrival`, 15000)
      } catch (error) {
        record.failedAt = { dimension: bot.game.dimension, position: { ...bot.entity.position } }
        throw error
      } finally {
        bot.clearControlStates()
      }
      await context.sleep(1500)
      record.arrival = { dimension: bot.game.dimension, position: { ...bot.entity.position } }
      context.expect(dimensionName(bot.game.dimension) === dimensionName(destination.dimension),
        `${label} keeps the traveller at the destination`, record)
    }

    try {
      for (const layout of selected) {
        const { z, horizontal } = LAYOUTS[layout]
        const prefix = `${layout.slice(0, 1)}${Date.now().toString(36)}`
        const overworld = portals.aperture({ dimension: OVERWORLD, x: 0, z, name: `${prefix}_ow`, horizontal })
        const end = portals.aperture({ dimension: END, x: 24, z, name: `${prefix}_end`, horizontal })
        evidence.current = { layout, overworld: overworld.name, end: end.name, crossings: [] }
        evidence.layouts.push(evidence.current)
        await context.step(`${layout}: prepare Overworld and End arenas`, async () => {
          await portals.reset()
          await portals.arena(OVERWORLD, z)
          await portals.supply()
          await portals.arena(END, z)
        })
        await context.step(`${layout}: wand-build the 3x3 Overworld portal`, () => portals.build(overworld))
        await context.step(`${layout}: wand-build the 3x3 End portal`, () => portals.build(end))
        await context.step(`${layout}: link the End portal to the Overworld and pair it`, async () => {
          evidence.current.pairing = await portals.linkAndReturn(end, overworld)
        })
        await context.step(`${layout}: End to Overworld`, () => cross(end, overworld, `${layout} End to Overworld`))
        if (!horizontal) {
          await context.step(`${layout}: turn around and return to the End through the arrival portal`,
            () => returnFromArrival(overworld, end, `${layout} Overworld arrival face to End`))
        }
        await context.step(`${layout}: Overworld to End through the front face`, () => cross(overworld, end, `${layout} Overworld to End`))
        await context.step(`${layout}: End to Overworld after starting in the Overworld`, () => cross(end, overworld, `${layout} End to Overworld again`))
        await context.step(`${layout}: delete the pair`, () => portals.cleanup())
      }
    } finally {
      bot.clearControlStates()
    }
  }
}
