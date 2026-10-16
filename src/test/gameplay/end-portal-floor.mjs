import { open, readFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { publicPlayer } from './support/public-player.mjs'
import { storedPortals } from './support/portal-storage.mjs'

const FRAME_TOP = 0.8125
const ENTER_TIMEOUT_MS = 10000
const RETURN_TIMEOUT_MS = 15000
const SITES = [
  { phase: 'floor-drop', label: 'floor', x: 2, y: 200, z: 2, pit: 0, inside: false },
  { phase: 'elevated-enter', label: 'elevated', x: 22, y: 200, z: 2, pit: 3, inside: false },
  { phase: 'floor-enter', label: 'floor-level', x: 12, y: 200, z: 2, pit: 0, inside: false, teleportIn: true },
  { phase: 'floor-convert', label: 'converted-underfoot', x: 32, y: 200, z: 2, pit: 0, inside: true }
]

export default {
  name: 'wormholes-end-portal-floor',
  description: 'Complete End portals on a floor and above a pit, enter them from the frame, from floor level inside the ring and while standing in them as they convert, and leave the End through the exit fountain.',
  async run(context) {
    const p = publicPlayer(context)
    const bot = p.bot
    const portalDirectory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const evidence = { sites: [], walks: [], fountain: null, winGame: [], dimensions: [], failures: [], skipped: [], log: [] }
    context.report.endPortalFloor = evidence
    const failures = []
    const logPath = context.server.logPath
    const logOffset = logPath ? (await stat(logPath)).size : 0
    let returned = false

    const position = () => ({ x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z })
    const cells = portal => portal?.data?.structure?.blocks ?? []
    const nearWindow = target => bot.game.dimension === 'the_end'
      && Math.abs(bot.entity.position.x - target.x) <= 2.5 && Math.abs(bot.entity.position.z - target.z) <= 2.5
    const centre = list => ({
      x: list.reduce((sum, cell) => sum + cell.x, 0) / list.length + 0.5,
      y: list[0].y + 0.5,
      z: list.reduce((sum, cell) => sum + cell.z, 0) / list.length + 0.5
    })

    bot.on('game', () => {
      const last = evidence.dimensions.at(-1)
      if (last?.dimension === bot.game.dimension) return
      evidence.dimensions.push({ at: new Date().toISOString(), dimension: bot.game.dimension, from: position() })
    })

    bot._client.on('game_state_change', packet => {
      if (packet.reason !== 4 && packet.reason !== 'win_game') return
      evidence.winGame.push({ value: packet.gameMode, dimension: bot.game.dimension, position: position() })
      bot._client.write('client_command', { actionId: 0 })
    })

    async function phase(name, label, action) {
      try {
        await context.step(`${name}: ${label}`, action)
      } catch (error) {
        if (context.signal.aborted) throw error
        failures.push(error.message.startsWith(`${name}:`) ? error.message : `${name}: ${error.message}`)
        evidence.failures.push({ phase: name, message: error.message, details: error.details })
      }
    }

    async function serverBlockIn(dimension, x, y, z, block, timeoutMs = 10000) {
      const marker = `QA_END_${dimension}_${x}_${y}_${z}`
      const deadline = Date.now() + timeoutMs
      for (;;) {
        try {
          await context.command(`/execute in minecraft:${dimension} if block ${x} ${y} ${z} ${block} run tellraw ${bot.username} ${JSON.stringify({ text: marker })}`,
            new RegExp(marker), 1000)
          return
        } catch {
          if (Date.now() >= deadline) throw new Error(`${block} not present at ${dimension} ${x},${y},${z} within ${timeoutMs} ms`)
        }
      }
    }

    async function stageIn(dimension, x, y, z, yaw) {
      bot.clearControlStates()
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      await context.command(`/execute in minecraft:${dimension} run tp @s ${x.toFixed(5)} ${y.toFixed(5)} ${z.toFixed(5)} ${yaw} 0`,
        new RegExp(`Teleported ${bot.username} `))
      await p.wait(() => bot.game.dimension === dimension && bot.entity.position.distanceTo(p.point(x, y, z)) < 0.3
        && bot.blockAt(p.point(x, y - 1, z)), `staged in ${dimension} at ${x},${y},${z}`, 20000)
      await context.sleep(400)
    }

    async function serverPosition() {
      const marker = `QA_POS_${bot.username}`
      const components = [{ text: `${marker} ` }, { nbt: 'Dimension', entity: '@s' }, { text: ' ' }, { nbt: 'Pos', entity: '@s' }]
      return context.command(`/tellraw @s ${JSON.stringify(components)}`, new RegExp(marker), 3000)
        .catch(error => `unavailable: ${error.message}`)
    }

    function clientView(list, offsets) {
      return list.flatMap(cell => offsets.map(dy => ({ x: cell.x, y: cell.y + dy, z: cell.z,
        name: bot.blockAt(p.point(cell.x, cell.y + dy, cell.z))?.name ?? null })))
    }

    async function walkUntil(walk, yaw, predicate, timeoutMs, message, stopWhen = () => false) {
      let forced = 0
      const onForced = () => { forced++ }
      bot.on('forcedMove', onForced)
      const started = Date.now()
      const sampler = setInterval(() => {
        if (!walk.stopped && stopWhen()) {
          bot.clearControlStates()
          walk.stopped = { t: Date.now() - started, ...position() }
        }
        if (walk.samples.length >= 400) return
        walk.samples.push({ t: Date.now() - started, dimension: bot.game.dimension, onGround: bot.entity.onGround,
          velocityY: bot.entity.velocity.y, ...position() })
      }, 50)
      await bot.look(yaw, 0, true)
      bot.setControlState('forward', true)
      try {
        await p.wait(predicate, message, timeoutMs)
        walk.elapsedMs = Date.now() - started
      } catch {
        walk.elapsedMs = Date.now() - started
        walk.server = await serverPosition()
        context.expect(false, message, { position: position(), dimension: bot.game.dimension, forcedMoves: forced, server: walk.server })
      } finally {
        clearInterval(sampler)
        bot.removeListener('forcedMove', onForced)
        bot.clearControlStates()
        walk.forcedMoves = forced
        walk.finalPosition = position()
        walk.finalDimension = bot.game.dimension
      }
    }

    async function waitForPortal(predicate, label, timeoutMs = 30000) {
      let found
      await p.wait(async () => {
        found = (await storedPortals(portalDirectory)).find(predicate)
        return Boolean(found)
      }, label, timeoutMs)
      return found
    }

    async function buildAndComplete(site) {
      const record = { label: site.label, centre: { x: site.x, y: site.y, z: site.z }, pit: site.pit }
      evidence.sites.push(record)
      const { x, y, z } = site
      if (bot.game.dimension !== 'overworld') await stageIn('overworld', x + 0.5, y, z + 3.5, 180)
      if (site.pit > 0) {
        await context.command(`/fill ${x - 1} ${y - site.pit} ${z - 1} ${x + 1} ${y - 1} ${z + 1} minecraft:air`, /filled|changed|blocks/i)
        await context.command(`/fill ${x - 2} ${y - site.pit - 1} ${z - 2} ${x + 2} ${y - site.pit - 1} ${z + 2} minecraft:stone`, /filled|changed|blocks/i)
      }
      await context.command(`/fill ${x - 1} ${y} ${z - 2} ${x + 1} ${y} ${z - 2} minecraft:end_portal_frame[facing=south,eye=true]`, /filled|changed|blocks/i)
      await context.command(`/fill ${x - 2} ${y} ${z - 1} ${x - 2} ${y} ${z + 1} minecraft:end_portal_frame[facing=east,eye=true]`, /filled|changed|blocks/i)
      await context.command(`/fill ${x + 2} ${y} ${z - 1} ${x + 2} ${y} ${z + 1} minecraft:end_portal_frame[facing=west,eye=true]`, /filled|changed|blocks/i)
      await context.command(`/fill ${x - 1} ${y} ${z + 2} ${x + 1} ${y} ${z + 2} minecraft:end_portal_frame[facing=north,eye=true]`, /filled|changed|blocks/i)
      await context.command(`/setblock ${x} ${y} ${z + 2} minecraft:end_portal_frame[facing=north,eye=false]`, /changed|placed|block/i)
      for (let dx = -1; dx <= 1; dx++) {
        for (let dz = -1; dz <= 1; dz++) {
          await p.serverBlock(p.point(x + dx, y, z + dz), 'minecraft:air')
          await p.serverBlock(p.point(x + dx, y - 1, z + dz), site.pit > 0 ? 'minecraft:air' : 'minecraft:stone')
        }
      }
      if (site.pit > 1) await p.serverBlock(p.point(x, y - 2, z), 'minecraft:air')
      const before = new Set((await storedPortals(portalDirectory)).map(portal => portal.id))
      if (site.inside) await stageIn('overworld', x + 0.5, y, z + 0.5, 0)
      else await stageIn('overworld', x + 0.5, y, z + 3.5, 180)
      await context.command('/give @s minecraft:ender_eye 4', /Gave|Given/i)
      await p.equip(/^Eye of Ender$/)
      await p.rightClick(p.point(x, y, z + 2))
      if (site.inside) {
        await p.wait(() => bot.game.dimension !== 'overworld' || bot.blockAt(p.point(x, y, z + 2))?.getProperties().eye === true,
          `${site.label} frame shows the twelfth eye`)
      } else {
        await serverBlockIn('overworld', x, y, z + 2, 'minecraft:end_portal_frame[eye=true]')
      }
      record.afterEye = { dimension: bot.game.dimension, position: position() }
      const inside = cell => cell.y === y && Math.abs(cell.x - x) <= 1 && Math.abs(cell.z - z) <= 1
      const source = await waitForPortal(portal => !before.has(portal.id) && portal.data.dimensionalPortalKind === 'END_SOURCE'
        && portal.data.dimensionalCounterpartId && cells(portal).length === 9 && cells(portal).every(inside),
      `${site.label} End portal converts into a linked END_SOURCE`)
      const arrival = await waitForPortal(portal => portal.id === source.data.dimensionalCounterpartId
        && portal.data.dimensionalPortalKind === 'END_ARRIVAL', `${site.label} End portal gains an END_ARRIVAL counterpart`)
      record.source = { id: source.id, structure: source.data.structure, counterpart: source.data.dimensionalCounterpartId }
      record.arrival = { id: arrival.id, structure: arrival.data.structure, centre: centre(cells(arrival)) }
      const settleDeadline = Date.now() + 3000
      while (site.inside && bot.game.dimension !== 'overworld' && !nearWindow(record.arrival.centre) && Date.now() < settleDeadline) {
        await context.sleep(50)
      }
      record.afterConversion = { dimension: bot.game.dimension, position: position(), server: await serverPosition() }
      if (site.inside && bot.game.dimension !== 'overworld') {
        if (nearWindow(record.arrival.centre)) {
          record.sentOnConversion = { dimension: bot.game.dimension, position: position() }
        } else {
          record.vanillaTravel = { dimension: bot.game.dimension, position: position(), dimensions: [...evidence.dimensions] }
        }
      }
      if (!site.inside) await verifyCleared(site)
      return record
    }

    async function verifyCleared(site) {
      const { x, y, z } = site
      for (let dx = -1; dx <= 1; dx++) {
        for (let dz = -1; dz <= 1; dz++) {
          await serverBlockIn('overworld', x + dx, y, z + dz, 'minecraft:air')
        }
      }
      await serverBlockIn('overworld', x, y - 1, z, site.pit > 0 ? 'minecraft:air' : 'minecraft:stone')
    }

    async function enter(site, record) {
      const walk = { phase: site.phase, site: site.label, samples: [] }
      evidence.walks.push(walk)
      context.expect(record?.arrival, `${site.phase}: ${site.label} End portal was never converted`)
      const { x, y, z } = site
      const target = record.arrival.centre
      walk.arrivalWindow = target
      walk.portalPlaneY = y + 0.5
      const interior = []
      for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) interior.push({ x: x + dx, y, z: z + dz })
      if (record.sentOnConversion) {
        walk.sentOnConversion = record.sentOnConversion
      } else if (site.teleportIn) {
        context.expect(bot.game.dimension === 'overworld', `${site.phase}: player left the overworld before entering the ${site.label} End portal`,
          { dimension: bot.game.dimension, position: position() })
        walk.start = position()
        bot.clearControlStates()
        const sent = Date.now()
        await context.command(`/execute in minecraft:overworld run tp @s ${(x + 0.5).toFixed(5)} ${y.toFixed(5)} ${(z + 0.5).toFixed(5)} 0 0`,
          new RegExp(`Teleported ${bot.username} `))
        walk.teleportedIn = { x: x + 0.5, y, z: z + 0.5 }
        try {
          await p.wait(() => bot.game.dimension === 'the_end', `${site.phase}: standing still at floor level inside the ${site.label} End portal leaves the overworld`,
            ENTER_TIMEOUT_MS)
        } catch {
          walk.server = await serverPosition()
          context.expect(false, `${site.phase}: standing still at floor level inside the ${site.label} End portal (portal cells y=${y}, solid floor below) never left the overworld within ${ENTER_TIMEOUT_MS} ms`,
            { position: position(), dimension: bot.game.dimension, server: walk.server })
        }
        walk.elapsedMs = Date.now() - sent
      } else {
        if (!site.inside) await stageIn('overworld', x + 0.5, y + FRAME_TOP, z + 2.5, 180)
        walk.start = position()
        walk.clientViewBefore = clientView(interior, [0, -1])
        let entered = null
        const approach = site.inside
          ? `standing in and walking across the ${site.label} End portal with feet on the floor at the portal cells (y=${y}, solid floor below)`
          : `walking off the frame onto the ${site.label} End portal (portal cells y=${y}${site.pit ? `, ${site.pit} air below` : ', solid floor below'})`
        await walkUntil(walk, 0, () => {
          if (!entered && bot.entity.position.z < z + 1.5 && bot.game.dimension === 'overworld') {
            entered = { position: position(), clientView: clientView(interior, [0, -1]) }
            walk.entered = entered
          }
          return bot.game.dimension === 'the_end'
        }, ENTER_TIMEOUT_MS, `${site.phase}: ${approach} never left the overworld within ${ENTER_TIMEOUT_MS} ms`,
        () => !site.inside && bot.game.dimension === 'overworld' && bot.entity.position.y < y + 0.05
          && Math.abs(bot.entity.position.x - (x + 0.5)) < 1.5 && Math.abs(bot.entity.position.z - (z + 0.5)) < 1.5)
      }
      await p.wait(() => nearWindow(target),
        `${site.phase}: arrival near the END_ARRIVAL window at ${target.x},${target.y},${target.z}`, 10000)
      walk.arrived = position()
      context.expect(bot.entity.position.y <= target.y + 1.5, `${site.phase}: arrival above the END_ARRIVAL window`, walk.arrived)
      await context.sleep(1500)
      context.expect(bot.game.dimension === 'the_end', `${site.phase}: player bounced out of the End after arrival`, position())
      walk.settled = position()
    }

    async function endReturn() {
      const walk = { phase: 'end-return', site: 'fountain', samples: [] }
      evidence.walks.push(walk)
      context.expect(bot.game.dimension === 'the_end', 'end-return: player is not in the End', { dimension: bot.game.dimension })
      const dragonMarker = `QA_DRAGON_${bot.username}`
      const registered = (await storedPortals(portalDirectory)).some(portal => portal.data.dimensionalPortalKind === 'END_EXIT')
      if (!registered) await p.wait(async () => {
        try {
          await context.command(`/execute if entity @e[type=minecraft:ender_dragon] run tellraw ${bot.username} ${JSON.stringify({ text: dragonMarker })}`,
            new RegExp(dragonMarker), 1000)
          return true
        } catch {
          return false
        }
      }, 'end-return: ender dragon spawns', 60000)
      if (!registered) await context.command('/kill @e[type=minecraft:ender_dragon]', /Killed/)
      const exit = await waitForPortal(portal => portal.data.dimensionalPortalKind === 'END_EXIT', 'end-return: exit fountain registered as END_EXIT', 30000)
      const fountain = cells(exit)
      const origin = { x: Math.round(centre(fountain).x - 0.5), y: fountain[0].y, z: Math.round(centre(fountain).z - 0.5) }
      evidence.fountain = { id: exit.id, cells: fountain.length, origin, structure: exit.data.structure }
      context.expect(fountain.length >= 9 && fountain.every(cell => cell.y === origin.y), 'end-return: exit fountain cells form one layer', evidence.fountain)
      await serverBlockIn('the_end', origin.x + 2, origin.y, origin.z, 'minecraft:end_portal')
      await serverBlockIn('the_end', origin.x + 3, origin.y, origin.z, 'minecraft:bedrock')
      await serverBlockIn('the_end', origin.x + 2, origin.y - 1, origin.z, 'minecraft:bedrock')
      await stageIn('the_end', origin.x + 3.5, origin.y + 1, origin.z + 0.5, 90)
      walk.start = position()
      const row = [1, 2].map(dx => ({ x: origin.x + dx, y: origin.y, z: origin.z }))
      walk.clientViewBefore = clientView(row, [0, -1, 1])
      let entered = null
      await walkUntil(walk, Math.PI / 2, () => {
        if (!entered && bot.entity.position.x < origin.x + 2.7 && bot.game.dimension === 'the_end') {
          entered = { position: position(), clientView: clientView(row, [0, -1, 1]) }
          walk.entered = entered
        }
        return bot.game.dimension === 'overworld'
      }, RETURN_TIMEOUT_MS,
      `end-return: walking off the rim into the End exit fountain (portal cells y=${origin.y}) never returned to the overworld within ${RETURN_TIMEOUT_MS} ms`)
      await p.wait(() => bot.blockAt(bot.entity.position.floored()), 'end-return: overworld arrival chunk loads', 10000)
      walk.arrived = position()
      returned = true
    }

    try {
      await context.step('prepare isolated arena with End portal replacement enabled', async () => {
        const toml = await readFile(path.join(context.server.directory, 'plugins/Wormholes/wormholes.toml'), 'utf8')
        context.expect(/^replace-nether-and-end-portals\s*=\s*true\s*$/m.test(toml), 'wormholes.toml must set replace-nether-and-end-portals = true')
        await p.arena()
        const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
        evidence.serverType = /^type=(\S+)\s*$/m.exec(source)?.[1] ?? 'unknown'
        evidence.gameMode = bot.game.gameMode
        evidence.version = bot.version
      })
      const records = new Map()
      for (const site of SITES.filter(candidate => !candidate.inside)) {
        await phase(site.phase, `build and complete the ${site.label} End portal with an Eye of Ender`, async () => {
          records.set(site.label, await buildAndComplete(site))
        })
      }
      await phase('floor-drop', 'walk off the frame onto the End portal built on a solid floor', () => enter(SITES[0], records.get('floor')))
      if (bot.game.dimension === 'the_end') {
        await phase('end-return', 'walk from the fountain rim into the End exit portal', endReturn)
      }
      await phase('floor-enter', 'stand still at floor level inside the floor End portal completed from outside the ring', () => enter(SITES[2], records.get('floor-level')))
      await phase('floor-convert', 'complete the floor End portal while standing inside it beside an armor stand, then walk across it at floor level', async () => {
        const site = SITES[3]
        const probe = { x: site.x - 0.5, y: site.y, z: site.z - 0.5 }
        const armorStandNear = async (dimension, at, radius, timeoutMs) => {
          const marker = `QA_STAND_${bot.username}_${Math.round(at.x)}_${Math.round(at.z)}`
          const selector = `@e[type=minecraft:armor_stand,x=${at.x.toFixed(2)},y=${at.y.toFixed(2)},z=${at.z.toFixed(2)},distance=..${radius}]`
          const deadline = Date.now() + timeoutMs
          while (bot.game.dimension === dimension) {
            try {
              await context.command(`/execute if entity ${selector} run tellraw ${bot.username} ${JSON.stringify({ text: marker })}`, new RegExp(marker), 1000)
              return true
            } catch {
              if (Date.now() >= deadline) return false
            }
          }
          return false
        }
        if (bot.game.dimension !== 'overworld') await stageIn('overworld', site.x + 0.5, site.y, site.z + 3.5, 180)
        await context.command(`/summon minecraft:armor_stand ${probe.x} ${probe.y} ${probe.z}`, /Summoned/i)
        const record = await buildAndComplete(site)
        record.probe = { summoned: probe }
        if (record.vanillaTravel) {
          const { dimension, position: at } = record.vanillaTravel
          const platform = Math.hypot(at.x - 100.5, at.z - 0.5) < 8 ? ' (the vanilla End platform)' : ''
          record.probe.alongsideVanillaTravel = await armorStandNear(dimension, at, 8, 3000)
          context.expect(false, `floor-convert: completing the floor-level End portal while standing inside it sent the player to ${dimension} at ${at.x},${at.y},${at.z}${platform} through vanilla travel before Wormholes converted it`
            + (record.probe.alongsideVanillaTravel ? '; the armor stand standing in the ring arrived there with it' : ''), record.vanillaTravel)
        }
        await enter(site, record)
        const arrival = record.arrival.centre
        if (await armorStandNear('the_end', arrival, 14, 6000)) record.probe.located = 'end-arrival'
        await stageIn('overworld', site.x + 0.5, site.y, site.z + 3.5, 180)
        if (!record.probe.located && await armorStandNear('overworld', probe, 2, 4000)) record.probe.located = 'overworld-ring'
        context.expect(record.probe.located, 'floor-convert: the armor stand standing in the ring neither stayed in the overworld ring nor arrived through the END_ARRIVAL',
          record.probe)
        await verifyCleared(site)
      })
      await phase('elevated-enter', 'walk from the frame onto the End portal built over a pit', () => enter(SITES[1], records.get('elevated')))
      if (!returned && !failures.some(failure => failure.startsWith('end-return:'))) {
        if (bot.game.dimension === 'the_end') {
          await phase('end-return', 'walk from the fountain rim into the End exit portal', endReturn)
        } else {
          failures.push('end-return: not exercised because no End entry succeeded')
          evidence.failures.push({ phase: 'end-return', message: 'not exercised because no End entry succeeded' })
        }
      }
    } finally {
      bot.clearControlStates()
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
      if (logPath) {
        const handle = await open(logPath, 'r')
        try {
          const size = (await handle.stat()).size
          const buffer = Buffer.alloc(Math.max(0, size - logOffset))
          await handle.read(buffer, 0, buffer.length, logOffset)
          evidence.log = buffer.toString('utf8').replace(/\u001b\[[0-9;]*[A-Za-z]/g, '').split('\n')
            .filter(line => /vanilla-portal|\[cross\]|\[latch\]|\[convoy\]|WARN|ERROR|Exception|moved wrongly|End fountain|End return|issued server command/.test(line)).slice(0, 400)
        } finally {
          await handle.close()
        }
      }
    }
    context.expect(failures.length === 0, failures.join('; '), evidence.failures)
  }
}
