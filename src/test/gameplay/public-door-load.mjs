import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { doorWorkflow } from './support/door-workflow.mjs'

export default {
  name: 'wormholes-public-door-load',
  description: 'Ordinary players repeatedly use paired and pocket doors, checking shared Public and separate Personal destinations.',
  async run(context) {
    const owner = doorWorkflow(context)
    const { p } = owner
    const actors = []
    const evidence = { actors: [], operations: [], thinkTimeMs: 2000 }
    context.report.doorLoad = evidence
    const logPath = path.join(context.server.directory, 'logs/latest.log')
    const logBefore = await readFile(logPath, 'utf8')
    let cancelled = false

    async function visit(actor, type, x) {
      context.expect(!cancelled, 'Door workload is still active')
      const doors = doorWorkflow(actor, { controller: context })
      const started = performance.now()
      const entry = await doors.dimensionCrossing(p.point(x, 200, 0), true)
      const returned = await doors.returnFromPocket()
      context.expect(Math.abs(actor.bot.entity.position.x - x - 0.5) < 4, 'Ordinary player returns to the source door', returned)
      const operation = { actor: actor.bot.username, type, entry, returned,
        latencyMs: Math.round(performance.now() - started) }
      evidence.operations.push(operation)
      await actor.sleep(evidence.thinkTimeMs)
      return operation
    }

    async function paired(actor) {
      const doors = doorWorkflow(actor, { controller: context })
      for (let crossing = 0; crossing < 10; crossing++) {
        context.expect(!cancelled, 'Door workload is still active')
        const source = crossing % 2 === 0 ? 0 : 24
        const destination = source === 0 ? 24 : 0
        await doors.p.stage(source + 0.5, 200, 3.5)
        await doors.openDoor(p.point(source, 200, 0))
        const started = performance.now()
        await doors.p.walk(0, () => Math.abs(actor.bot.entity.position.x - destination - 0.5) < 2,
          'ordinary player reaches paired destination')
        await doors.p.serverBlock(p.point(source, 200, 0), 'minecraft:oak_door[open=false]')
        evidence.operations.push({ actor: actor.bot.username, type: 'pair', source, destination,
          latencyMs: Math.round(performance.now() - started), position: { ...actor.bot.entity.position } })
        await actor.sleep(evidence.thinkTimeMs)
      }
    }

    try {
      await context.step('create the paired, Personal, and Public doors through player interactions', async () => {
        await p.arena()
        await context.command('/wh door type=pair', /door/i)
        await p.equip(/^Entangled Door Pair$/)
        p.bot.activateItem()
        await p.wait(() => p.bot.inventory.items().some(item => p.itemName(item) === 'Wormhole Door B'), 'pair kit unpacked')
        await owner.placeDoor(0, /^Wormhole Door A$/, 'oak_door')
        await owner.placeDoor(24, /^Wormhole Door B$/, 'oak_door')
        await context.command('/wh door type=personal', /door/i)
        await owner.placeDoor(8, /^Personal Dimension Door$/, 'dark_oak_door')
        await context.command('/wh door type=public', /door/i)
        await owner.placeDoor(16, /^Public Dimension Door$/, 'pale_oak_door')
      })
      await context.step('join three ordinary travelers', async () => {
        for (let index = 0; index < 3; index++) {
          const actor = await context.connectActor(`WhDoor${Date.now().toString(36)}${index}`)
          actors.push(actor)
          await actor.command('/wh door type=pair', /permission/i)
          evidence.actors.push(actor.bot.username)
        }
      })
      await context.step('run repeated ordinary-player door travel concurrently', async () => {
        const started = performance.now()
        const results = await Promise.allSettled([
          paired(actors[0]),
          (async () => { for (let round = 0; round < 3; round++) await visit(actors[1], 'personal', 8) })(),
          (async () => { for (let round = 0; round < 3; round++) await visit(actors[2], 'public', 16) })()
        ].map(promise => promise.catch(error => { cancelled = true; throw error })))
        evidence.durationMs = Math.round(performance.now() - started)
        const failures = results.filter(result => result.status === 'rejected').map(result => result.reason)
        evidence.failures = failures.map(error => ({ message: error.message, stack: error.stack }))
        if (failures.length) throw new AggregateError(failures, 'Ordinary-player door workload failed')
        context.expect(evidence.operations.filter(operation => operation.type === 'pair').length === 10,
          'All ten paired crossings completed')
        context.expect(evidence.operations.filter(operation => operation.type === 'personal').length === 3
          && evidence.operations.filter(operation => operation.type === 'public').length === 3,
        'Both pocket travelers completed three round trips')
      })
      await context.step('verify Public sharing and Personal separation between ordinary players', async () => {
        const shared = await visit(actors[1], 'public', 16)
        const privateVisit = await visit(actors[2], 'personal', 8)
        const firstPublic = evidence.operations.find(operation => operation.type === 'public')
        const firstPrivate = evidence.operations.find(operation => operation.type === 'personal')
        const distance = (a, b) => p.point(a.entry.position.x, a.entry.position.y, a.entry.position.z)
          .distanceTo(p.point(b.entry.position.x, b.entry.position.y, b.entry.position.z))
        context.expect(distance(shared, firstPublic) < 4, 'Two ordinary players share the same Public pocket', { shared, firstPublic })
        context.expect(distance(privateVisit, firstPrivate) > 8, 'Two ordinary players receive distinct Personal pockets', { privateVisit, firstPrivate })
        for (const type of ['personal', 'public']) {
          const visits = evidence.operations.filter(operation => operation.type === type).slice(0, 3)
          context.expect(visits.every(operation => distance(operation, visits[0]) < 4), 'Repeated travel keeps the same pocket identity', visits)
        }
      })
      await context.step('require a clean server log for the door workload', async () => {
        const after = await readFile(logPath, 'utf8')
        context.expect(after.startsWith(logBefore), 'Server log did not rotate during the workload')
        evidence.serverErrors = after.slice(logBefore.length).split('\n').filter(line => /\bERROR\b|Exception:|Error:/.test(line))
        context.expect(evidence.serverErrors.length === 0, 'No server errors during ordinary-player door load', evidence.serverErrors)
        evidence.completedTraversals = evidence.operations.reduce((count, operation) => count + (operation.type === 'pair' ? 1 : 2), 0)
      })
    } finally {
      cancelled = true
      for (const actor of actors) actor.bot.clearControlStates()
      const failures = []
      for (const door of owner.placed.reverse()) {
        try {
          await p.stage(door.x + 0.5, 200, 3.5)
          await p.emptyHand()
          await p.bounded(() => p.bot.dig(p.bot.blockAt(p.point(door.x, 200, 0))), 'remove load-test door')
          await p.serverBlock(p.point(door.x, 200, 0), 'minecraft:air')
        } catch (error) { failures.push(error) }
      }
      if (failures.length) throw new AggregateError(failures, 'Door load cleanup failed')
    }
  }
}
