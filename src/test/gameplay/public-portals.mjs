import { portalWorkflow } from './support/portal-workflow.mjs'

export default {
  name: 'wormholes-public-portals',
  description: 'Place selection blocks, build and name two portals with the wand, link through menus, and walk both ways.',
  async run(context) {
    const { p, evidence, build, link, wormhole, cleanup } = portalWorkflow(context)
    const prefix = `qa_${Date.now().toString(36)}`


    try {
      await context.step('prepare isolated arena and obtain public wand', async () => {
        await p.arena()
        await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
        await context.command('/wh wand rune=false', /wand/i)
      })
      await context.step('place blocks and construct the first portal using the wand', () => build(0, `${prefix}_a`))
      await context.step('place blocks and construct the second portal using the wand', () => build(24, `${prefix}_b`))
      await context.step('link the first portal with the destination menu', () => link(0, `${prefix}_b`))
      await context.step('link the return portal with the destination menu', () => link(24, `${prefix}_a`))
      await context.step('select Wormhole mode through the public menus', async () => {
        await wormhole(0)
        await wormhole(24)
      })
      for (const [source, destination] of [[0, 24], [24, 0]]) {
        await context.step(`walk through portal at ${source}`, async () => {
          await p.stage(source + 0.5, 200, 3.5)
          await p.emptyHand()
          await p.walk(0, () => Math.abs(p.bot.entity.position.x - (destination + 0.5)) < 2,
            `arrival near portal at ${destination}`)
          const position = { ...p.bot.entity.position }
          evidence.crossings.push({ source, destination, position })
          await context.sleep(700)
          context.expect(Math.abs(p.bot.entity.position.x - (destination + 0.5)) < 2,
            'Traveler does not bounce without another crossing', { position: p.bot.entity.position })
        })
      }
    } finally {
      await cleanup()
    }
  }
}
