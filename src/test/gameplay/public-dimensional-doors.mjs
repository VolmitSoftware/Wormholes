import { doorWorkflow } from './support/door-workflow.mjs'

export default {
  name: 'wormholes-public-dimensional-doors',
  description: 'Unpack and place paired doors, traverse them, and enter and leave persistent Personal and Public pockets.',
  async run(context) {
    const { p, placed, placeDoor, openDoor, dimensionCrossing, returnFromPocket } = doorWorkflow(context)
    const evidence = { pair: [], pockets: [] }
    context.report.publicDoors = evidence


    try {
      await context.step('prepare arena and unpack the public pair kit', async () => {
        await p.arena()
        await context.command('/wh door type=pair', /door/i)
        await p.equip(/^Entangled Door Pair$/)
        p.bot.activateItem()
        await p.wait(() => p.bot.inventory.items().some(item => p.itemName(item) === 'Wormhole Door A')
          && p.bot.inventory.items().some(item => p.itemName(item) === 'Wormhole Door B'), 'pair kit produces both identities')
        context.expect(!p.bot.inventory.items().some(item => p.itemName(item) === 'Entangled Door Pair'),
          'Unpacking consumes the pair kit')
      })
      await context.step('place both paired door endpoints', async () => {
        await placeDoor(0, /^Wormhole Door A$/, 'oak_door')
        await placeDoor(24, /^Wormhole Door B$/, 'oak_door')
      })
      for (const [source, destination] of [[0, 24], [24, 0]]) {
        await context.step(`walk through paired door at ${source}`, async () => {
          await p.stage(source + 0.5, 200, 3.5)
          await openDoor(p.point(source, 200, 0))
          await p.walk(0, () => Math.abs(p.bot.entity.position.x - destination - 0.5) < 2, 'paired door destination')
          evidence.pair.push({ source, destination, position: { ...p.bot.entity.position } })
          await p.serverBlock(p.point(source, 200, 0), 'minecraft:oak_door[open=false]')
        })
      }
      for (const product of [
        { type: 'personal', name: /^Personal Dimension Door$/, material: 'dark_oak_door', x: 8 },
        { type: 'public', name: /^Public Dimension Door$/, material: 'pale_oak_door', x: 16 }
      ]) {
        await context.step(`obtain and place ${product.type} dimensional door`, async () => {
          await p.stage(product.x + 0.5, 200, 3.5)
          await context.command(`/wh door type=${product.type}`, /door/i)
          await placeDoor(product.x, product.name, product.material)
        })
        const visits = []
        for (let visit = 0; visit < 2; visit++) {
          await context.step(`${product.type} pocket visit ${visit + 1} and return`, async () => {
            const entry = await dimensionCrossing(p.point(product.x, 200, 0), true)
            await context.command('/wh pocket info', /pocket|size|material/i)
            const returned = await returnFromPocket()
            context.expect(Math.abs(p.bot.entity.position.x - product.x - 0.5) < 4,
              'Return ticket leads back to the source door', returned)
            visits.push(entry)
          })
        }
        context.expect(p.point(visits[0].position.x, visits[0].position.y, visits[0].position.z)
          .distanceTo(p.point(visits[1].position.x, visits[1].position.y, visits[1].position.z)) < 4,
        'Repeated entry reaches the same pocket', visits)
        evidence.pockets.push({ type: product.type, visits })
      }
      context.expect(p.point(...['x', 'y', 'z'].map(axis => evidence.pockets[0].visits[0].position[axis]))
        .distanceTo(p.point(...['x', 'y', 'z'].map(axis => evidence.pockets[1].visits[0].position[axis]))) > 8,
      'Personal and Public doors have distinct pocket spaces', evidence.pockets)
    } finally {
      p.bot.clearControlStates()
      if (p.bot.currentWindow) p.bot.closeWindow(p.bot.currentWindow)
      const failures = []
      try {
        await context.command('/execute unless dimension wormholes:pockets run tellraw @s {"text":"QA_CLEANUP_OVERWORLD"}', /QA_CLEANUP_OVERWORLD/, 2000)
        for (const door of placed.reverse()) {
          try {
            await p.stage(door.x + 0.5, 200, 3.5)
            await p.emptyHand()
            await p.bounded(() => p.bot.dig(p.bot.blockAt(p.point(door.x, 200, 0))), 'remove placed door')
            await p.serverBlock(p.point(door.x, 200, 0), 'minecraft:air')
          } catch (error) { failures.push(error) }
        }
      } catch (error) { failures.push(error) }
      if (failures.length) throw new AggregateError(failures, 'Door cleanup failed; dispose of this isolated instance')
    }
  }
}
