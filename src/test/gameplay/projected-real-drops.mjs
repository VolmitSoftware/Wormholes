import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'projected-real-drops',
  description: 'Verify portal projections replace the hidden pickup item with Gloss real-drop displays.',
  async run(context) {
    const instancePath = process.env.WORMHOLES_DROPS_INSTANCE_PATH
    context.expect(instancePath && path.basename(instancePath) === context.server.instance,
      'WORMHOLES_DROPS_INSTANCE_PATH must name the current isolated instance')
    const glossFile = path.join(instancePath, 'plugins/Gloss/gloss.toml')
    const originalGloss = await readFile(glossFile, 'utf8')
    const evidence = { phases: [] }
    context.report.projectedDrops = evidence
    const projected = () => Object.values(context.bot.entities)
      .filter(entity => ['item', 'block_display', 'item_display'].includes(entity.name)
        && Math.abs(entity.position.x - 8.5) < 3
        && entity.position.y > 190 && entity.position.y < 194
        && entity.position.z > 1 && entity.position.z < 8)
      .map(entity => ({ id: entity.id, uuid: entity.uuid, name: entity.name, position: { ...entity.position } }))
    const waitFor = async (predicate, message, timeout = 15000) => {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        if (predicate()) return
        await context.sleep(100)
      }
      evidence.failureSample = await context.command('/whdrops sample', /DROPS item=/, 10000)
      context.expect(false, message, { projected: projected(), nearby: Object.values(context.bot.entities)
        .filter(entity => Math.abs(entity.position.x - 8.5) < 15)
        .map(entity => ({ id: entity.id, name: entity.name, position: entity.position })) })
    }
    const enableRealDrops = async enabled => {
      const current = await readFile(glossFile, 'utf8')
      context.expect(/^realDrops\s*=\s*(true|false)/m.test(current), 'Gloss realDrops option is present')
      await writeFile(glossFile, current.replace(/^realDrops\s*=\s*(true|false)/m, `realDrops = ${enabled}`))
    }
    const snapshot = name => evidence.phases.push({ name, projected: projected() })
    try {
      await context.step('create paired portals and stage the observer', async () => {
        if (context.bot.game.gameMode !== 'creative') {
          await context.command('/gamemode creative @s', /creative/i, 10000)
        }
        await context.command('/whdrops setup', /DROPS ready open=true linked=true/, 45000)
        await context.command('/whdrops stage', /DROPS staged=true/, 15000)
        await waitFor(() => Math.abs(context.bot.entity.position.x - 8.5) < 0.1
          && Math.abs(context.bot.entity.position.z - 13.5) < 0.1, 'Observer staging failed')
        await context.bot.look(0, 0, true)
        evidence.sourceItem = await context.command('/whdrops sample', /DROPS item=.*valid=true/, 10000)
        await enableRealDrops(true)
      })
      await context.step('diorite projects exactly one real-drop display and no vanilla pickup item', async () => {
        await waitFor(() => projected().some(entity => entity.name === 'block_display'),
          'The projected diorite block display is missing', 45000)
        await context.sleep(3000)
        snapshot('diorite enabled')
        context.expect(projected().filter(entity => entity.name === 'block_display').length === 1,
          'One diorite stack should project one block display', projected())
        context.expect(projected().every(entity => entity.name !== 'item'),
          'The hidden vanilla pickup item was projected alongside the diorite real drop', projected())
      })
      await context.step('disabling real drops restores the projected vanilla item', async () => {
        await enableRealDrops(false)
        await waitFor(() => projected().filter(entity => entity.name === 'item').length === 1
          && projected().every(entity => entity.name !== 'block_display' && entity.name !== 'item_display'),
        'Disabling real drops did not restore one vanilla item', 20000)
        snapshot('diorite disabled')
      })
      await context.step('reenabling real drops replaces the existing projected item', async () => {
        await enableRealDrops(true)
        await waitFor(() => projected().filter(entity => entity.name === 'block_display').length === 1
          && projected().every(entity => entity.name !== 'item'),
        'Reenabling real drops retained the vanilla projection', 20000)
        snapshot('diorite reenabled')
      })
      await context.step('flat items project one item display without the pickup carrier', async () => {
        await context.command('/whdrops material STICK', /DROPS material=STICK/, 10000)
        await waitFor(() => projected().filter(entity => entity.name === 'item_display').length === 1
          && projected().every(entity => entity.name !== 'item' && entity.name !== 'block_display'),
        'Flat item projection did not replace the pickup carrier', 20000)
        snapshot('stick enabled')
      })
      await context.step('the source pickup item remains valid', async () => {
        evidence.sourceAfter = await context.command('/whdrops sample', /DROPS item=.*valid=true.*stack=STICK/, 10000)
        context.expect(/DROPS item=([^ ]+)/.exec(evidence.sourceItem)?.[1]
          === /DROPS item=([^ ]+)/.exec(evidence.sourceAfter)?.[1],
        'The original pickup item changed identity during projection updates', evidence)
        context.expect(evidence.sourceAfter.includes('carrierDefault=false'),
          'The replacement display did not retain the hidden pickup carrier', evidence.sourceAfter)
        await context.sleep(2000)
        context.expect(context.bot.entity != null, 'Observer disconnected after assertions')
      })
    } finally {
      await writeFile(glossFile, originalGloss)
    }
  }
}
