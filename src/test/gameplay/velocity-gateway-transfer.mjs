import { createRequire } from 'node:module'
import { runGatewayScale } from './velocity-gateway-scale.mjs'

export default {
  name: 'velocity-gateway-transfer',
  description: 'Transfers through stock Velocity messaging while both linked gateways project their remote world.',
  async run(context) {
    const require = createRequire(process.argv[1])
    const mineflayer = require('mineflayer')
    const source = process.env.WORMHOLES_SOURCE_NAME ?? 'qa-a'
    const destination = process.env.WORMHOLES_DEST_NAME ?? 'qa-b'
    const dwellMs = Number(process.env.WORMHOLES_PROJECTION_DWELL_MS ?? 20000)
    context.expect(Number.isFinite(dwellMs) && dwellMs >= 0 && dwellMs <= 120000, 'Projection dwell must be between zero and 120000 milliseconds')
    const evidence = { source, destination, jarSha256: process.env.WORMHOLES_JAR_SHA256,
      arrivals: [], approaches: [], samples: [], messages: [], errors: [], blockPackets: 0, nativeTransfers: 0 }
    context.report.velocity = evidence
    let keeper
    let guest
    let closing = false
    let rejectFatal
    const fatal = new Promise((resolve, reject) => { rejectFatal = reject })
    fatal.catch(() => {})
    const fail = message => {
      evidence.errors.push(message)
      rejectFatal(new Error(message))
    }
    const resumeWriters = []
    function configureMovement(bot) {
      const originalWrite = bot._client.write
      const movementPackets = new Set(['position', 'position_look', 'look', 'flying', 'tick_end', 'player_input'])
      bot._client.write = function (name, packet) {
        if (this.state !== 'play' && movementPackets.has(name)) return
        return originalWrite.call(this, name, packet)
      }
      resumeWriters.push(() => { bot._client.write = originalWrite })
    }

    async function bounded(promise, milliseconds, description) {
      let timer
      try {
        return await Promise.race([promise, fatal, new Promise((resolve, reject) => {
          timer = setTimeout(() => reject(new Error(`${description} timed out`)), milliseconds)
        })])
      } finally {
        clearTimeout(timer)
      }
    }

    async function event(emitter, name, predicate, milliseconds = 15000) {
      let listener
      try {
        return await bounded(new Promise((resolve, reject) => {
          listener = (...arguments_) => {
            try {
              if (predicate(...arguments_)) resolve(arguments_)
            } catch (error) { reject(error) }
          }
          emitter.on(name, listener)
        }), milliseconds, name)
      } finally {
        emitter.removeListener(name, listener)
      }
    }

    async function disconnect(bot) {
      if (!bot || bot._client.ended) return
      let timer
      let onEnd
      try {
        bot.clearControlStates()
        const ended = new Promise(resolve => {
          onEnd = resolve
          bot.once('end', onEnd)
        })
        bot.quit()
        await Promise.race([ended, new Promise((resolve, reject) => {
          timer = setTimeout(() => reject(new Error(`${bot.username} did not disconnect`)), 5000)
        })])
      } catch (error) {
        bot._client.socket?.destroy()
        throw error
      } finally {
        clearTimeout(timer)
        if (onEnd) bot.removeListener('end', onEnd)
      }
    }

    async function command(bot, text, pattern) {
      const response = event(bot, 'messagestr', message => pattern.test(message))
      bot.chat(text)
      return (await response)[0]
    }

    async function where(bot, name) {
      const deadline = Date.now() + 25000
      while (Date.now() < deadline) {
        const response = await command(bot, '/whtest where', /FIXTURE server=/)
        if (response.includes(`server=${name} `)) return response
        await bounded(context.sleep(500), 1500, 'server arrival')
      }
      throw new Error(`Player did not reach ${name}`)
    }

    async function exported(bot, text, prefix) {
      const response = event(bot, 'message', message => JSON.stringify(message.json).includes(prefix))
      bot.chat(text)
      const [message] = await response
      const code = JSON.stringify(message.json).match(new RegExp(`${prefix.replace('.', '\\.')}[A-Za-z0-9_-]+`))?.[0]
      context.expect(Boolean(code), 'Export contains a current-format code')
      return code
    }

    async function ready(bot, peer) {
      const deadline = Date.now() + 30000
      while (Date.now() < deadline) {
        const response = await command(bot, '/wh server list', new RegExp(`${peer} (ready|offline) `))
        if (response.includes(`${peer} ready `)) return
        await bounded(context.sleep(1000), 2000, 'peer readiness')
      }
      throw new Error(`${peer} did not become ready`)
    }

    async function stats(bot) {
      const response = await command(bot, '/whtest stats', /FIXTURE completed=/)
      const values = response.match(/completed=(\d+) failed=(\d+) inFlight=(\d+)/)
      context.expect(Boolean(values), 'Traversal counters are available')
      return { completed: Number(values[1]), failed: Number(values[2]), inFlight: Number(values[3]) }
    }

    async function sample(bot, name) {
      const response = await command(bot, '/whtest ticks', /FIXTURE tickMillis=/)
      const values = response.match(/tickMillis=([\d.Ee+-]+) projections=(\d+) observers=(\d+) renderMillis=([\d.Ee+-]+)/)
      context.expect(Boolean(values), 'Projection and tick counters are available', response)
      const record = { server: name, tickMillis: Number(values[1]), projections: Number(values[2]), observers: Number(values[3]), renderMillis: Number(values[4]) }
      Object.assign(record, Object.fromEntries([...response.matchAll(/(\w+)=([\d.Ee+-]+)/g)].map(match => [match[1], Number(match[2])])) )
      record.platform = response.match(/platform=([^ ]+)/)?.[1]
      if (process.env.WORMHOLES_EXPECT_PLATFORM) {
        context.expect(record.platform?.toLowerCase() === process.env.WORMHOLES_EXPECT_PLATFORM.toLowerCase(),
          'The backend uses the expected server platform', record)
      }
      if (process.env.WORMHOLES_EXPECT_FRAME_BUDGET_MICROS) {
        context.expect(record.frameBudgetMicros === Number(process.env.WORMHOLES_EXPECT_FRAME_BUDGET_MICROS),
          'The backend uses the expected projection frame budget', record)
      }
      evidence.samples.push(record)
      return record
    }

    async function transfer(name, gateway) {
      const uuid = context.bot.player.uuid
      const approach = { destination: name, positions: [] }
      let previousPositionAt = 0
      const recordPosition = () => {
        if (Date.now() - previousPositionAt < 250) return
        previousPositionAt = Date.now()
        approach.positions.push({ at: previousPositionAt, position: context.bot.entity.position.clone() })
      }
      let spawned
      if (gateway) {
        const gate = await command(context.bot, '/whtest gate', /FIXTURE gate /)
        context.expect(gate.includes('open=true valid=true projection=ON'), 'Gateway is open, linked, and projecting before traversal', gate)
        await command(context.bot, '/whtest stage', /FIXTURE staged true/)
        await bounded(context.sleep(2000), 3000, 'gateway cooldown')
        const start = context.bot.entity.position.clone()
        context.expect(Math.abs(start.x - 8.5) < 0.2 && Math.abs(start.z - 12.5) < 0.2 && Math.abs(start.y - 101) < 0.2, 'Traveler stands on the gateway approach', start)
        approach.gate = gate
        evidence.approaches.push(approach)
        context.bot.on('move', recordPosition)
        await context.bot.lookAt(start.offset(0, context.bot.entity.height, -10), true)
        spawned = event(context.bot, 'login', () => true, 30000)
        context.bot.setControlState('forward', true)
      } else {
        spawned = event(context.bot, 'login', () => true, 30000)
        context.bot.chat(`/wh server connect ${name}`)
      }
      try {
        await spawned
      } finally {
        context.bot.clearControlStates()
        context.bot.removeListener('move', recordPosition)
      }
      await bounded(context.sleep(1500), 2500, 'arrival positioning')
      const response = await where(context.bot, name)
      context.expect(response.includes(`uuid=${uuid}`), 'Velocity preserves the player UUID', response)
      context.expect(response.includes('transferred=false'), 'Velocity handoff does not use native client transfer', response)
      const position = context.bot.entity.position.clone()
      if (gateway) {
        context.expect(Math.abs(position.x - 8.5) < 4 && Math.abs(position.y - 101) < 4 && Math.abs(position.z - 8.5) < 6,
          'Gateway handoff arrives at the destination frame', position)
      }
      evidence.arrivals.push({ server: name, gateway, uuid, position })
    }

    const countBlock = () => { evidence.blockPackets++ }
    const countTransfer = () => { evidence.nativeTransfers++ }
    context.bot._client.on('multi_block_change', countBlock)
    context.bot._client.on('transfer', countTransfer)
    const primaryEnd = reason => { if (!closing) fail(`Traveler disconnected: ${reason}`) }
    const primaryDeath = () => { if (!closing) fail('Traveler died') }
    context.bot.on('end', primaryEnd)
    context.bot.on('death', primaryDeath)
    configureMovement(context.bot)
    try {
      await context.step('Connect destination operator through Velocity', async () => {
        keeper = mineflayer.createBot({ host: context.server.host, port: context.server.port, username: 'VelocityKeeper', auth: 'offline', version: context.bot.version })
        configureMovement(keeper)
        keeper.on('error', error => { if (!closing) fail(`Keeper error: ${error.message}`) })
        keeper.on('kicked', reason => { if (!closing) fail(`Keeper kicked: ${JSON.stringify(reason)}`) })
        keeper.on('end', reason => { if (!closing) fail(`Keeper disconnected: ${reason}`) })
        keeper.on('death', () => { if (!closing) fail('Keeper died') })
        keeper.on('messagestr', message => evidence.messages.push(message))
        await event(keeper, 'spawn', () => true, 30000)
        await bounded(context.sleep(1000), 2000, 'keeper login')
        const spawned = event(keeper, 'login', () => true, 30000)
        keeper.chat(`/server ${destination}`)
        await spawned
        await where(keeper, destination)
        await where(context.bot, source)
      })
      if (process.env.WORMHOLES_TEST_DEBUG === 'true') {
        await context.step('Enable verbose diagnostics on both backends', async () => {
          evidence.debugEnabled = []
          for (const bot of [context.bot, keeper]) {
            evidence.debugEnabled.push(await command(bot, '/wh debug toggle', /Debug logging enabled\./))
          }
        })
      }
      await context.step('Create linked gateways with remote projections', async () => {
        for (const bot of [context.bot, keeper]) await command(bot, '/whtest setup', /FIXTURE ready/)
        const sourceCode = await exported(context.bot, '/wh server export', 'WHS2.')
        const destinationCode = await exported(keeper, '/wh server export', 'WHS2.')
        await command(context.bot, `/wh server import ${destinationCode}`, /saved|imported|linked/i)
        await command(keeper, `/wh server import ${sourceCode}`, /saved|imported|linked/i)
        const sourcePortal = await exported(context.bot, '/whtest export', 'WHP6.')
        const destinationPortal = await exported(keeper, '/whtest export', 'WHP6.')
        await command(context.bot, `/whtest import ${destinationPortal}`, /linked/i)
        await command(keeper, `/whtest import ${sourcePortal}`, /linked/i)
        await ready(context.bot, destination)
        await ready(keeper, source)
        for (const bot of [context.bot, keeper]) {
          await command(bot, '/whtest projection true', /FIXTURE projection=ON/)
          await command(bot, '/whtest stage', /FIXTURE staged true/)
        }
      })
      if (process.env.WORMHOLES_TEST_ACCESS === 'true') {
        await context.step('Reject a blacklisted guest then allow the revoked node', async () => {
          guest = mineflayer.createBot({ host: context.server.host, port: context.server.port, username: 'VelocityGuest', auth: 'offline', version: context.bot.version })
          configureMovement(guest)
          guest.on('error', error => { if (!closing) fail(`Guest error: ${error.message}`) })
          guest.on('kicked', reason => { if (!closing) fail(`Guest kicked: ${JSON.stringify(reason)}`) })
          guest.on('end', reason => { if (!closing) fail(`Guest disconnected: ${reason}`) })
          guest.on('death', () => { if (!closing) fail('Guest died') })
          guest._client.on('transfer', packet => fail(`Guest received native transfer: ${JSON.stringify(packet)}`))
          guest.on('messagestr', message => evidence.messages.push(`Guest: ${message}`))
          await event(guest, 'spawn', () => true, 30000)
          await bounded(context.sleep(1000), 2000, 'guest login')
          const grant = await command(context.bot, '/whtest grant VelocityGuest true', /FIXTURE permission=.* granted=true/)
          await command(context.bot, '/minecraft:tp VelocityGuest 8.5 101 12.5 180 0', /Teleported/)
          await bounded(context.sleep(1500), 2500, 'guest staging')
          await guest.lookAt(guest.entity.position.offset(0, guest.entity.height, -10), true)
          const denied = event(guest, 'messagestr', message => /portal access denied/i.test(message), 12000)
          guest.setControlState('forward', true)
          let denial
          try {
            denial = (await denied)[0]
          } finally {
            guest.clearControlStates()
          }
          const sourcePresence = await command(context.bot, '/minecraft:data get entity VelocityGuest Pos', /VelocityGuest has the following entity data/)
          const revoke = await command(context.bot, '/whtest grant VelocityGuest false', /FIXTURE permission=.* granted=false/)
          await command(context.bot, '/minecraft:tp VelocityGuest 8.5 101 12.5 180 0', /Teleported/)
          await bounded(context.sleep(2500), 3500, 'guest retry cooldown')
          const uuid = guest.player.uuid
          await guest.lookAt(guest.entity.position.offset(0, guest.entity.height, -10), true)
          const arrived = event(guest, 'login', () => true, 20000)
          guest.setControlState('forward', true)
          try {
            await arrived
          } finally {
            guest.clearControlStates()
          }
          await bounded(context.sleep(1500), 2500, 'guest arrival')
          context.expect(guest.player.uuid === uuid, 'Ordinary player keeps its UUID after the allowed transfer')
          const position = guest.entity.position.clone()
          context.expect(Math.abs(position.x - 8.5) < 4 && Math.abs(position.y - 101) < 4 && Math.abs(position.z - 8.5) < 6,
            'Ordinary player arrives at the linked frame after removing the blacklist grant', position)
          const destinationPresence = await command(keeper, '/minecraft:data get entity VelocityGuest Pos', /VelocityGuest has the following entity data/)
          const deadline = Date.now() + 15000
          while ((await stats(context.bot)).inFlight !== 0) {
            context.expect(Date.now() < deadline, 'Ordinary player handoff must settle')
            await bounded(context.sleep(500), 1500, 'guest handoff settlement')
          }
          evidence.access = { grant, denial, sourcePresence, revoke, uuid, position, destinationPresence }
        })
      }
      evidence.before = { source: await stats(context.bot), destination: await stats(keeper) }
      await context.step('Observe moving remote projections', async () => {
        const end = Date.now() + dwellMs
        while (Date.now() < end) {
          for (const bot of [context.bot, keeper]) await bot.lookAt(bot.entity.position.offset(0, bot.entity.height, -10), true)
          context.bot.setControlState('left', true)
          await bounded(context.sleep(500), 1500, 'left movement')
          context.bot.setControlState('left', false)
          context.bot.setControlState('right', true)
          await bounded(context.sleep(500), 1500, 'right movement')
          context.bot.setControlState('right', false)
          await sample(context.bot, source)
          await sample(keeper, destination)
          await bounded(context.sleep(1000), 2000, 'projection sampling')
        }
        context.expect(evidence.samples.some(item => item.projections > 0 && item.renderMillis > 0), 'Remote projection work ran during sampling')
      })
      for (const gateway of [false, true, true]) {
        await context.step(`${gateway ? 'Walk gateway' : 'Command transfer'} to ${destination}`, () => transfer(destination, gateway))
        await context.step(`${gateway ? 'Walk gateway' : 'Command transfer'} to ${source}`, () => transfer(source, gateway))
      }
      await context.step('Confirm all six handoffs and stable proxy connection', async () => {
        const deadline = Date.now() + 15000
        do {
          evidence.after = { source: await stats(context.bot), destination: await stats(keeper) }
          if (evidence.after.source.inFlight === 0 && evidence.after.destination.inFlight === 0) break
          await bounded(context.sleep(500), 1500, 'handoff acknowledgments')
        } while (Date.now() < deadline)
        for (const side of ['source', 'destination']) {
          context.expect(evidence.after[side].completed === evidence.before[side].completed + 3, 'Each server confirmed three arrivals', evidence.after)
          context.expect(evidence.after[side].failed === evidence.before[side].failed && evidence.after[side].inFlight === 0, 'No handoff failed or remained in flight', evidence.after)
        }
        context.expect(evidence.nativeTransfers === 0, 'All handoffs stayed inside the Velocity connection')
        context.expect(!context.bot._client.ended && !keeper._client.ended, 'Both players stayed connected')
        context.expect(evidence.errors.length === 0, 'No auxiliary player errors occurred', evidence.errors)
      })
      if (Number(process.env.WORMHOLES_SCALE_PLAYERS ?? 0) > 0) {
        await runGatewayScale(context, { mineflayer, source, destination, keeper, command, event,
          bounded, stats, sample, configureMovement, fail, disconnect })
      }
      if (process.env.WORMHOLES_TEST_DEBUG === 'true') {
        await context.step('Disable verbose diagnostics on both backends', async () => {
          evidence.debugDisabled = []
          for (const bot of [context.bot, keeper]) {
            evidence.debugDisabled.push(await command(bot, '/wh debug toggle', /Debug logging disabled\./))
          }
        })
      }
    } finally {
      context.bot.clearControlStates()
      context.bot._client.removeListener('multi_block_change', countBlock)
      context.bot._client.removeListener('transfer', countTransfer)
      context.bot.removeListener('end', primaryEnd)
      context.bot.removeListener('death', primaryDeath)
      closing = true
      try {
        const results = await Promise.allSettled([keeper, guest].map(disconnect))
        const failures = results.filter(result => result.status === 'rejected')
        context.expect(failures.length === 0, 'Every auxiliary bot disconnected cleanly', failures.map(result => String(result.reason)))
      } finally {
        for (const restore of resumeWriters) restore()
      }
    }
  }
}
