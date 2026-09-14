export async function runGatewayScale(context, helpers) {
  const { mineflayer, source, destination, keeper, command, event, bounded, stats,
    sample, configureMovement, fail, disconnect } = helpers
  const playerCount = Number(process.env.WORMHOLES_SCALE_PLAYERS ?? 12)
  const rounds = Number(process.env.WORMHOLES_SCALE_ROUNDS ?? 3)
  const capacity = Number(process.env.WORMHOLES_SCALE_MAX_PLAYERS ?? 16)
  context.expect(Number.isInteger(playerCount) && playerCount >= 12 && playerCount <= 62,
    'Scale test has between 12 and 62 ordinary players', playerCount)
  context.expect(Number.isInteger(rounds) && rounds >= 1 && rounds <= 20,
    'Scale test has between one and twenty round trips', rounds)
  context.expect(Number.isInteger(capacity) && capacity >= playerCount + 3,
    'Backend capacity accommodates the simultaneous travelers and observer', capacity)
  const identities = Array.from({ length: playerCount }, (_, index) => ({ name: `WhScale${index}`, mode: 'ordinary' }))
  identities.push({ name: 'WhScaleOp', mode: 'op' }, { name: 'WhScaleWildcard', mode: 'wildcard' })
  const evidence = { ordinaryPlayers: playerCount, concurrentPlayers: identities.length, rounds,
    waves: [], rejections: [], expectedRejections: [], probes: [], samples: [], errors: [], nativeTransfers: 0, movement: [] }
  context.report.velocity.scale = evidence
  const bots = []
  const observerPackets = { [source]: 0, [destination]: 0 }
  const packetListeners = [source, destination].map(name => {
    const listener = () => { observerPackets[name]++ }
    observer(name)._client.on('multi_block_change', listener)
    return { name, listener }
  })
  const expectedDenials = new Set()
  let closing = false
  const sleep = milliseconds => bounded(context.sleep(milliseconds), milliseconds + 2000, 'scale wait')
  function observer(name) {
    return name === source ? context.bot : keeper
  }

  function watch(bot) {
    for (const eventName of ['login', 'spawn', 'forcedMove']) {
      bot.on(eventName, () => {
        if (evidence.movement.length >= 2000) return
        evidence.movement.push({ username: bot.username, event: eventName, at: Date.now(),
          position: bot.entity ? { ...bot.entity.position } : null, velocity: bot.entity ? { ...bot.entity.velocity } : null })
      })
    }
    bot.on('error', error => { if (!closing) fail(`${bot.username}: ${error.stack ?? error}`) })
    bot.on('kicked', reason => { if (!closing) fail(`${bot.username} kicked: ${JSON.stringify(reason)}`) })
    bot.on('end', reason => { if (!closing) fail(`${bot.username} disconnected: ${reason}`) })
    bot.on('death', () => { if (!closing) fail(`${bot.username} died`) })
    bot._client.on('transfer', packet => {
      evidence.nativeTransfers++
      fail(`${bot.username} received native transfer: ${JSON.stringify(packet)}`)
    })
    bot.on('messagestr', message => {
      if (!/access denied|rejected|handoff failed|transfer failed|destination.*not ready/i.test(message)) return
      const item = { username: bot.username, at: Date.now(), message }
      if (expectedDenials.has(bot.username)) evidence.expectedRejections.push(item)
      else evidence.rejections.push(item)
    })
  }

  async function identity(operator, player, mode) {
    return command(operator, `/whtest scale identity ${player.name} ${mode}`,
      new RegExp(`SCALE identity username=${player.name} mode=${mode.toUpperCase()}`))
  }

  async function stage(operator, bot, lane) {
    return command(operator, `/whtest scale stage ${bot.username} ${lane}`,
      new RegExp(`SCALE staged username=${bot.username} success=true`))
  }

  async function probe(operator, bot, name) {
    const response = await command(operator, `/whtest scale probe ${bot.username}`,
      new RegExp(`SCALE probe username=${bot.username} `))
    context.expect(response.includes(`online=true server=${name} `), 'Traveler is present on the expected backend', response)
    evidence.probes.push(response)
    return response
  }

  async function settle() {
    const deadline = Date.now() + 20000
    let snapshot
    do {
      snapshot = { source: await stats(context.bot), destination: await stats(keeper) }
      if (snapshot.source.inFlight === 0 && snapshot.destination.inFlight === 0) return snapshot
      await sleep(250)
    } while (Date.now() < deadline)
    throw new Error(`Scale handoffs did not settle: ${JSON.stringify(snapshot)}`)
  }

  async function cross(group, from, to) {
    for (let lane = 0; lane < group.length; lane++) await stage(observer(from), group[lane], lane + 1)
    await sleep(2200)
    await Promise.all(group.map(async bot => {
      await bot.lookAt(bot.entity.position.offset(0, bot.entity.height, -10), true)
      const arrived = event(bot, 'login', () => true, 30000)
      bot.setControlState('forward', true)
      bot.setControlState('jump', true)
      try {
        await arrived
      } finally {
        bot.clearControlStates()
      }
    }))
    await sleep(1500)
    return Promise.all(group.map(bot => probe(observer(to), bot, to)))
  }

  async function wave(from, to, index) {
    const operator = observer(from)
    const receiver = observer(to)
    for (const watcher of [context.bot, keeper]) {
      watcher.clearControlStates()
      await stage(watcher, watcher, -1)
      await watcher.lookAt(watcher.entity.position.offset(4, watcher.entity.height, -4), true)
    }
    for (let lane = 0; lane < bots.length; lane++) await stage(operator, bots[lane], lane)
    await sleep(2200)
    const staged = await sample(operator, from)
    context.expect(staged.online === bots.length + 1 && staged.maxPlayers === capacity,
      'All simultaneous travelers and their observer occupy the configured backend capacity', staged)
    context.expect(staged.projectorViewers >= 10 && staged.aperture === 12 && staged.depth === 64,
      'At least ten simultaneous travelers view the full-depth remote gateway before jumping', staged)
    for (const watcher of [context.bot, keeper]) await command(watcher, '/whtest tickstats reset', /HORIZONTAL ticksReset/)
    await Promise.all(bots.map(bot => bot.lookAt(bot.entity.position.offset(0, bot.entity.height, -10), true)))
    const record = { index, source: from, destination: to, startedAt: Date.now(), arrivals: [], observers: [] }
    const captureObservers = () => {
      for (const watcher of [context.bot, keeper]) {
        const position = { ...watcher.entity.position }
        const snapshot = { username: watcher.username, at: Date.now(), position, health: watcher.health }
        record.observers.push(snapshot)
        context.expect(position.x > 1.3 && position.x < 14.7 && position.z > 1.3 && position.z < 14.7
          && position.y >= 100.9 && position.y < 107 && watcher.health > 0,
        'Moving observer remains alive on the fixture platform', snapshot)
      }
    }
    captureObservers()
    const packetsBefore = { ...observerPackets }
    const sampleStart = evidence.samples.length
    evidence.waves.push(record)
    let completed = false
    const moving = (async () => {
      while (!completed) {
        for (const direction of ['left', 'right']) {
          for (const watcher of [context.bot, keeper]) watcher.setControlState(direction, true)
          try {
            await sleep(350)
          } finally {
            for (const watcher of [context.bot, keeper]) watcher.clearControlStates()
          }
          captureObservers()
        }
        evidence.samples.push(await sample(context.bot, source), await sample(keeper, destination))
      }
    })()
    moving.catch(() => {})
    try {
      const arrivals = bots.map(async bot => {
        const uuid = bot.player.uuid
        const arrival = event(bot, 'login', () => true, 30000)
        bot.setControlState('forward', true)
        bot.setControlState('jump', true)
        try {
          await arrival
        } finally {
          bot.clearControlStates()
        }
        record.arrivals.push({ username: bot.username, uuid, elapsedMillis: Date.now() - record.startedAt })
      })
      await Promise.all(arrivals)
    } finally {
      completed = true
      for (const bot of bots) bot.clearControlStates()
      await moving
    }
    await sleep(1500)
    captureObservers()
    for (let position = 0; position < bots.length; position++) {
      const bot = bots[position]
      const response = await probe(receiver, bot, to)
      const arrival = record.arrivals.find(item => item.username === bot.username)
      context.expect(response.includes(`uuid=${arrival.uuid} `) && response.includes('transferred=false'),
        'Proxy handoff preserves identity and avoids native transfer', response)
      const privileged = identities[position].mode !== 'ordinary'
      context.expect(response.includes('allowed=true'), 'Every arriving traveler retains portal access', response)
      if (privileged) {
        context.expect(response.includes('role=DENIED'), 'Privilege overrides an explicit denied role', response)
        context.expect(response.includes(identities[position].mode === 'op' ? 'op=true' : 'op=false wildcard=true'),
          'Privilege test uses the intended operator or literal wildcard identity', response)
      }
      const location = bot.entity.position
      context.expect(Math.abs(location.x - 8.5) < 5 && location.y >= 100 && location.y < 107 && Math.abs(location.z - 8.5) < 7,
        'Concurrent traveler arrives safely beside the destination gateway', { username: bot.username, location })
    }
    record.finishedAt = Date.now()
    record.staged = staged
    record.tickTiming = {}
    for (const name of [source, destination]) {
      const timing = await command(observer(name), '/whtest tickstats', /HORIZONTAL ticks count=/)
      record.tickTiming[name] = Object.fromEntries([...timing.matchAll(/(\w+)=([\d.Ee+-]+)/g)].map(match => [match[1], Number(match[2])]))
    }
    record.observerPackets = Object.fromEntries([source, destination].map(name => [name, observerPackets[name] - packetsBefore[name]]))
    for (const name of [source, destination]) {
      context.expect(evidence.samples.slice(sampleStart).some(item => item.server === name && item.projections > 0 && item.renderMillis > 0),
        'Each moving observer retains active remote projection work during the burst', { server: name, samples: evidence.samples.slice(sampleStart) })
      context.expect(record.observerPackets[name] > 0, 'Each moving observer receives projected block updates during the burst', record.observerPackets)
    }
    record.counters = await settle()
    context.expect(record.arrivals.length === bots.length, 'Every simultaneous traveler completed the handoff', record)
    process.stderr.write(`VELOCITY scale wave=${index} travelers=${bots.length} arrived=${record.arrivals.length} rejections=${evidence.rejections.length}\n`)
  }

  try {
    await context.step('Prepare concurrent ordinary, operator, and wildcard travelers', async () => {
      for (const player of identities) {
        for (const operator of [context.bot, keeper]) await identity(operator, player, player.mode)
      }
      for (const player of identities) {
        const bot = mineflayer.createBot({ host: context.server.host, port: context.server.port,
          username: player.name, auth: 'offline', version: context.bot.version })
        bots.push(bot)
        configureMovement(bot)
        watch(bot)
        await event(bot, 'spawn', () => true, 30000)
      }
      for (const operator of [context.bot, keeper]) {
        await stage(operator, operator, -1)
        await operator.lookAt(operator.entity.position.offset(4, operator.entity.height, -4), true)
      }
      context.expect(bots.length >= 14, 'At least fourteen travelers remain connected together')
    })
    await context.step('Confirm ordinary denial remains enforced before the allowed load test', async () => {
      const bot = bots[0]
      expectedDenials.add(bot.username)
      await identity(context.bot, identities[0], 'denied')
      await stage(context.bot, bot, 1)
      await sleep(1500)
      await bot.lookAt(bot.entity.position.offset(0, bot.entity.height, -10), true)
      const denied = event(bot, 'messagestr', message => /portal access denied/i.test(message), 12000)
      bot.setControlState('forward', true)
      try {
        await denied
      } finally {
        bot.clearControlStates()
      }
      await probe(context.bot, bot, source)
      await identity(context.bot, identities[0], 'ordinary')
      await stage(context.bot, bot, 1)
      await sleep(3000)
      const restored = await probe(context.bot, bot, source)
      context.expect(restored.includes('role=null allowed=true') && bot.entity.position.z > 12,
        'The ordinary control regains access away from the portal after its denial notice expires', restored)
      expectedDenials.delete(bot.username)
      context.expect(evidence.expectedRejections.length > 0, 'An ordinary explicitly denied traveler is rejected')
    })
    await context.step('Honor source-only operator and wildcard access without granting destination privileges', async () => {
      const privileged = bots.slice(-2)
      const modes = identities.slice(-2)
      for (const player of modes) await identity(keeper, player, 'denied')
      await command(keeper, '/whtest scale incoming false', /SCALE incoming=false/)
      await command(keeper, '/whtest scale capacity 1', /SCALE capacity=1/)
      evidence.sourceOnly = { source: [], destinationBefore: [], destination: [], deniedReturn: [], returned: [] }
      evidence.sourceOnly.fullBefore = await sample(keeper, destination)
      context.expect(evidence.sourceOnly.fullBefore.online === 1 && evidence.sourceOnly.fullBefore.maxPlayers === 1,
        'The destination backend is already full before source-privileged arrivals', evidence.sourceOnly.fullBefore)
      for (const bot of privileged) {
        evidence.sourceOnly.source.push(await probe(context.bot, bot, source))
        const destinationBefore = await command(keeper, `/whtest scale probe ${bot.username}`,
          new RegExp(`SCALE probe username=${bot.username} `))
        context.expect(destinationBefore.includes('online=false op=false role=DENIED configuredMode=DENIED'),
          'The destination does not grant operator status or access before source-privileged arrival', destinationBefore)
        evidence.sourceOnly.destinationBefore.push(destinationBefore)
      }
      evidence.sourceOnly.destination = await cross(privileged, source, destination)
      evidence.sourceOnly.fullAfter = await sample(keeper, destination)
      context.expect(evidence.sourceOnly.fullAfter.online === 3 && evidence.sourceOnly.fullAfter.maxPlayers === 1,
        'Both source-privileged travelers bypass the full destination login limit', evidence.sourceOnly.fullAfter)
      for (const response of evidence.sourceOnly.destination) {
        context.expect(response.includes('op=false wildcard=false role=DENIED allowed=false'),
          'Source privilege permits the first crossing without changing destination permissions', response)
        context.expect(response.includes('incoming=false'), 'Source privilege crosses an incoming-disabled destination', response)
      }
      for (let index = 0; index < privileged.length; index++) {
        const bot = privileged[index]
        expectedDenials.add(bot.username)
        await stage(keeper, bot, index + 1)
        await sleep(1800)
        await bot.lookAt(bot.entity.position.offset(0, bot.entity.height, -10), true)
        const denied = event(bot, 'messagestr', message => /portal access denied/i.test(message), 12000)
        bot.setControlState('forward', true)
        try {
          evidence.sourceOnly.deniedReturn.push((await denied)[0])
        } finally {
          bot.clearControlStates()
        }
        await probe(keeper, bot, destination)
      }
      for (const player of modes) await identity(keeper, player, 'ordinary')
      await command(keeper, `/whtest scale capacity ${capacity}`, new RegExp(`SCALE capacity=${capacity}`))
      await command(keeper, '/whtest scale incoming true', /SCALE incoming=true/)
      evidence.sourceOnly.returned = await cross(privileged, destination, source)
      for (const bot of privileged) expectedDenials.delete(bot.username)
      for (const player of modes) await identity(keeper, player, player.mode)
      context.expect(evidence.sourceOnly.deniedReturn.length === 2,
        'Both return attempts obey the destination backend permissions')
    })
    await context.step('Honor a trusted role in whitelist mode without a portal permission grant', async () => {
      for (const operator of [context.bot, keeper]) {
        await identity(operator, identities[0], 'trusted')
        await command(operator, '/whtest scale mode whitelist', /SCALE mode=WHITELIST/)
      }
      evidence.trustedRole = await cross([bots[0]], source, destination)
      evidence.trustedRole.push(...await cross([bots[0]], destination, source))
      for (const response of evidence.trustedRole) {
        context.expect(response.includes('op=false wildcard=false role=USER allowed=true')
          && response.includes('nodeGranted=false roleWhitelist=true permissionMode=WHITELIST'),
        'A trusted non-operator traverses a whitelist portal without its permission node', response)
      }
      for (const operator of [context.bot, keeper]) {
        await command(operator, '/whtest scale mode blacklist', /SCALE mode=BLACKLIST/)
        await identity(operator, identities[0], 'ordinary')
      }
    })
    for (const name of [source, destination]) {
      const response = await probe(observer(name), observer(name), name)
      context.expect(response.includes('roleWhitelist=false permissionMode=BLACKLIST incoming=true'),
        'Both gateways permit ordinary travelers before simultaneous waves', response)
    }
    evidence.before = await settle()
    for (let round = 0; round < rounds; round++) {
      await context.step(`Concurrent jump wave ${round * 2 + 1} to ${destination}`, () => wave(source, destination, round * 2 + 1))
      await context.step(`Concurrent jump wave ${round * 2 + 2} to ${source}`, () => wave(destination, source, round * 2 + 2))
    }
    evidence.after = await settle()
    for (const side of ['source', 'destination']) {
      context.expect(evidence.after[side].completed === evidence.before[side].completed + bots.length * rounds,
        'Every simultaneous handoff is confirmed by the server counter', evidence)
      context.expect(evidence.after[side].failed === evidence.before[side].failed,
        'The scale run adds no failed handoffs', evidence)
    }
    context.expect(evidence.rejections.length === 0, 'Allowed players encounter zero rejections at scale', evidence.rejections)
    context.expect(evidence.nativeTransfers === 0, 'All scale handoffs stay on the proxy connection')
  } finally {
    closing = true
    for (const { name, listener } of packetListeners) observer(name)._client.removeListener('multi_block_change', listener)
    for (const operator of [context.bot, keeper]) operator.clearControlStates()
    const results = await Promise.allSettled(bots.map(disconnect))
    const failures = results.filter(result => result.status === 'rejected')
    context.expect(failures.length === 0, 'Every scale traveler disconnects cleanly', failures.map(result => String(result.reason)))
  }
}
