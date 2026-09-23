import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { portalWorkflow } from './support/portal-workflow.mjs'
import { storedPortals } from './support/portal-storage.mjs'

export default {
  name: 'wormholes-persistent-workflow',
  description: 'Run a managed ordinary session actor through declared Overworld/Nether route transitions and command steps.',
  async run(context) {
    const source = path.dirname(process.argv[1])
    const { createSessionTransport, loadSessionRuntime } = await import(pathToFileURL(path.join(source, 'sessions/transport.mjs')))
    const { executePluginActivity, validatePluginActivities } = await import(pathToFileURL(path.join(source, 'sessions/plugin_activities.mjs')))
    const logPath = path.join(context.server.directory, 'logs/latest.log')
    const logBefore = await readFile(logPath, 'utf8')
    const observerPath = path.join(context.server.directory, 'plugins/MultiplexorObserver/metrics.json')
    const snapshot = await context.observe()
    const home = snapshot.worlds.find(world => world.dimension === 'NORMAL')
    const away = snapshot.worlds.find(world => world.dimension === 'NETHER')
    context.expect(home && away, 'Observer must expose Overworld and Nether identities')
    const portals = [portalWorkflow(context), portalWorkflow(context)]
    const prefix = `qa_session_${Date.now().toString(36)}`
    const names = [`${prefix}_a`, `${prefix}_b`]
    const evidence = { events: [], arrivals: [], respawns: 0 }
    context.report.persistentWorkflow = evidence
    let transport
    const changeWorld = async world => {
      if (context.bot.currentWindow) context.bot.closeWindow(context.bot.currentWindow)
      await context.transition(() => context.bot.chat(
        `/execute in minecraft:${world.dimension === 'NORMAL' ? 'overworld' : 'the_nether'} run tp @s 0.5 202 3.5`),
      { worldId: world.id, timeoutMs: 30000 })
      await context.command('/clear @s', /Removed|No items/i)
      await context.command('/wh wand rune=false', /wand/i)
    }
    try {
      await context.step('construct and link public portals across two worlds', async () => {
        for (const [index, world] of [home, away].entries()) {
          await changeWorld(world)
          const workflow = portals[index]
          await workflow.p.arena()
          await context.command('/fill -2 199 -48 2 199 48 minecraft:stone', /filled|changed|blocks/i)
          await context.command('/fill -2 200 -48 2 204 48 minecraft:air', /filled|changed|blocks/i)
          for (const x of [-1, 1]) await context.command(`/fill ${x} 200 -48 ${x} 202 48 minecraft:stone`, /filled|changed|blocks/i)
          await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
          await context.command('/wh wand rune=false', /wand/i)
          await workflow.build(0, names[index], { removeSelectionBlocks: false })
          await context.command('/fill 0 200 0 0 202 0 minecraft:air', /filled|changed|blocks/i)
          for (const y of [200, 201, 202]) await workflow.p.serverBlock(workflow.p.point(0, y, 0), 'minecraft:air')
          await workflow.wormhole(0)
        }
        await portals[1].link(0, names[0])
        await changeWorld(home)
        await portals[0].link(0, names[1])
      })
      await context.step('ordinary persistent actor completes declared roundtrip workflow', async () => {
        const runtime = await loadSessionRuntime()
        transport = createSessionTransport({ runtime, timeoutMs: 90000,
          target: { kind: 'instance', host: context.server.host, port: context.server.port,
            version: context.bot.version, backends: [{ alias: 'portal-lab', observerPath }] },
          record: event => evidence.events.push(event) })
        const owned = await transport.connect({ id: 'traveler', username: `WhSess${Date.now().toString(36)}` }, context.signal)
        const bot = owned.bot
        const respawn = () => { evidence.respawns++ }
        bot.on('respawn', respawn)
        try {
          await context.command(`/gamemode creative ${bot.username}`, /creative/i)
          await context.command(`/tp ${bot.username} 0.5 200 3.5`, /Teleported/)
          await context.waitUntil(async () => {
            const observed = (await context.observe()).players.find(player => player.username === bot.username)
            return observed?.world === home.id && bot.blockAt(bot.entity.position.floored())
          }, { timeoutMs: 15000, label: 'ordinary actor observed in starting world' })
          const [activity] = validatePluginActivities([{ id: 'portal-roundtrip', backend: 'portal-lab', timeoutSeconds: 90,
            steps: [
              { route: [{ x: 0.5, y: 200, z: -40 }], transition: { worldName: away.name, position: { x: 0.5, y: 200, z: 0.5 }, radius: 32 } },
              { command: '/wh wand rune=false', expect: 'You do not have permission' },
              { route: [{ x: 0.5, y: 200, z: 40 }], transition: { worldName: home.name, position: { x: 0.5, y: 200, z: 0.5 }, radius: 32 } },
              { command: '/wh door type=pair', expect: 'You do not have permission' }
            ] }], { aliases: ['portal-lab'] })
          evidence.activity = activity
          const result = await executePluginActivity(bot, activity, {
            signal: AbortSignal.any([context.signal, owned.signal]), observerPath,
            assertCurrent: () => { context.signal.throwIfAborted(); owned.signal.throwIfAborted(); context.expect(!owned.ended, 'Session actor disconnected') },
            worldTransition: operation => transport.worldTransition(owned, operation),
            failTransition: error => owned.abort.abort(error),
            record: event => {
              evidence.events.push(event)
              if (event.type === 'plugin-step' && event.result?.world) {
                const arrival = { ...event.result, pathfinderIdle: bot.pathfinder.goal === null && !bot.pathfinder.isMoving(),
                  controlsReleased: Object.values(bot.controlState).every(value => value === false) }
                evidence.arrivals.push(arrival)
                context.expect(arrival.pathfinderIdle && arrival.controlsReleased, 'Old route must stop at the declared arrival', arrival)
              }
            }
          })
          evidence.result = result
          context.expect(result.status === 'completed' && result.metrics.pluginSteps === 4, 'All workflow steps must complete')
          context.expect(evidence.arrivals.length === 2 && evidence.respawns === 2, 'Roundtrip requires two real world arrivals and respawns', evidence)
          context.expect(!owned.signal.aborted && !owned.switching, 'Declared respawns must not abort managed transport')
          const position = bot.entity.position.clone()
          await context.sleep(500)
          evidence.idleAfterArrival = { pathfinderIdle: bot.pathfinder.goal === null && !bot.pathfinder.isMoving(),
            controlsReleased: Object.values(bot.controlState).every(value => value === false),
            drift: bot.entity.position.distanceTo(position), position: { ...bot.entity.position } }
          context.expect(evidence.idleAfterArrival.pathfinderIdle && evidence.idleAfterArrival.controlsReleased,
            'Persistent actor must not resume pathfinding after final arrival', evidence.idleAfterArrival)
        } finally { bot.removeListener('respawn', respawn) }
      })
      await context.step('persistent workflow has no server errors', async () => {
        const after = await readFile(logPath, 'utf8')
        context.expect(after.startsWith(logBefore), 'Runtime log rotated during workflow')
        const errors = after.slice(logBefore.length).split('\n').filter(line => /\bERROR\b|Exception:/.test(line))
        context.expect(errors.length === 0, 'Persistent workflow server errors', { errors })
      })
    } finally {
      if (transport) evidence.cleanup = await transport.close()
      evidence.cleanupMenuErrors = []
      for (const [index, world] of [home, away].entries()) {
        if (!portals[index].created.length) continue
        const remaining = await storedPortals(path.join(context.server.directory, 'plugins/Wormholes/portals'))
        if (!remaining.some(portal => portal.data.name === names[index])) continue
        try {
          await changeWorld(world)
          await portals[index].cleanup()
        } catch (error) { evidence.cleanupMenuErrors.push({ world: world.name, message: error.message }) }
      }
      await context.waitUntil(async () => (await storedPortals(path.join(context.server.directory, 'plugins/Wormholes/portals'))).length === 0,
        { timeoutMs: 10000, label: 'all workflow portal records removed' })
      evidence.cleanupPortals = 0
    }
  }
}
