import { readFile } from 'node:fs/promises'
import path from 'node:path'

import { closeBot, createClientViewBot, installMovementFilter, loadHarnessModule } from './support/clientview-client.mjs'
import { CHANNEL, capabilitySet, decodeS2C } from './support/clientview-codec.mjs'

export default {
  name: 'client-view-mesh-timeout',
  description: 'Keep reciprocal direct and nested mesh streams acknowledged beyond the capture timeout, then observe the return portal.',
  async run(context) {
    const seconds = Number(process.env.WORMHOLES_MESH_SECONDS ?? 65)
    context.expect(Number.isFinite(seconds) && seconds >= 65 && seconds <= 180,
      'Each mesh observation lasts between 65 and 180 seconds', seconds)
    const logPath = path.join(context.server.directory, 'logs/latest.log')
    context.bot.physicsEnabled = false
    installMovementFilter(context.bot)
    const logStart = (await readFile(logPath, 'utf8')).length
    const evidence = { seconds, phases: [], begins: 0, sections: 0, acknowledgements: 0, drops: [], resets: [] }
    context.report.meshTimeout = evidence
    const streams = new Map()
    const locked = new Set()
    let failure = null
    let closing = false
    let repositioning = false
    let client = null
    const healthy = () => {
      if (failure) throw failure
      if (client?.peer.violationCount) throw new Error(JSON.stringify(client.peer.violations))
      return true
    }
    const observe = (packet, metadata) => {
      if (metadata.name !== 'custom_payload' || packet.channel !== CHANNEL) return
      try {
        const message = decodeS2C(packet.data, client.peer.caps).message
        if (message.type === 'SESSION_RESET') {
          evidence.resets.push({ reason: message.reason, repositioning })
          streams.clear()
          if (!repositioning || message.reason !== 'TELEPORT') throw new Error('Unexpected ClientView reset: ' + message.reason)
        }
        if (message.type === 'PORTAL_DROP') {
          evidence.drops.push({ portalKey: message.portalKey, locked: locked.has(message.portalKey) })
          streams.delete(message.portalKey)
          if (locked.has(message.portalKey)) throw new Error('Observed mesh portal was dropped: ' + message.portalKey)
        }
        if (message.type === 'MESH_BEGIN') {
          streams.set(message.portalKey, { generation: message.generation, sections: new Set(), frames: 0 })
          evidence.begins++
        }
        if (message.type === 'MESH_DROP') {
          const stream = streams.get(message.portalKey)
          if (stream?.generation === message.generation) {
            stream.sections.delete([message.sectionX, message.sectionY, message.sectionZ].join(','))
          }
        }
        if (message.type === 'MESH_SECTION') {
          const stream = streams.get(message.portalKey)
          if (!stream || stream.generation !== message.generation) throw new Error('Mesh section has no matching generation')
          stream.sections.add([message.sectionX, message.sectionY, message.sectionZ].join(','))
          stream.frames++
          evidence.sections++
          client.peer.send({ type: 'MESH_ACK', portalKey: message.portalKey, generation: message.generation,
            sectionX: message.sectionX, sectionY: message.sectionY, sectionZ: message.sectionZ, revision: message.revision })
          evidence.acknowledgements++
        }
      } catch (error) {
        failure ??= error
      }
    }
    const snapshots = () => [...streams].map(([portalKey, stream]) => ({ portalKey, generation: stream.generation,
      sections: stream.sections.size, frames: stream.frames, parent: client.peer.portals.get(portalKey)?.geometry.parentPortalKey,
      origin: client.peer.portals.get(portalKey)?.geometry.origin }))
    async function phase(name, pose, nested) {
      locked.clear()
      repositioning = true
      const x = Number(pose.split(' ')[0])
      await context.command(`/tp WhMeshProbe ${pose}`, /Teleported/i)
      await context.waitUntil(() => healthy() && Math.abs(client.bot.entity.position.x - x) < 0.1,
        { timeoutMs: 10000, label: name + ' observer position' })
      await context.sleep(2000)
      await context.waitUntil(() => {
        healthy()
        const ready = snapshots().filter(stream => stream.sections > 0)
        return ready.some(stream => stream.parent === 0 && Math.abs(stream.origin[0] - x) < 8)
          && (!nested || ready.some(stream => stream.parent > 0))
      }, { timeoutMs: 60000, label: name + ' mesh sections' })
      for (const stream of snapshots()) {
        if (stream.sections > 0) locked.add(stream.portalKey)
      }
      repositioning = false
      const record = { name, startedAt: new Date().toISOString(), initial: snapshots() }
      evidence.phases.push(record)
      const deadline = Date.now() + seconds * 1000
      while (Date.now() < deadline) {
        healthy()
        context.expect(client.peer.state === 'CLIENT_VIEW', 'Mesh session stays accepted')
        for (const key of locked) context.expect(streams.get(key)?.sections.size > 0, 'Observed mesh retains sections', key)
        await context.sleep(250)
      }
      healthy()
      record.finishedAt = new Date().toISOString()
      record.final = snapshots()
      context.expect(record.final.some(stream => stream.parent === 0 && stream.sections > 0), 'Direct mesh remains available')
      if (nested) context.expect(record.final.some(stream => stream.parent > 0 && stream.sections > 0), 'Nested mesh remains available')
    }
    try {
      await context.step('create reciprocal nested portal pairs', async () => {
        if (context.bot.game.gameMode !== 'creative') await context.command('/gamemode creative', /creative/i)
        await context.command('/whnested mesh', /NESTED ready=true/, 60000)
        await context.command('/tp @s 8.5 191 40.5', /Teleported/i)
      })
      await context.step('negotiate a mesh-capable client', async () => {
        client = createClientViewBot(loadHarnessModule('mineflayer'), { host: context.server.host,
          port: context.server.port, username: 'WhMeshProbe', version: context.bot.version,
          peer: { caps: capabilitySet('PLATES', 'MESH_RENDER', 'CLIENT_RECURSION', 'DEST_LIGHT', 'CONFIG_PHASE'), frameLogLimit: 1000 } })
        client.bot.on('error', error => { failure ??= error })
        client.bot.on('kicked', reason => { failure ??= new Error('Mesh bot kicked: ' + JSON.stringify(reason)) })
        client.bot.on('end', reason => { if (!closing) failure ??= new Error('Mesh bot disconnected: ' + reason) })
        client.bot._client.on('packet', observe)
        let spawned = false
        client.spawned.then(() => { spawned = true }, error => { failure ??= error })
        await context.waitUntil(() => healthy() && spawned && client.peer.settled(), { timeoutMs: 30000, label: 'mesh negotiation' })
        context.expect(client.peer.state === 'CLIENT_VIEW', 'Server accepts mesh capabilities', client.peer.negotiations)
        if (client.bot.game.gameMode !== 'creative') await context.command('/gamemode creative WhMeshProbe', /creative/i)
      })
      await context.step('direct and nested streams survive the capture window', () => phase('source', '8.5 191 13.5 180 0', true))
      await context.step('return stream survives the capture window', () => phase('return', '104.5 191 13.5 180 0', false))
      const runtime = (await readFile(logPath, 'utf8')).slice(logStart)
      context.expect(!/mesh capture timeout|View stream (?:nested )?mesh failed/i.test(runtime), 'Server logs contain no mesh capture failures')
      context.expect(evidence.sections > 0 && evidence.sections === evidence.acknowledgements, 'Every received mesh section is acknowledged', evidence)
    } finally {
      closing = true
      locked.clear()
      if (client) {
        evidence.protocol = client.peer.report()
        client.bot._client.removeListener('packet', observe)
        client.peer.close()
        await closeBot(client.bot)
      }
    }
  }
}
