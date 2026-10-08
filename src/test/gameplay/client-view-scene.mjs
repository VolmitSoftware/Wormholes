import { writeFile } from 'node:fs/promises'

import {
  closeBot,
  createClientViewBot,
  installMovementFilter,
  loadHarnessModule
} from './support/clientview-client.mjs'

const FAKE_ENTITY_ID_MIN = -0x3FFFFFFF
const FAKE_ENTITY_ID_MAX = -0x20000000
const ENTITY_PACKETS = ['entity_velocity', 'rel_entity_move', 'entity_move_look', 'entity_look', 'sync_entity_position', 'entity_teleport']
const SCENE_TYPES = ['ENTITY_FRAME', 'FX', 'ATMOSPHERE']
const LIFECYCLE_PARTICLES = new Set(['end_rod', 'portal', 'reverse_portal', 'enchant', 'flash', 'electric_spark', 'smoke', 'explosion', 'sculk_soul', 'block'])

function isWormholesEntityId(id) {
  return id >= FAKE_ENTITY_ID_MIN && id <= FAKE_ENTITY_ID_MAX
}

function number(value, fallback) {
  if (value === undefined || value === '') return fallback
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) throw new Error(`expected a number, got ${value}`)
  return parsed
}

function parsePoint(text, fallback) {
  if (!text) return fallback
  const values = text.trim().split(/[\s,]+/).map(Number)
  if (values.length !== 3 || values.some((value) => !Number.isFinite(value))) throw new Error('WORMHOLES_SCENE_CENTER must be "x y z"')
  return { x: values[0], y: values[1], z: values[2] }
}

function loop(center, radius) {
  return [
    { x: center.x - radius, z: center.z + radius },
    { x: center.x + radius, z: center.z + radius },
    { x: center.x + radius, z: center.z - radius },
    { x: center.x - radius, z: center.z - radius }
  ]
}

function path(corners, stepBlocks) {
  const points = []
  for (let i = 0; i < corners.length; i++) {
    const a = corners[i]
    const b = corners[(i + 1) % corners.length]
    const steps = Math.max(1, Math.round(Math.hypot(b.x - a.x, b.z - a.z) / stepBlocks))
    for (let s = 0; s < steps; s++) points.push({ x: a.x + (b.x - a.x) * s / steps, z: a.z + (b.z - a.z) * s / steps })
  }
  return points
}

function sceneCounter(bot, center, lightRadius) {
  const state = { counting: false, entity: {}, particles: {}, light: 0, farLight: 0, lightChunks: {}, blockChanges: 0, wormholesEntityIds: new Set() }
  const onPacket = (data, meta) => {
    if (meta.state !== 'play') return
    if (meta.name === 'spawn_entity' && isWormholesEntityId(data.entityId)) state.wormholesEntityIds.add(data.entityId)
    if (!state.counting) return
    if (ENTITY_PACKETS.includes(meta.name) && isWormholesEntityId(data.entityId)) {
      state.entity[meta.name] = (state.entity[meta.name] ?? 0) + 1
    }
    if (meta.name === 'world_particles') {
      const key = String(data.particle?.type ?? data.particleId ?? data.particle ?? 'unknown')
      state.particles[key] = (state.particles[key] ?? 0) + 1
    }
    if (meta.name === 'update_light') {
      const distance = Math.hypot(data.chunkX * 16 + 8 - center.x, data.chunkZ * 16 + 8 - center.z)
      if (distance <= lightRadius) state.light++
      else state.farLight++
      const chunk = `${data.chunkX},${data.chunkZ}`
      state.lightChunks[chunk] = (state.lightChunks[chunk] ?? 0) + 1
    }
    if (meta.name === 'multi_block_change' || meta.name === 'block_change') state.blockChanges++
  }
  bot._client.on('packet', onPacket)
  return {
    begin() { state.counting = true },
    end() { state.counting = false },
    detach() { bot._client.removeListener('packet', onPacket) },
    snapshot(seconds) {
      const particles = Object.values(state.particles).reduce((sum, value) => sum + value, 0)
      const lifecycle = Object.entries(state.particles).filter(([type]) => LIFECYCLE_PARTICLES.has(type)).reduce((sum, [, value]) => sum + value, 0)
      const perSecond = (value) => Number((value / Math.max(0.001, seconds)).toFixed(2))
      return {
        wormholesEntityPackets: state.entity,
        entityVelocityPerSecond: perSecond(state.entity.entity_velocity ?? 0),
        relEntityMovePerSecond: perSecond(state.entity.rel_entity_move ?? 0),
        worldParticlesPerSecond: perSecond(particles),
        lifecycleParticlesPerSecond: perSecond(lifecycle),
        updateLightPerSecond: perSecond(state.light),
        farUpdateLightPerSecond: perSecond(state.farLight),
        particlesByType: state.particles,
        lightChunks: state.lightChunks,
        blockChangesPerSecond: perSecond(state.blockChanges),
        wormholesSpawns: state.wormholesEntityIds.size
      }
    }
  }
}

export default {
  name: 'client-view-scene',
  description: 'Glide a negotiated ClientView bot and a vanilla bot through a portal scene and prove the negotiated bot receives no Wormholes entity motion, particle or light packets while the scene frames arrive over the ClientView channel.',
  async run(context) {
    const vanilla = context.bot
    const username = process.env.WORMHOLES_SCENE_BOT || 'WhCvScene'
    const center = parsePoint(process.env.WORMHOLES_SCENE_CENTER, { x: 637.5, y: 63, z: -4686.5 })
    const seconds = number(process.env.WORMHOLES_SCENE_SECONDS, 30)
    const warmSeconds = number(process.env.WORMHOLES_SCENE_WARM_SECONDS, 10)
    const radius = number(process.env.WORMHOLES_SCENE_RADIUS, 2.5)
    const lightRadius = number(process.env.WORMHOLES_SCENE_LIGHT_RADIUS, 96)
    const mineflayer = loadHarnessModule('mineflayer')
    const evidence = { username, center, seconds, warmSeconds, lightRadius }
    context.report.clientViewScene = evidence
    vanilla.physicsEnabled = false
    installMovementFilter(vanilla)
    const vanillaScene = sceneCounter(vanilla, center, lightRadius)
    let client = null
    let clientScene = null
    let failure = null
    const alive = () => {
      if (failure) throw failure
      return true
    }
    try {
      await context.step('connect the ClientView bot', async () => {
        client = createClientViewBot(mineflayer, { host: context.server.host, port: context.server.port, username, version: vanilla.version, brand: 'fabric',
          peer: { capture: Boolean(process.env.WORMHOLES_SCENE_CAPTURE) } })
        clientScene = sceneCounter(client.bot, center, lightRadius)
        let spawned = false
        client.spawned.then(() => { spawned = true }, (error) => { failure = error })
        await context.waitUntil(() => alive() && spawned, { timeoutMs: 30000, label: `${username} spawn` })
        client.bot.on('kicked', (reason) => { failure = new Error(`${username} kicked: ${JSON.stringify(reason)}`) })
        client.bot.on('end', (reason) => { failure ??= new Error(`${username} disconnected: ${reason}`) })
        client.bot.on('error', (error) => { failure ??= error })
        await context.waitUntil(() => alive() && client.peer.settled(), { timeoutMs: 15000, label: 'ClientView negotiation' })
        context.expect(client.peer.state === 'CLIENT_VIEW', `ClientView negotiation ended in ${client.peer.state}`, client.peer.negotiations)
        evidence.caps = client.peer.caps.toString(16)
      })

      await context.step('move both bots into the scene', async () => {
        let sent = 0
        await context.waitUntil(() => {
          alive()
          const position = client.bot.entity?.position
          if (position && Math.hypot(position.x - center.x, position.z - center.z) < 1.5) return true
          if (Date.now() - sent >= 2000) {
            vanilla.chat(`/tp @s ${center.x} ${center.y} ${center.z} 0 0`)
            vanilla.chat(`/tp ${username} ${center.x} ${center.y} ${center.z} 0 0`)
            sent = Date.now()
          }
          return false
        }, { timeoutMs: 20000, label: `${username} arrival` })
        await context.sleep(warmSeconds * 1000)
        alive()
      })

      await context.step('glide and count', async () => {
        const points = path(loop(center, radius), 0.3)
        client.counters.begin()
        vanillaScene.begin()
        clientScene.begin()
        const started = Date.now()
        let index = 0
        let yaw = 0
        while (Date.now() - started < seconds * 1000) {
          alive()
          const point = points[index % points.length]
          const pitch = Math.round(Math.sin(index / 37) * 35 - 10)
          vanilla.chat(`/tp @s ${point.x.toFixed(3)} ${center.y} ${point.z.toFixed(3)} ${yaw.toFixed(1)} ${pitch}`)
          vanilla.chat(`/tp ${username} ${point.x.toFixed(3)} ${center.y} ${point.z.toFixed(3)} ${yaw.toFixed(1)} ${pitch}`)
          yaw = (yaw + 4.5) % 360
          index++
          await context.sleep(100)
        }
        const elapsed = (Date.now() - started) / 1000
        client.counters.end()
        vanillaScene.end()
        clientScene.end()
        evidence.elapsedSeconds = elapsed
        evidence.clientView = { ...clientScene.snapshot(elapsed), wormholes: client.counters.snapshot().wormholes, plates: client.peer.plates.length,
          portals: client.peer.portals.size, violations: client.peer.violationCount, worldFx: { ...client.peer.worldFx } }
        evidence.vanilla = vanillaScene.snapshot(elapsed)
      })

      await context.step('negotiated bot receives no Wormholes motion, particle or light packets', async () => {
        const negotiated = evidence.clientView
        context.expect(negotiated.violations === 0, `${negotiated.violations} ClientView protocol violations`, client.peer.violations.slice(0, 10))
        context.expect(negotiated.entityVelocityPerSecond === 0, `negotiated bot saw ${negotiated.entityVelocityPerSecond}/s Wormholes entity_velocity`, negotiated)
        context.expect(negotiated.relEntityMovePerSecond === 0, `negotiated bot saw ${negotiated.relEntityMovePerSecond}/s Wormholes rel_entity_move`, negotiated)
        context.expect(negotiated.worldParticlesPerSecond === 0, `negotiated bot saw ${negotiated.worldParticlesPerSecond}/s world_particles`, negotiated.particlesByType)
        context.expect(negotiated.updateLightPerSecond === 0, `negotiated bot saw ${negotiated.updateLightPerSecond}/s update_light`, negotiated)
        context.expect(negotiated.wormholesSpawns === 0, `negotiated bot received ${negotiated.wormholesSpawns} Wormholes entity spawns`, negotiated)
        if (evidence.vanilla.lifecycleParticlesPerSecond > 0) {
          const oneShots = (negotiated.worldFx.ANIMATION ?? 0) + (negotiated.worldFx.BURST ?? 0)
          context.expect(oneShots > 0, 'the vanilla bot saw portal lifecycle particles but no lifecycle emitter reached the negotiated bot', negotiated.worldFx)
        }
        const sceneFrames = SCENE_TYPES.reduce((sum, type) => sum + (negotiated.wormholes.byType[type]?.frames ?? 0), 0)
        context.expect(negotiated.plates > 0, 'no plate reached the negotiated bot', negotiated.wormholes.byType)
        evidence.sceneFrames = sceneFrames
      })
    } finally {
      vanillaScene.detach()
      if (client) {
        clientScene?.detach()
        client.peer.close()
        if (process.env.WORMHOLES_SCENE_CAPTURE) {
          await writeFile(process.env.WORMHOLES_SCENE_CAPTURE, client.peer.captured.map((entry) => JSON.stringify(entry)).join('\n') + '\n')
        }
        client.counters.detach()
        await closeBot(client.bot)
      }
    }
  }
}
