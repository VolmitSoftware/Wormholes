import { createRequire } from 'node:module'

export default {
  name: 'horizontal-projection-performance',
  description: 'Measures two viewers above an irregular Down portal during stationary and repeated movement phases.',
  async run(context) {
    const mineflayer = createRequire(process.argv[1])('mineflayer')
    const movingCycles = Number(process.env.WORMHOLES_MOVING_CYCLES ?? 20)
    context.expect(Number.isInteger(movingCycles) && movingCycles >= 20 && movingCycles <= 120,
      'Moving cycle count is an integer between 20 and 120', movingCycles)
    const evidence = { jarSha256: process.env.WORMHOLES_JAR_SHA256, movingCycles, phases: [], samples: [], errors: [], blockPackets: 0, observerPackets: {} }
    context.report.horizontal = evidence
    let second
    let closing = false
    let rejectFatal
    const fatal = new Promise((resolve, reject) => { rejectFatal = reject })
    fatal.catch(() => {})
    const fail = message => {
      evidence.errors.push(message)
      rejectFatal(new Error(message))
    }
    const sleep = milliseconds => Promise.race([context.sleep(milliseconds), fatal])

    async function event(bot, name, predicate, timeout = 12000) {
      let listener
      let timer
      try {
        return await Promise.race([fatal, new Promise((resolve, reject) => {
          listener = (...args) => { if (predicate(...args)) resolve(args) }
          bot.on(name, listener)
          timer = setTimeout(() => reject(new Error(`${bot.username} ${name} timed out`)), timeout)
        })])
      } finally {
        clearTimeout(timer)
        bot.removeListener(name, listener)
      }
    }

    async function command(bot, text, pattern) {
      const reply = event(bot, 'messagestr', message => pattern.test(message))
      bot.chat(text)
      return (await reply)[0]
    }

    async function move(bot, direction) {
      let ticks = 0
      const completed = event(bot, 'physicsTick', () => {
        if (++ticks < 8) return false
        bot.clearControlStates()
        return true
      })
      bot.setControlState(direction, true)
      try {
        await completed
      } finally {
        bot.clearControlStates()
      }
    }

    function watch(bot) {
      bot.on('error', error => fail(`${bot.username}: ${error.stack ?? error}`))
      bot.on('kicked', reason => fail(`${bot.username} kicked: ${JSON.stringify(reason)}`))
      bot.on('death', () => fail(`${bot.username} died`))
      bot.on('end', reason => { if (!closing) fail(`${bot.username} disconnected: ${reason}`) })
      bot._client.on('packet', (packet, metadata) => {
        if (metadata.name === 'multi_block_change' || metadata.name === 'block_change') {
          evidence.blockPackets++
          evidence.observerPackets[bot.username] = (evidence.observerPackets[bot.username] ?? 0) + 1
        }
      })
    }

    async function sample(phase) {
      const messages = []
      const listener = message => { if (message.startsWith('HORIZONTAL ')) messages.push(message) }
      context.bot.on('messagestr', listener)
      try {
        await command(context.bot, '/whtest horizontal sample', /HORIZONTAL sampleEnd/)
      } finally {
        context.bot.removeListener('messagestr', listener)
      }
      const counters = messages.find(message => message.startsWith('HORIZONTAL sample time='))
      context.expect(Boolean(counters), 'Fixture telemetry is present', messages)
      const values = Object.fromEntries([...counters.matchAll(/(\w+)=([\d.Ee+-]+)/g)].map(match => [match[1], Number(match[2])]))
      if (process.env.WORMHOLES_EXPECT_FRAME_BUDGET_MICROS) {
        context.expect(values.frameBudgetMicros === Number(process.env.WORMHOLES_EXPECT_FRAME_BUDGET_MICROS),
          'The runtime uses the expected projection frame budget', values)
      }
      const projectors = messages.filter(message => message.startsWith('HORIZONTAL projector ')).map(message => {
        const parsed = Object.fromEntries([...message.matchAll(/(\w+)=([^ ]+)/g)].map(match => {
          const numeric = Number(match[2])
          return [match[1], Number.isNaN(numeric) ? match[2] : numeric]
        }))
        return { ...parsed, raw: message }
      })
      if (phase !== 'startup') {
        context.expect(projectors.length === 2, 'Both observers have active projectors', messages)
        context.expect(projectors.every(projector => projector.candidateWork > 10000 && projector.fittedDepth === 64),
          'Both projections retain full depth and a substantial scan volume', projectors)
        context.expect(projectors.every(projector => projector.rendered > 0), 'Both viewers receive destination geometry', projectors)
      }
      context.expect(values.failures === 0, 'Projection telemetry reports no runtime failures', values)
      const positions = [context.bot, second].map(bot => ({ username: bot.username, ...bot.entity.position }))
      context.expect(positions.every(position => position.y > 123.9 && position.y < 124.1 && position.x >= 0 && position.x <= 16),
        'Viewers remain on the observation platform', positions)
      const observerPackets = { ...evidence.observerPackets }
      const previous = evidence.samples.at(-1)
      if (phase === 'moving' && previous?.phase === 'moving') {
        for (const viewer of [context.bot, second]) {
          context.expect(observerPackets[viewer.username] > previous.observerPackets[viewer.username],
            'Each observer receives fresh block updates in every movement sample interval',
            { username: viewer.username, previous: previous.observerPackets[viewer.username], current: observerPackets[viewer.username] })
        }
      }
      evidence.samples.push({ phase, ...values, projectors, positions, observerPackets })
    }

    async function phase(name, action) {
      const record = { name, startedAt: new Date().toISOString() }
      evidence.phases.push(record)
      process.stderr.write(`HORIZONTAL phase=${name} startedAt=${record.startedAt}\n`)
      await command(context.bot, '/whtest horizontal ticks reset', /HORIZONTAL ticksReset/)
      await context.step(name, action)
      const timing = await command(context.bot, '/whtest horizontal ticks', /HORIZONTAL ticks count=/)
      record.tickTiming = Object.fromEntries([...timing.matchAll(/(\w+)=([\d.Ee+-]+)/g)].map(match => [match[1], Number(match[2])]))
      context.expect(record.tickTiming.count > 0, 'Actual server tick durations were recorded', timing)
      record.finishedAt = new Date().toISOString()
      process.stderr.write(`HORIZONTAL phase=${name} finishedAt=${record.finishedAt}\n`)
    }

    try {
      watch(context.bot)
      await context.step('create horizontal portal and join second viewer', async () => {
        const setup = await command(context.bot, '/whtest horizontal setup', /HORIZONTAL ready/)
        context.expect(setup.includes('cells=37') && setup.includes('depth=64 pad=48 open=true linked=true'),
          'Fixture matches the horizontal workload', setup)
        evidence.fixture = setup
        second = mineflayer.createBot({ host: context.server.host, port: context.server.port,
          username: 'HorizViewer2', auth: 'offline', version: context.bot.version })
        watch(second)
        await event(second, 'spawn', () => true, 30000)
        await command(context.bot, '/op HorizViewer2', /operator|already/i)
        await command(context.bot, '/whtest horizontal stage', /HORIZONTAL staged=true/)
        await command(second, '/whtest horizontal stage oblique', /HORIZONTAL staged=true/)
        await context.bot.look(0, -Math.PI / 2, true)
        await second.look(0, -Math.PI / 2, true)
        await command(context.bot, '/wh admin flush', /[Ff]lushed/)
      })
      await phase('warmup', async () => {
        for (let index = 0; index < 8; index++) {
          await sleep(250)
          await sample('startup')
        }
        await sleep(18000)
        await sample('warmup')
      })
      await phase('stationary', async () => {
        for (let index = 0; index < 20; index++) {
          await sleep(1000)
          await sample('stationary')
        }
      })
      await phase('moving', async () => {
        for (let index = 0; index < movingCycles; index++) {
          await Promise.all([
            command(context.bot, '/whtest horizontal stage', /HORIZONTAL staged=true/),
            command(second, '/whtest horizontal stage oblique', /HORIZONTAL staged=true/)
          ])
          for (const direction of ['left', 'right', 'left', 'right']) {
            await Promise.all([move(context.bot, direction), move(second, direction)])
          }
          await sample('moving')
        }
      })
      await phase('settled', async () => {
        await sleep(5000)
        for (let index = 0; index < 5; index++) {
          await sleep(1000)
          await sample('settled')
        }
      })
      context.expect(evidence.blockPackets > 0, 'Clients received projected block updates')
      context.expect(evidence.errors.length === 0, 'Both viewers remained connected without client errors')
    } finally {
      closing = true
      context.bot.clearControlStates()
      if (second && !second._client.ended) {
        let timer
        let onEnd
        try {
          second.clearControlStates()
          const ended = new Promise(resolve => { onEnd = resolve; second.once('end', onEnd) })
          second.quit()
          await Promise.race([ended, new Promise((resolve, reject) => {
            timer = setTimeout(() => reject(new Error('Second viewer cleanup timed out')), 5000)
          })])
        } catch (error) {
          second._client.socket?.destroy()
          throw error
        } finally {
          clearTimeout(timer)
          second.removeListener('end', onEnd)
        }
      }
    }
  }
}
