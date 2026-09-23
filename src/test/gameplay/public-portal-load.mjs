import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { portalWorkflow } from './support/portal-workflow.mjs'

function integer(value, name, minimum, maximum) {
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name} must be an integer from ${minimum} to ${maximum}`)
  }
  return value
}

export async function loadConfiguration(file = process.env.WORMHOLES_STRESS_CONFIG) {
  const input = file ? JSON.parse(await readFile(file, 'utf8')) : {}
  const supported = ['travelers', 'crossingsPerTraveler', 'observerLaps', 'crossingTimeoutMs']
  for (const key of Object.keys(input)) if (!supported.includes(key)) throw new Error(`Unknown stress setting: ${key}`)
  return {
    travelers: integer(input.travelers ?? 4, 'travelers', 1, 16),
    crossingsPerTraveler: integer(input.crossingsPerTraveler ?? 5, 'crossingsPerTraveler', 1, 200),
    observerLaps: integer(input.observerLaps ?? 2, 'observerLaps', 1, 100),
    crossingTimeoutMs: integer(input.crossingTimeoutMs ?? 15000, 'crossingTimeoutMs', 1000, 60000)
  }
}

export default {
  name: 'wormholes-public-portal-load',
  description: 'Author linked Wormholes through player controls, then measure ordinary-player crossings while an observer circles nearby.',
  async run(context) {
    const configuration = await loadConfiguration()
    const workflow = portalWorkflow(context)
    const { p, build, link, wormhole } = workflow
    const actors = []
    const prefix = `load_${Date.now().toString(36)}`
    const evidence = { configuration, actors: [], crossings: [], completed: 0 }
    context.report.portalLoad = evidence
    const logPath = path.join(context.server.directory, 'logs/latest.log')
    const logBefore = await readFile(logPath, 'utf8')
    let cancelled = false

    async function traveler(actor, index) {
      const record = { username: actor.bot.username, completed: 0 }
      evidence.actors.push(record)
      try {
        await actor.sleep(index * 250)
        for (let crossing = 0; crossing < configuration.crossingsPerTraveler; crossing++) {
          context.expect(!cancelled, 'Load cancelled after another actor failed')
          const source = crossing % 2 === 0 ? 0 : 24
          const destination = source === 0 ? 24 : 0
          await context.command(`/tp ${actor.bot.username} ${source + 0.5} 200 3.5`,
            new RegExp(`Teleported ${actor.bot.username} `))
          await actor.waitUntil(() => actor.bot.entity.position.distanceTo(p.point(source + 0.5, 200, 3.5)) < 0.4
            && actor.bot.blockAt(p.point(source, 199, 0)), { label: 'ordinary traveler staging' })
          await actor.bot.look(0, 0, true)
          const started = performance.now()
          actor.bot.setControlState('forward', true)
          try {
            await actor.waitUntil(() => {
              context.expect(!cancelled, 'Load cancelled after another actor failed')
              context.expect(actor.bot.health > 0, 'Ordinary traveler remains alive')
              context.expect(actor.bot.entity.position.y > 195, 'Traveler stays on the test platform')
              return Math.abs(actor.bot.entity.position.x - destination - 0.5) < 2
            }, { timeoutMs: configuration.crossingTimeoutMs, label: `${actor.bot.username} crossing ${crossing + 1}` })
          } finally {
            actor.bot.clearControlStates()
          }
          evidence.crossings.push({ actor: actor.bot.username, source, destination,
            latencyMs: Math.round(performance.now() - started), position: { ...actor.bot.entity.position } })
          record.completed++
          evidence.completed++
          await actor.sleep(400)
        }
      } catch (error) {
        cancelled = true
        throw error
      } finally {
        actor.bot.clearControlStates()
      }
    }

    try {
      await context.step('author the load-test Wormholes through player controls', async () => {
        await p.arena()
        await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
        await context.command('/wh wand rune=false', /wand/i)
        await build(0, `${prefix}_a`)
        await build(24, `${prefix}_b`)
        await link(0, `${prefix}_b`)
        await link(24, `${prefix}_a`)
        await wormhole(0)
        await wormhole(24)
        await p.emptyHand()
      })
      await context.step('join ordinary travelers without operator permissions', async () => {
        for (let index = 0; index < configuration.travelers; index++) {
          const actor = await context.connectActor(`WhLoad${Date.now().toString(36)}${index}`)
          actors.push(actor)
          await actor.command('/wh wand rune=false', /permission/i)
        }
      })
      await context.step('run concurrent physical crossings and observation circles', async () => {
        await p.stage(16, 200, 5)
        const started = performance.now()
        const results = await Promise.allSettled([
          context.actions.walkCircle({ center: { x: 12, y: 200, z: 5 }, radius: 4,
            laps: configuration.observerLaps, timeoutMs: Math.min(600000, configuration.observerLaps * 60000) })
            .then(result => { evidence.observer = result })
            .catch(error => { cancelled = true; throw error }),
          ...actors.map(traveler)
        ])
        evidence.durationMs = Math.round(performance.now() - started)
        const failures = results.filter(result => result.status === 'rejected').map(result => result.reason)
        if (failures.length) throw new AggregateError(failures, 'Portal load workload failed')
        const expected = configuration.travelers * configuration.crossingsPerTraveler
        context.expect(evidence.completed === expected, `All ${expected} crossings completed`, evidence)
        context.expect(evidence.actors.every(actor => actor.completed === configuration.crossingsPerTraveler),
          'Every ordinary actor completed its workload', evidence.actors)
        const latencies = evidence.crossings.map(crossing => crossing.latencyMs).sort((a, b) => a - b)
        evidence.latencyMs = { p50: latencies[Math.ceil(latencies.length * 0.5) - 1],
          p95: latencies[Math.ceil(latencies.length * 0.95) - 1], max: latencies.at(-1) }
        evidence.crossingsPerSecond = evidence.completed / (evidence.durationMs / 1000)
      })
      await context.step('inspect the server log for workload errors', async () => {
        const after = await readFile(logPath, 'utf8')
        context.expect(after.startsWith(logBefore), 'Server log did not rotate during the workload')
        evidence.serverErrors = after.slice(logBefore.length).split('\n').filter(line => /\bERROR\b|Exception:|Error:/.test(line))
        context.expect(evidence.serverErrors.length === 0, 'No server errors during the workload', evidence.serverErrors)
      })
    } finally {
      cancelled = true
      for (const actor of actors) actor.bot.clearControlStates()
      p.bot.pathfinder?.setGoal(null)
      p.bot.clearControlStates()
      await workflow.cleanup()
    }
  }
}
