export default {
  name: 'entity-crossing-motion',
  description: 'Verify a moving item keeps its crossing endpoint and rotated velocity through perpendicular portals.',
  async run(context) {
    const evidence = { runs: [] }
    context.report.entityMotion = evidence
    await context.step('prepare perpendicular portals and a moving item', async () => {
      if (context.bot.game.gameMode !== 'creative') {
        await context.command('/gamemode creative', /creative/i)
      }
      await context.command('/whnested motion setup', /MOTION ready=true/, 60000)
    })
    for (const mode of ['normal', 'slow']) {
      await context.step(`${mode} item crosses with exact position and rotated momentum`, async () => {
        const run = { mode, samples: [] }
        evidence.runs.push(run)
        run.launch = await context.command(`/whnested motion launch ${mode}`, /MOTION launched=/)
        let response
        const deadline = Date.now() + 10000
        while (Date.now() < deadline) {
          response = await context.command('/whnested motion sample', /MOTION state=/)
          run.samples.push(response)
          if (response.includes('state=arrived')) break
          await context.sleep(100)
        }
        context.expect(response.includes('state=arrived'), 'Moving item did not arrive', evidence)
        const positionError = Number(/positionError=([^ ]+)/.exec(response)?.[1])
        const vector = name => new RegExp(`${name}=([^ ]+)`).exec(response)?.[1].split(',').map(Number)
        const departure = vector('departureVelocity')
        const arrival = vector('arrivalVelocity')
        context.expect(positionError < 0.000001, 'Crossing endpoint was displaced', { positionError, response })
        context.expect(departure?.length === 3 && arrival?.length === 3, 'Missing motion vectors', response)
        const errors = [0, 1, 2].map(ticks => Math.hypot(...departure.map((value, axis) =>
          arrival[axis] - value * Math.pow(0.98, ticks))))
        run.velocityErrors = errors
        context.expect(Math.min(...errors) < 0.00001,
          'Arrival velocity differs from the rotated incoming momentum after ordinary item drag', evidence)
        if (mode === 'slow') {
          context.expect(Math.hypot(...departure) < 0.01, 'Slow-item case did not exercise sub-0.01 motion', run)
        }
      })
    }
  }
}
