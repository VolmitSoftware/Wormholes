import path from 'node:path'
import { portalWorkflow } from './support/portal-workflow.mjs'
import { storedPortals } from './support/portal-storage.mjs'

export default {
  name: 'wormholes-reciprocal-faces',
  description: 'Wand-build a cross-world pair, use Link and return, repeat pairing, and traverse both faces of both portals.',
  async run(context) {
    const workflow = portalWorkflow(context)
    const p = workflow.p
    const prefix = `faces_${Date.now().toString(36)}`
    const portals = [
      { name: `${prefix}_a`, dimension: 'minecraft:overworld', x: 0 },
      { name: `${prefix}_b`, dimension: 'minecraft:the_end', x: 24 }
    ]
    const directory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const evidence = { portals, pairings: [], crossings: [] }
    context.report.reciprocalFaces = evidence

    async function dimension(portal) {
      if (p.bot.currentWindow) p.bot.closeWindow(p.bot.currentWindow)
      p.bot.clearControlStates()
      await context.command(`/execute in ${portal.dimension} run tp @s ${portal.x + 0.5} 202 3.5`, /Teleported/i)
      await p.wait(() => p.bot.game.dimension === portal.dimension.split(':')[1]
        && p.bot.blockAt(p.point(portal.x, 199, 3)), `arrival in ${portal.dimension}`, 30000)
    }

    async function pair() {
      await dimension(portals[0])
      await workflow.open(portals[0].x)
      await p.click(/^Destination$/)
      const reply = context.waitForMessage(/now link both ways/i, 10000)
      reply.catch(() => {})
      await p.click(/^Link and return$/)
      evidence.pairings.push(await reply)
      await p.wait(() => !p.bot.currentWindow, 'pairing closes the menu')
    }

    async function storedPair() {
      const saved = await storedPortals(directory)
      return portals.map(portal => saved.find(entry => entry.data.name === portal.name))
    }

    await context.step('prepare the isolated source arena and wand', async () => {
      await p.arena()
      await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
      await context.command('/wh wand rune=false', /wand/i)
    })
    await context.step('wand-build the source portal', async () => {
      await workflow.build(portals[0].x, portals[0].name)
      await workflow.wormhole(portals[0].x)
    })
    await context.step('wand-build the destination in a second world', async () => {
      await dimension(portals[1])
      await p.arena()
      await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
      await context.command('/wh wand rune=false', /wand/i)
      await workflow.build(portals[1].x, portals[1].name)
      await workflow.wormhole(portals[1].x)
    })
    await context.step('select the destination then use Link and return', async () => {
      await dimension(portals[0])
      await workflow.link(portals[0].x, portals[1].name)
      await pair()
      await p.wait(async () => {
        const [source, destination] = await storedPair()
        return source && destination && source.data.tunnel?.destination === destination.id
          && destination.data.tunnel?.destination === source.id
      }, 'both cross-world destinations are persisted', 30000)
      evidence.stored = (await storedPair()).map(entry => ({ id: entry.id, tunnel: entry.data.tunnel }))
      context.expect(evidence.stored.every(portal => portal.tunnel.type === 'DIMENSIONAL'),
        'Both cross-world routes use dimensional tunnels', evidence.stored)
    })
    await context.step('repeat pairing without clearing either route', async () => {
      await pair()
      const [source, destination] = await storedPair()
      context.expect(source.data.tunnel.destination === destination.id
        && destination.data.tunnel.destination === source.id, 'Repeated pairing retains both routes')
    })
    for (const [sourceIndex, side] of [[0, 1], [1, 1], [0, -1], [1, -1]]) {
      const source = portals[sourceIndex]
      const destination = portals[1 - sourceIndex]
      await context.step(`cross ${source.name} from ${side > 0 ? 'front' : 'back'}`, async () => {
        await dimension(source)
        await p.stage(source.x + 0.5, 200, side > 0 ? 3.5 : -2.5)
        await p.emptyHand()
        await context.sleep(1000)
        await p.walk(side > 0 ? 0 : Math.PI, () => p.bot.game.dimension === destination.dimension.split(':')[1]
          && Math.abs(p.bot.entity.position.x - destination.x - 0.5) < 2, 'cross-world portal arrival', 15000)
        await context.sleep(700)
        const arrival = { source: source.name, side, dimension: p.bot.game.dimension, position: { ...p.bot.entity.position } }
        evidence.crossings.push(arrival)
        context.expect(arrival.dimension === destination.dimension.split(':')[1]
          && Math.abs(arrival.position.x - destination.x - 0.5) < 2, 'Traveler remains at the destination', arrival)
      })
    }
  }
}
