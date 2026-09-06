import { createRequire } from 'node:module'

export default {
  name: 'cross-server-transfer',
  description: 'Retains an observer while a traveler transfers between linked servers and gateway frames.',
  async run(context) {
    const require = createRequire(process.argv[1])
    const mineflayer = require('mineflayer')
    const destination = {
      host: process.env.WORMHOLES_DEST_HOST ?? context.server.host,
      port: Number(process.env.WORMHOLES_DEST_PORT),
      name: process.env.WORMHOLES_DEST_NAME ?? 'link-b'
    }
    const source = {
      host: context.server.host,
      port: context.server.port,
      name: process.env.WORMHOLES_SOURCE_NAME ?? 'link-a'
    }
    const native = process.env.WORMHOLES_NATIVE_TRANSFER === 'true'
    const refusal = process.env.WORMHOLES_REFUSAL
    const commandRounds = Number(process.env.WORMHOLES_COMMAND_ROUNDS ?? 2)
    const gatewayRounds = Number(process.env.WORMHOLES_GATEWAY_ROUNDS ?? 2)
    const expectedPerServer = commandRounds + gatewayRounds
    context.expect(Number.isInteger(destination.port) && destination.port > 0 && destination.port <= 65535,
      'WORMHOLES_DEST_PORT must be a valid Minecraft port')
    context.expect(refusal === undefined || refusal === 'wrong-endpoint' || refusal === 'offline', 'Unknown refusal mode')
    context.expect([commandRounds, gatewayRounds].every(rounds => Number.isInteger(rounds) && rounds >= 0 && rounds <= 10)
      && expectedPerServer > 0, 'Transfer rounds must be integers from zero through ten, with at least one round')
    const sessions = new Set()
    const evidence = { native, commandRounds, gatewayRounds, jarSha256: process.env.WORMHOLES_JAR_SHA256,
      transfers: [], arrivals: [], messages: [], errors: [] }
    context.report.crossServer = evidence
    let rejectFatal
    const fatal = new Promise((resolve, reject) => { rejectFatal = reject })
    fatal.catch(() => {})

    function fail(message) {
      evidence.errors.push(message)
      rejectFatal(new Error(message))
    }

    function bounded(promise, timeout, label) {
      let timer
      return Promise.race([
        promise,
        fatal,
        new Promise((resolve, reject) => {
          timer = setTimeout(() => reject(new Error(`${label} timed out after ${timeout}ms`)), timeout)
        })
      ]).finally(() => clearTimeout(timer))
    }

    function event(emitter, name, predicate, timeout = 15000) {
      let listener
      const result = new Promise((resolve, reject) => {
        listener = (...args) => {
          try {
            if (predicate(...args)) resolve(args)
          } catch (error) {
            reject(error)
          }
        }
        emitter.on(name, listener)
      })
      return bounded(result, timeout, name).finally(() => emitter.removeListener(name, listener))
    }

    async function chat(session, command, expected, timeout = 15000) {
      const response = event(session.bot, 'messagestr', text => expected.test(text), timeout)
      session.bot.chat(command)
      return (await response)[0]
    }

    async function connect(endpoint, username, transferred = false) {
      const bot = mineflayer.createBot({
        host: endpoint.host, port: endpoint.port, username, auth: 'offline',
        version: context.bot.version, hideErrors: true, logErrors: false
      })
      const session = { bot, endpoint, closing: false, expectedTransfer: false, handshake: null, transferCount: 0 }
      sessions.add(session)
      const originalWrite = bot._client.write.bind(bot._client)
      bot._client.write = (name, packet) => {
        if (name === 'set_protocol') {
          packet = { ...packet, nextState: transferred ? 3 : 2 }
          session.handshake = packet.nextState
        }
        originalWrite(name, packet)
      }
      bot.on('error', error => { if (!session.closing) fail(`${username}: ${error.message}`) })
      bot.on('kicked', reason => { if (!session.closing) fail(`${username} kicked: ${JSON.stringify(reason)}`) })
      bot.on('death', () => { if (!session.closing) fail(`${username} died during the scenario`) })
      bot.on('end', reason => {
        if (!session.closing) fail(`${username} disconnected before cleanup: ${reason}`)
      })
      bot.on('messagestr', message => evidence.messages.push({ server: endpoint.name, username, message }))
      bot._client.on('transfer', packet => {
        session.transferCount++
        if (!session.expectedTransfer) fail(`${username} received an unexpected transfer: ${JSON.stringify(packet)}`)
      })
      await event(bot, 'spawn', () => true, 30000)
      context.expect(session.handshake === (transferred ? 3 : 2), 'Correct login intention was sent')
      await bounded(context.sleep(1200), 2000, 'spawn settlement')
      return session
    }

    async function close(session) {
      if (session.closing) return
      session.closing = true
      session.bot.clearControlStates()
      if (session.bot._client.ended) return
      const ended = new Promise(resolve => session.bot.once('end', resolve))
      session.bot.quit()
      let timer
      try {
        await Promise.race([ended, new Promise((resolve, reject) => {
          timer = setTimeout(() => reject(new Error('Auxiliary disconnect timed out')), 5000)
        })])
      } finally {
        clearTimeout(timer)
      }
    }

    async function exported(session, command, prefix) {
      const response = event(session.bot, 'message', message => JSON.stringify(message.json).includes(prefix))
      session.bot.chat(command)
      const [message] = await response
      const code = JSON.stringify(message.json).match(new RegExp(`${prefix.replace('.', '\\.')}[A-Za-z0-9_-]+`))?.[0]
      context.expect(Boolean(code), 'Export includes a copyable current-format code')
      return code
    }

    async function stats(session) {
      const message = await chat(session, '/whtest stats', /FIXTURE completed=/)
      const values = message.match(/completed=(\d+) failed=(\d+) inFlight=(\d+)/)
      context.expect(Boolean(values), 'Source reports complete handoff counters', message)
      return { completed: Number(values[1]), failed: Number(values[2]), inFlight: Number(values[3]) }
    }

    async function peerState(session, expected) {
      const peer = session.endpoint.name === source.name ? destination.name : source.name
      const escaped = peer.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const row = new RegExp(`${escaped} (ready|offline) `, 'i')
      const deadline = Date.now() + 20000
      while (Date.now() < deadline) {
        const message = await chat(session, '/wh server list', row)
        if (new RegExp(`${escaped} ${expected} `, 'i').test(message)) return message
        await bounded(context.sleep(1000), 2000, 'peer readiness')
      }
      throw new Error(`${session.endpoint.name} did not report ${peer} ${expected}`)
    }

    async function confirmed(session, baseline) {
      const deadline = Date.now() + 15000
      while (Date.now() < deadline) {
        const current = await stats(session)
        context.expect(current.failed === baseline.failed, 'No handoff failed after dispatch', current)
        if (current.completed === baseline.completed + expectedPerServer && current.inFlight === 0) return current
        await bounded(context.sleep(1000), 2000, 'arrival receipt settlement')
      }
      throw new Error(`${session.endpoint.name} did not confirm all ${expectedPerServer} source arrivals`)
    }

    async function verifyArrival(session, priorUuid, gateway) {
      const message = await chat(session, '/whtest where', /FIXTURE server=/)
      context.expect(message.includes(`server=${session.endpoint.name}`), 'Player reached the intended server', message)
      context.expect(message.includes(`uuid=${priorUuid}`), 'Player UUID survived the transfer', message)
      context.expect(message.includes(`transferred=${native}`), 'Destination used the configured native or compatibility transfer path', message)
      const position = session.bot.entity.position.clone()
      if (gateway) {
        context.expect(Math.abs(position.x - 8.5) < 4 && Math.abs(position.y - 101) < 4 && Math.abs(position.z - 8.5) < 6,
          'Gateway arrival is at its destination frame', position)
      }
      evidence.arrivals.push({ server: session.endpoint.name, uuid: priorUuid, gateway, position, native })
    }

    async function transfer(session, endpoint, gateway) {
      const uuid = session.bot.player.uuid
      session.expectedTransfer = true
      const received = event(session.bot._client, 'transfer', () => true, 25000)
      received.catch(() => {})
      if (gateway) {
        await chat(session, '/whtest stage', /FIXTURE staged true/)
        await bounded(context.sleep(1500), 2500, 'gateway cooldown')
        const stage = session.bot.entity.position.clone()
        context.expect(Math.abs(stage.x - 8.5) < 0.2 && Math.abs(stage.y - 101) < 0.2 && Math.abs(stage.z - 12.5) < 0.2,
          'Traveler starts on the approach platform', stage)
        await session.bot.lookAt(stage.offset(0, session.bot.entity.height, -10), true)
        session.bot.setControlState('forward', true)
      } else {
        session.bot.chat(`/wh server connect ${endpoint.name}`)
      }
      const [packet] = await received
      session.bot.clearControlStates()
      context.expect(packet.host === endpoint.host && packet.port === endpoint.port,
        'Transfer packet contains the complete intended Minecraft endpoint', packet)
      context.expect(session.transferCount === 1, 'One handoff emits exactly one transfer packet')
      evidence.transfers.push({ from: session.endpoint.name, to: endpoint.name, gateway, host: packet.host, port: packet.port })
      await close(session)
      const arrived = await connect(endpoint, session.bot.username, true)
      await verifyArrival(arrived, uuid, gateway)
      return arrived
    }

    try {
      let traveler = await context.step('Connect auxiliary traveler on source', () => connect(source, 'GateTraveler'))
      if (refusal) {
        await context.step(`Reject ${refusal} without disconnecting source`, async () => {
          await peerState(traveler, refusal === 'wrong-endpoint' ? 'ready' : 'offline')
          const message = await chat(traveler, `/wh server connect ${destination.name}`, /failed|unavailable|offline|not ready|cannot|could not|endpoint|reachable/i, 25000)
          await bounded(context.sleep(3000), 4000, 'refusal settlement')
          context.expect(traveler.transferCount === 0, 'Refusal emitted no transfer packet')
          const where = await chat(traveler, '/whtest where', /FIXTURE server=/)
          context.expect(where.includes(`server=${source.name}`), 'Rejected traveler remains connected to source')
          evidence.refusal = { kind: refusal, message, source: where }
        })
        return
      }
      const keeper = await context.step('Connect destination fixture operator', () => connect(destination, 'GateKeeper'))
      if (process.env.WORMHOLES_RELOAD === 'true') {
        await context.step('Reload both plugins while observers remain connected', async () => {
          await chat(traveler, '/wh reload', /configuration and language files reloaded/i)
          await chat(keeper, '/wh reload', /configuration and language files reloaded/i)
          await peerState(traveler, 'ready')
          await peerState(keeper, 'ready')
        })
      }
      await context.step('Construct frame gateways', async () => {
        await chat(traveler, '/whtest setup', /FIXTURE ready/)
        await chat(keeper, '/whtest setup', /FIXTURE ready/)
      })
      await context.step('Exchange server and gateway export codes', async () => {
        const serverA = await exported(traveler, '/wh server export', 'WHS2.')
        const serverB = await exported(keeper, '/wh server export', 'WHS2.')
        await chat(traveler, `/wh server import ${serverB}`, /saved|imported|linked/i)
        await chat(keeper, `/wh server import ${serverA}`, /saved|imported|linked/i)
        const portalA = await exported(traveler, '/whtest export', 'WHP6.')
        const portalB = await exported(keeper, '/whtest export', 'WHP6.')
        await chat(traveler, `/whtest import ${portalB}`, /linked/i)
        await chat(keeper, `/whtest import ${portalA}`, /linked/i)
        for (const session of [traveler, keeper]) {
          await peerState(session, 'ready')
        }
      })
      evidence.statsBefore = { source: await stats(traveler), destination: await stats(keeper) }
      for (let round = 1; round <= commandRounds; round++) {
        traveler = await context.step(`Command transfer A to B round ${round}`, () => transfer(traveler, destination, false))
        traveler = await context.step(`Command transfer B to A round ${round}`, () => transfer(traveler, source, false))
      }
      for (let round = 1; round <= gatewayRounds; round++) {
        traveler = await context.step(`Walk gateway A to B round ${round}`, () => transfer(traveler, destination, true))
        traveler = await context.step(`Walk gateway B to A round ${round}`, () => transfer(traveler, source, true))
      }
      context.expect(evidence.transfers.length === expectedPerServer * 2 && evidence.arrivals.length === expectedPerServer * 2,
        'All requested handoffs completed')
      await context.step('Confirm all arrivals at their source servers', async () => {
        evidence.statsAfter = {
          source: await confirmed(traveler, evidence.statsBefore.source),
          destination: await confirmed(keeper, evidence.statsBefore.destination)
        }
      })
      context.expect(!context.bot._client.ended, 'Primary observer remained connected throughout')
    } finally {
      await Promise.allSettled([...sessions].map(close)).then(results => {
        const failures = results.filter(result => result.status === 'rejected')
        context.expect(failures.length === 0, 'Every auxiliary bot disconnected cleanly', failures.map(result => String(result.reason)))
      })
    }
  }
}
