import { publicPlayer } from './support/public-player.mjs'

export default {
  name: 'wormholes-portal-crossing-latency',
  description: 'Walk repeatedly through linked portals and bound server crossing-to-teleport latency with destination prewarming enabled and disabled.',
  async run(context) {
    const p = publicPlayer(context)
    const bot = context.bot
    const results = context.report.crossingLatency = { cooldownMillis: 0, maximumDelayMicros: 150000, samples: [] }
    await context.command('/whnested latency setup', /LATENCY setup=true/, 30000)
    try {
      for (const prewarm of [false, true]) {
        await context.command(`/whnested latency prewarm ${prewarm}`, new RegExp(`LATENCY prewarm=${prewarm}`))
        await p.stage(8.5, 191, prewarm ? 14.5 : 10)
        for (let crossing = 0; crossing < 6; crossing++) {
          await context.step(`natural crossing ${crossing} prewarm=${prewarm}`, async () => {
            const sourceX = bot.entity.position.x
            const destinationX = sourceX < 50 ? 104.5 : 8.5
            await context.command('/whnested latency arm', /LATENCY armed=true/)
            await bot.look(bot.entity.position.z > 8.5 ? 0 : Math.PI, 0, true)
            bot.setControlState('forward', true)
            try {
              await p.wait(() => Math.abs(bot.entity.position.x - destinationX) < 0.25,
                'portal teleport reaches the linked destination', 8000)
              await p.wait(() => Math.abs(bot.entity.position.z - 8.5) >= 1.25,
                'traveler clears the destination portal before reversing', 4000)
            } finally {
              bot.clearControlStates()
            }
            const sample = await context.command('/whnested latency sample', /LATENCY delayMicros=/)
            const delayMicros = Number(/delayMicros=(-?\d+)/.exec(sample)?.[1])
            const departureZ = Number(/departureZ=(-?[\d.]+)/.exec(sample)?.[1])
            context.expect(Number.isFinite(delayMicros) && delayMicros <= results.maximumDelayMicros,
              'Physical crossing reaches teleport without a preparation wait', { sample, delayMicros })
            if (delayMicros < 0) {
              context.expect(sample.includes('earlyCrossing=true') && Math.abs(departureZ - 8.5) <= 0.3,
                'Early teleport coincides with the portal plane', { sample, departureZ })
            }
            await p.wait(() => bot.blockAt(p.point(Math.floor(bot.entity.position.x), 190,
              Math.floor(bot.entity.position.z)))?.name === 'stone', 'destination landing terrain arrives', 8000)
            context.expect(bot.health > 0 && Math.abs(bot.entity.position.x - destinationX) < 0.25,
              'Traveler stays alive at the linked destination', { position: bot.entity.position, health: bot.health })
            results.samples.push({ prewarm, crossing, sourceX, destinationX, delayMicros, departureZ,
              position: { ...bot.entity.position } })
          })
        }
      }
    } finally {
      bot.clearControlStates()
      await context.command('/whnested latency prewarm true', /LATENCY prewarm=true/)
    }
  }
}
