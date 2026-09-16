import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'projected-item-visibility',
  description: 'Verify each portal observer receives only item carriers and replacement displays visible to that observer.',
  async run(context) {
    const mineflayer = createRequire(process.argv[1])('mineflayer')
    const instancePath = process.env.WORMHOLES_DROPS_INSTANCE_PATH
    context.expect(instancePath && path.basename(instancePath) === context.server.instance,
      'WORMHOLES_DROPS_INSTANCE_PATH must name the current isolated instance')
    const glossFile = path.join(instancePath, 'plugins/Gloss/gloss.toml')
    const documentFile = path.join(instancePath, 'plugins/Gloss/real-drops/default.json')
    const originalGloss = await readFile(glossFile, 'utf8')
    const originalDocument = await readFile(documentFile, 'utf8')
    const evidence = { phases: [], errors: [], spawns: {} }
    context.report.itemVisibility = evidence
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
    const projected = bot => Object.values(bot.entities)
      .filter(entity => ['item', 'block_display', 'item_display'].includes(entity.name)
        && Math.abs(entity.position.x - 8.5) < 3
        && entity.position.y > 190 && entity.position.y < 194
        && entity.position.z > 1 && entity.position.z < 8)
      .map(entity => ({ id: entity.id, uuid: entity.uuid, name: entity.name, position: { ...entity.position } }))
    const count = (bot, type) => projected(bot).filter(entity => entity.name === type).length
    const snapshot = name => evidence.phases.push({ name, observers: [context.bot, second]
      .map(bot => ({ username: bot.username, position: { ...bot.entity.position }, yaw: bot.entity.yaw,
        projected: projected(bot) })) })
    const command = (text, expected = /DROPS /, timeout = 10000) =>
      Promise.race([context.command(text, expected, timeout), fatal])
    const waitFor = async (predicate, message, timeout = 15000) => {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        if (predicate()) return
        await sleep(100)
      }
      evidence.failureSamples = []
      for (const bot of [context.bot, second].filter(Boolean)) {
        evidence.failureSamples.push(await command(`/whdrops sample ${bot.username}`))
      }
      evidence.nearby = [context.bot, second].filter(Boolean).map(bot => ({ username: bot.username,
        entities: Object.values(bot.entities).filter(entity => Math.abs(entity.position.x - 8.5) < 10)
          .map(entity => ({ id: entity.id, name: entity.name, position: { ...entity.position } })) }))
      snapshot(`failure: ${message}`)
      context.expect(false, message, evidence.phases.at(-1))
    }
    const stable = async (predicate, message, milliseconds = 1500) => {
      const deadline = Date.now() + milliseconds
      while (Date.now() < deadline) {
        context.expect(predicate(), message, [context.bot, second].map(bot => projected(bot)))
        await sleep(100)
      }
    }
    const enableRealDrops = async enabled => {
      const current = await readFile(glossFile, 'utf8')
      context.expect(/^realDrops\s*=\s*(true|false)/m.test(current), 'Gloss realDrops option is present')
      await writeFile(glossFile, current.replace(/^realDrops\s*=\s*(true|false)/m, `realDrops = ${enabled}`))
    }
    const audience = async viewerName => {
      const document = JSON.parse(await readFile(documentFile, 'utf8'))
      document.audience.when = `viewer.name == '${viewerName}'`
      document.presentation.limits.viewRange = 128
      document.revision++
      await writeFile(documentFile, JSON.stringify(document, null, 2))
    }
    const visibility = (bot, visible) => command(`/whdrops visibility ${bot.username} ${visible ? 'show' : 'hide'}`)
    const watchSpawns = (bot, username) => {
      evidence.spawns[username] = []
      bot._client.on('spawn_entity', packet => {
        if (Math.abs(packet.x - 8.5) < 5 && packet.y > 190 && packet.y < 194 && packet.z < 8) {
          evidence.spawns[username].push({ entityId: packet.entityId, type: packet.type,
            x: packet.x, y: packet.y, z: packet.z })
        }
      })
    }

    async function event(bot, name, predicate, timeout = 15000) {
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

    async function secondCommand(text, expected) {
      const response = event(second, 'messagestr', message => expected.test(message))
      second.chat(text)
      return (await response)[0]
    }

    async function connectSecond() {
      second = mineflayer.createBot({ host: context.server.host, port: context.server.port,
        username: 'DropsOther', auth: 'offline', version: context.server.minecraftVersion,
        hideErrors: true, logErrors: false })
      second.on('error', error => fail(`Second observer error: ${error.stack ?? error}`))
      second.on('kicked', reason => fail(`Second observer kicked: ${JSON.stringify(reason)}`))
      second.on('end', reason => { if (!closing) fail(`Second observer disconnected: ${reason}`) })
      watchSpawns(second, 'DropsOther')
      await event(second, 'spawn', () => true, 30000)
    }

    try {
      await context.step('prepare two observers and one ordinary pickup item', async () => {
        watchSpawns(context.bot, context.bot.username)
        if (context.bot.game.gameMode !== 'creative') {
          await command('/gamemode creative @s', /creative/i)
        }
        await command('/whdrops setup', /DROPS ready open=true linked=true/, 45000)
        await command('/whdrops stage', /DROPS staged=true/)
        await waitFor(() => Math.abs(context.bot.entity.position.x - 8.5) < 0.1
          && Math.abs(context.bot.entity.position.y - 191) < 0.1
          && Math.abs(context.bot.entity.position.z - 13.5) < 0.1, 'Primary observer staging failed')
        await context.bot.look(0, 0, true)
        await connectSecond()
        await command('/op DropsOther', /operator|already/i)
        if (second.game.gameMode !== 'creative') {
          await secondCommand('/gamemode creative @s', /creative/i)
        }
        closing = true
        const ended = event(second, 'end', () => true, 5000)
        second.quit()
        await ended
        closing = false
        await connectSecond()
        await secondCommand('/whdrops stage', /DROPS staged=true/)
        await waitFor(() => Math.abs(second.entity.position.x - 8.5) < 0.1
          && Math.abs(second.entity.position.y - 191) < 0.1
          && Math.abs(second.entity.position.z - 13.5) < 0.1, 'Second observer staging failed')
        await second.look(0, 0, true)
        await enableRealDrops(false)
        await waitFor(() => [context.bot, second].every(bot => count(bot, 'item') === 1
          && projected(bot).length === 1), 'Both observers did not receive the ordinary item', 30000)
        evidence.source = await command('/whdrops sample')
        snapshot('ordinary item')
      })
      await context.step('an independent plugin hide affects only its chosen observer', async () => {
        await visibility(context.bot, false)
        await waitFor(() => projected(context.bot).length === 0 && count(second, 'item') === 1,
          'The independently hidden item remained projected or disappeared for the other observer')
        await stable(() => projected(context.bot).length === 0 && count(second, 'item') === 1,
          'A later projection update restored the hidden item')
        snapshot('hidden for primary')
        await visibility(context.bot, true)
        await waitFor(() => [context.bot, second].every(bot => count(bot, 'item') === 1),
          'Showing the item did not restore its projection')
        snapshot('shown for both')
      })
      await context.step('default-hidden items project only to explicitly shown observers', async () => {
        await command('/whdrops default false', /DROPS default=false/)
        await visibility(context.bot, false)
        await visibility(second, false)
        await waitFor(() => [context.bot, second].every(bot => projected(bot).length === 0),
          'A default-hidden item remained projected')
        await visibility(context.bot, true)
        await waitFor(() => count(context.bot, 'item') === 1 && projected(second).length === 0,
          'An explicitly shown default-hidden item did not project only to its chosen observer')
        await stable(() => count(context.bot, 'item') === 1 && projected(second).length === 0,
          'Default visibility overrode the explicit viewer selection')
        snapshot('default-hidden, shown for primary')
        await command('/whdrops default true', /DROPS default=true/)
        await visibility(context.bot, true)
        await visibility(second, true)
        await waitFor(() => [context.bot, second].every(bot => count(bot, 'item') === 1),
          'Restoring default visibility did not restore both projections')
      })
      await context.step('conditional Gloss audiences select the display or carrier for each observer', async () => {
        await audience(context.bot.username)
        await enableRealDrops(true)
        await waitFor(() => count(context.bot, 'block_display') === 1 && projected(context.bot).length === 1
          && count(second, 'item') === 1 && projected(second).length === 1,
        'Gloss audience selection did not choose the matching display and fallback carrier', 30000)
        evidence.primaryAudience = await command(`/whdrops sample ${context.bot.username}`)
        evidence.secondAudience = await command(`/whdrops sample ${second.username}`)
        context.expect(evidence.primaryAudience.includes('canSee=false')
          && evidence.secondAudience.includes('canSee=true'),
        'Protocol projections did not match native per-viewer carrier visibility', evidence)
        snapshot('Gloss for primary')
        await audience(second.username)
        await waitFor(() => count(second, 'block_display') === 1 && projected(second).length === 1
          && count(context.bot, 'item') === 1 && projected(context.bot).length === 1,
        'Changing the Gloss audience did not reverse the observer presentations', 30000)
        await stable(() => count(second, 'block_display') === 1 && projected(second).length === 1
          && count(context.bot, 'item') === 1 && projected(context.bot).length === 1,
        'A later frame duplicated a conditional Gloss carrier')
        snapshot('Gloss for second')
      })
      await context.step('local portal occlusion cannot release another plugin hide', async () => {
        await enableRealDrops(false)
        await waitFor(() => [context.bot, second].every(bot => count(bot, 'item') === 1
          && projected(bot).length === 1), 'Ordinary item mode did not restore before local occlusion')
        const local = await command('/whdrops local create', /DROPS local=/)
        const localId = Number(/entityId=(\d+)/.exec(local)?.[1])
        context.expect(Number.isInteger(localId), 'Local pickup entity ID is missing', local)
        let occlusion
        const deadline = Date.now() + 15000
        while (Date.now() < deadline) {
          occlusion = await command('/whdrops local sample', /DROPS local=/)
          if (occlusion.includes('occluded=true')) break
          await sleep(200)
        }
        context.expect(occlusion.includes('occluded=true'), 'The local pickup did not enter portal occlusion', occlusion)
        await command('/whdrops local hide')
        await command('/whdrops projection false', /DROPS projection=OFF/)
        await waitFor(() => !context.bot.entities[localId] && second.entities[localId],
          'Releasing portal occlusion exposed the independently hidden local item')
        await stable(() => !context.bot.entities[localId] && second.entities[localId],
          'Occlusion release later restored the independently hidden local item')
        evidence.localOcclusion = await command('/whdrops local sample', /DROPS local=/)
        context.expect(evidence.localOcclusion.includes('canSee=false')
          && evidence.localOcclusion.includes('occluded=false'),
        'The remaining local hide was not independent of portal occlusion', evidence.localOcclusion)
        await command('/whdrops local show')
        await waitFor(() => context.bot.entities[localId], 'Releasing the independent hide did not restore the local item')
      })
      await context.step('releasing local occlusion cannot reveal a default-hidden pickup', async () => {
        await command('/whdrops local default false', /DROPS localDefault=false/)
        await command('/whdrops projection true', /DROPS projection=ON/)
        let localState
        const deadline = Date.now() + 15000
        while (Date.now() < deadline) {
          localState = await command('/whdrops local sample', /DROPS local=/)
          if (localState.includes('occluded=true')) break
          await sleep(200)
        }
        context.expect(localState.includes('occluded=true'), 'Default-hidden local pickup did not enter portal occlusion', localState)
        const localId = Number(/entityId=(\d+)/.exec(localState)?.[1])
        await command('/whdrops projection false', /DROPS projection=OFF/)
        await waitFor(() => [context.bot, second].every(bot => !bot.entities[localId]),
          'Portal occlusion release exposed the default-hidden local pickup')
        await stable(() => [context.bot, second].every(bot => !bot.entities[localId]),
          'A later update exposed the default-hidden local pickup')
        evidence.defaultHiddenLocal = await command('/whdrops local sample', /DROPS local=/)
        context.expect(evidence.defaultHiddenLocal.includes('canSee=false')
          && evidence.defaultHiddenLocal.includes('occluded=false'),
        'Releasing portal occlusion left a visibility grant for the default-hidden pickup', evidence.defaultHiddenLocal)
        await command('/whdrops local default true', /DROPS localDefault=true/)
        await waitFor(() => [context.bot, second].every(bot => bot.entities[localId]),
          'Restoring the local pickup default visibility did not restore both observers')
      })
      context.expect(evidence.errors.length === 0, 'An observer had a protocol error', evidence.errors)
    } finally {
      closing = true
      try {
        await writeFile(glossFile, originalGloss)
        await writeFile(documentFile, originalDocument)
      } finally {
        if (second && !second._client.ended) {
          let timer
          let onEnd
          try {
            second.clearControlStates()
            const ended = new Promise(resolve => { onEnd = resolve; second.once('end', onEnd) })
            second.quit()
            await Promise.race([ended, new Promise((resolve, reject) => {
              timer = setTimeout(() => reject(new Error('Second observer cleanup timed out')), 5000)
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
}
