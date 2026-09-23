import { readFile, writeFile, rm } from 'node:fs/promises'
import path from 'node:path'
import { portalWorkflow } from './support/portal-workflow.mjs'
import { storedPortals } from './support/portal-storage.mjs'

export default {
  name: 'wormholes-restart-persistence',
  description: 'Prepare linked public portals, then verify their identity, links and traversal after an actual JVM restart.',
  async run(context) {
    const phase = process.env.WORMHOLES_RESTART_PHASE ?? 'verify'
    context.report.restartPersistence = { requestedPhase: phase }
    context.expect(['prepare', 'verify'].includes(phase), 'WORMHOLES_RESTART_PHASE must be prepare or verify')
    const statePath = path.join(context.server.directory, '.wormholes-gameplay-restart.json')
    const directory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const { p, created, build, link, wormhole, open, cleanup } = portalWorkflow(context)
    if (phase === 'prepare') {
      let saved = false
      try {
        const prefix = `qa_restart_${Date.now().toString(36)}`
        const names = [`${prefix}_a`, `${prefix}_b`]
        await context.step('construct and persist linked Wormholes through player menus', async () => {
          await p.arena()
          await context.command('/give @s minecraft:glass 64', /Gave|Given/i)
          await context.command('/wh wand rune=false', /wand/i)
          await build(0, names[0])
          await build(24, names[1])
          await link(0, names[1])
          await link(24, names[0])
          await wormhole(0)
          await wormhole(24)
        })
        const snapshot = await context.observe()
        const ids = (await storedPortals(directory)).map(portal => portal.id).sort()
        context.expect(ids.length === 2, 'Restart fixture requires exactly two persisted portals')
        const state = { names, ids, processId: snapshot.processId, username: p.bot.username }
        await writeFile(statePath, JSON.stringify(state), { flag: 'wx' })
        saved = true
        context.report.restartPersistence = { requestedPhase: phase, phase: 'prepared', ...state }
      } finally {
        p.bot.clearControlStates()
        if (!saved) await cleanup()
      }
      return
    }
    const state = JSON.parse(await readFile(statePath, 'utf8'))
    const snapshot = await context.observe()
    context.expect(snapshot.processId !== state.processId, 'Verification requires an actual server JVM restart',
      { preparedProcessId: state.processId, currentProcessId: snapshot.processId })
    context.expect(p.bot.username === state.username, 'Use the same portal-owner username after restart')
    created.push(0, 24)
    try {
      await context.step('same portal identities and destinations survive restart', async () => {
        const ids = (await storedPortals(directory)).map(portal => portal.id).sort()
        context.expect(JSON.stringify(ids) === JSON.stringify(state.ids), 'Persisted portal identities changed')
        await context.command('/wh wand rune=false', /wand/i)
        for (const [x, destination] of [[0, state.names[1]], [24, state.names[0]]]) {
          await open(x)
          context.expect(p.items().some(item => item.name === 'Destination' && item.lore.some(line => line.includes(destination))),
            'Portal destination link survives restart', p.items())
          context.expect(p.items().some(item => item.lore.includes('Type: Wormhole')), 'Portal type survives restart')
          p.bot.closeWindow(p.bot.currentWindow)
        }
      })
      await context.step('both persisted links physically traverse after restart', async () => {
        const configuration = await readFile(path.join(context.server.directory, 'plugins/Wormholes/wormholes.toml'), 'utf8')
        const cooldownMs = Number(configuration.match(/^teleport-cooldown-millis\s*=\s*(\d+)/m)?.[1])
        context.expect(Number.isFinite(cooldownMs) && cooldownMs <= 60000, 'Expected bounded configured teleport cooldown')
        for (const [source, destination] of [[0, 24], [24, 0]]) {
          await p.stage(source + 0.5, 200, 3.5)
          await p.walk(0, () => Math.abs(p.bot.entity.position.x - destination - 0.5) < 2, 'persisted link arrival')
          await context.sleep(cooldownMs + 100)
        }
      })
      context.report.restartPersistence = { requestedPhase: phase, phase: 'verified', ids: state.ids, previousProcessId: state.processId,
        processId: snapshot.processId, completedCrossings: 2 }
    } finally {
      await cleanup()
      await context.waitUntil(async () => {
        const remaining = new Set((await storedPortals(directory)).map(portal => portal.id))
        return state.ids.every(id => !remaining.has(id))
      }, { timeoutMs: 10000, label: 'destroyed persisted portal records are removed' })
      await rm(statePath)
    }
  }
}
