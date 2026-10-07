import { writeFile } from 'node:fs/promises'

import {
  DEFAULT_CLIENT_CAPS,
  attachPacketCounters,
  closeBot,
  createClientViewBot,
  installMovementFilter,
  loadHarnessModule
} from './support/clientview-client.mjs'
import { CHANNEL, DECLINE_REASONS, parseCapabilities } from './support/clientview-codec.mjs'

function positiveNumber(value, fallback, label) {
  if (value === undefined || value === '') return fallback
  const number = Number(value)
  if (!Number.isFinite(number) || number < 0) throw new Error(`${label} must be a non-negative number`)
  return number
}

function oneOf(value, fallback, allowed, label) {
  const chosen = value === undefined || value === '' ? fallback : value
  if (!allowed.includes(chosen)) throw new Error(`${label} must be one of ${allowed.join(', ')}`)
  return chosen
}

function parsePose(text) {
  if (text === undefined || text.trim() === '') return null
  const values = text.trim().split(/[\s,]+/).map(Number)
  if (values.length < 3 || values.length > 5 || values.some((value) => !Number.isFinite(value))) throw new Error('WORMHOLES_CV_POSE must be "x y z [yaw pitch]"')
  return { x: values[0], y: values[1], z: values[2], yaw: values[3] ?? 0, pitch: values[4] ?? 0 }
}

const LIFECYCLE_STEPS = ['dimension', 'respawn', 'toggle']
const DEFAULT_FAR_POSE = { x: 0.5, y: 130, z: 0.5, yaw: 0, pitch: 0 }
const STREAM_TYPES = new Set(['PORTAL', 'PORTAL_DROP', 'PLATE_BEGIN', 'PLATE_PATCH', 'PLATE_HANDLE', 'ENTITY_FRAME', 'ATMOSPHERE'])
const PLATE_ENDS = new Set(['PLATE_END'])

function parseLifecycle(text) {
  if (text === undefined || text.trim() === '') return []
  const steps = text.trim().split(/[\s,]+/)
  const unknown = steps.filter((step) => !LIFECYCLE_STEPS.includes(step))
  if (unknown.length > 0) throw new Error(`WORMHOLES_CV_LIFECYCLE entries must be among ${LIFECYCLE_STEPS.join(', ')}`)
  return steps
}

function parseDataVersion(text) {
  if (text === undefined || text === '' || text === 'echo') return 'echo'
  if (text === 'bot') return 'bot'
  const value = Number(text)
  if (!Number.isInteger(value)) throw new Error('WORMHOLES_CV_DATA_VERSION must be echo, bot or an integer')
  return value
}

export function readSettings(environment) {
  const expect = oneOf(environment.WORMHOLES_CV_EXPECT, 'accept', ['accept', 'decline', 'none'], 'WORMHOLES_CV_EXPECT')
  const declineReason = environment.WORMHOLES_CV_DECLINE_REASON || null
  if (declineReason !== null && !DECLINE_REASONS.includes(declineReason)) throw new Error(`WORMHOLES_CV_DECLINE_REASON must be one of ${DECLINE_REASONS.join(', ')}`)
  const username = environment.WORMHOLES_CV_BOT || 'WhClientA'
  if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('WORMHOLES_CV_BOT must be a valid offline username')
  const pose = parsePose(environment.WORMHOLES_CV_POSE)
  const lifecycle = parseLifecycle(environment.WORMHOLES_CV_LIFECYCLE)
  if (lifecycle.length > 0 && (expect !== 'accept' || pose === null)) throw new Error('WORMHOLES_CV_LIFECYCLE needs WORMHOLES_CV_EXPECT=accept and a WORMHOLES_CV_POSE to return to')
  return {
    bots: oneOf(environment.WORMHOLES_CV_BOTS, 'ab', ['a', 'ab'], 'WORMHOLES_CV_BOTS'),
    username,
    expect,
    declineReason,
    handshake: oneOf(environment.WORMHOLES_CV_HANDSHAKE, 'any', ['any', 'config', 'play'], 'WORMHOLES_CV_HANDSHAKE'),
    dataVersion: parseDataVersion(environment.WORMHOLES_CV_DATA_VERSION),
    caps: environment.WORMHOLES_CV_CAPS === undefined ? DEFAULT_CLIENT_CAPS : parseCapabilities(environment.WORMHOLES_CV_CAPS),
    brand: environment.WORMHOLES_CV_BRAND || 'fabric',
    cache: oneOf(environment.WORMHOLES_CV_CACHE, 'warm', ['warm', 'cold'], 'WORMHOLES_CV_CACHE'),
    ack: oneOf(environment.WORMHOLES_CV_ACK, 'coalesced', ['coalesced', 'every', 'off'], 'WORMHOLES_CV_ACK'),
    maxFrameBytes: Math.round(positiveNumber(environment.WORMHOLES_CV_MAX_FRAME_KB, 512, 'WORMHOLES_CV_MAX_FRAME_KB') * 1024),
    seconds: positiveNumber(environment.WORMHOLES_CV_SECONDS, 20, 'WORMHOLES_CV_SECONDS'),
    negotiateSeconds: positiveNumber(environment.WORMHOLES_CV_NEGOTIATE_SECONDS, 10, 'WORMHOLES_CV_NEGOTIATE_SECONDS'),
    pose,
    minPlates: expect === 'accept' ? positiveNumber(environment.WORMHOLES_CV_MIN_PLATES, 1, 'WORMHOLES_CV_MIN_PLATES') : 0,
    lifecycle,
    lifecycleSeconds: positiveNumber(environment.WORMHOLES_CV_LIFECYCLE_SECONDS, 60, 'WORMHOLES_CV_LIFECYCLE_SECONDS'),
    quietSeconds: positiveNumber(environment.WORMHOLES_CV_QUIET_SECONDS, 3, 'WORMHOLES_CV_QUIET_SECONDS'),
    homeDimension: environment.WORMHOLES_CV_HOME_DIMENSION || 'minecraft:overworld',
    farDimension: environment.WORMHOLES_CV_FAR_DIMENSION || 'minecraft:the_nether',
    farPose: parsePose(environment.WORMHOLES_CV_FAR_POSE) ?? DEFAULT_FAR_POSE,
    capture: environment.WORMHOLES_CV_CAPTURE || null,
    frameLogLimit: positiveNumber(environment.WORMHOLES_CV_FRAME_LOG, 5000, 'WORMHOLES_CV_FRAME_LOG')
  }
}

function plain(value) {
  return JSON.parse(JSON.stringify(value, (key, entry) => typeof entry === 'bigint' ? `0x${entry.toString(16)}` : entry))
}

function settingsReport(settings) {
  return { ...settings, caps: `0x${settings.caps.toString(16)}` }
}

function tp(target, pose) {
  return `/tp ${target} ${pose.x} ${pose.y} ${pose.z} ${pose.yaw} ${pose.pitch}`
}

function tpIn(dimension, target, pose) {
  return `/execute in ${dimension} run tp ${target} ${pose.x} ${pose.y} ${pose.z} ${pose.yaw} ${pose.pitch}`
}

function entryAfter(peer, from, type, reason = undefined) {
  for (let index = from; index < peer.log.length; index++) {
    const entry = peer.log[index]
    if (entry.dir === 'S2C' && entry.type === type && (reason === undefined || entry.summary?.reason === reason)) return entry
  }
  return null
}

function framesAfter(peer, entry, types) {
  return peer.log.slice(peer.log.indexOf(entry) + 1).filter((frame) => frame.dir === 'S2C' && types.has(frame.type))
}

export default {
  name: 'client-view-protocol',
  description: 'Negotiate the Wormholes ClientView channel with a hand-encoded HELLO, record every ClientView channel frame with byte counts, and prove a second vanilla bot keeps the vanilla stream.',
  async run(context) {
    const settings = readSettings(process.env)
    const vanilla = context.bot
    context.expect(vanilla.username.toLowerCase() !== settings.username.toLowerCase(), 'WORMHOLES_CV_BOT must differ from the harness bot')
    const mineflayer = loadHarnessModule('mineflayer')
    const evidence = { settings: settingsReport(settings) }
    context.report.clientView = evidence
    vanilla.physicsEnabled = false
    installMovementFilter(vanilla)
    const vanillaCounters = attachPacketCounters(vanilla)
    let client = null
    let failure = null
    const alive = () => {
      if (failure) throw failure
      return true
    }
    try {
      await context.step('connect the ClientView bot', async () => {
        const dataVersion = settings.dataVersion === 'bot' ? vanilla.registry.version.dataVersion : settings.dataVersion
        client = createClientViewBot(mineflayer, {
          host: context.server.host,
          port: context.server.port,
          username: settings.username,
          version: vanilla.version,
          brand: settings.brand,
          peer: { mcDataVersion: dataVersion, caps: settings.caps, maxFrameBytes: settings.maxFrameBytes, cache: settings.cache, ack: settings.ack, frameLogLimit: settings.frameLogLimit, capture: settings.capture !== null }
        })
        let spawned = false
        client.spawned.then(() => { spawned = true }, (error) => { failure = error })
        await context.waitUntil(() => alive() && spawned, { timeoutMs: 30000, label: `${settings.username} spawn` })
        client.bot.on('kicked', (reason) => { failure = new Error(`${settings.username} kicked: ${JSON.stringify(reason)}`) })
        client.bot.on('end', (reason) => { failure ??= new Error(`${settings.username} disconnected: ${reason}`) })
        client.bot.on('error', (error) => { failure ??= error })
      })

      await context.step(`negotiation ends in ${settings.expect}`, async () => {
        const peer = client.peer
        const timeoutMs = Math.max(1, settings.negotiateSeconds * 1000)
        if (settings.expect === 'none') {
          await context.sleep(timeoutMs)
          alive()
          context.expect(peer.state !== 'CLIENT_VIEW', 'The server accepted a session that should stay vanilla', plain(peer.negotiations))
          return
        }
        await context.waitUntil(() => alive() && peer.settled(), { timeoutMs, label: 'ACCEPT or DECLINE' })
        const negotiation = peer.negotiations.at(-1)
        const expected = settings.expect === 'accept' ? 'ACCEPT' : 'DECLINE'
        context.expect(negotiation.outcome === expected, `The server answered ${negotiation.outcome} instead of ${expected}`, plain(negotiation))
        if (settings.declineReason) context.expect(negotiation.reason === settings.declineReason, `DECLINE reason ${negotiation.reason} is not ${settings.declineReason}`, plain(negotiation))
        if (settings.handshake !== 'any') {
          const phase = settings.handshake === 'config' ? 'configuration' : 'play'
          context.expect(negotiation.phase === phase, `OFFER arrived in ${negotiation.phase} instead of ${phase}`, plain(negotiation))
        }
      })

      if (settings.pose) {
        await context.step('move the bots to the pose', async () => {
          vanilla.chat(tp(settings.username, settings.pose))
          if (settings.bots === 'ab') vanilla.chat(tp('@s', settings.pose))
          await context.waitUntil(() => {
            alive()
            const position = client.bot.entity?.position
            return position && Math.hypot(position.x - settings.pose.x, position.z - settings.pose.z) < 1.5
          }, { timeoutMs: 10000, label: `${settings.username} arrival at the pose` })
        })
      }

      await context.step(`observe the ${CHANNEL} stream`, async () => {
        client.peer.mark('observe-start')
        client.counters.begin()
        vanillaCounters.begin()
        const until = Date.now() + settings.seconds * 1000
        await context.waitUntil(() => alive() && Date.now() >= until, { timeoutMs: settings.seconds * 1000 + 5000, label: 'observation window', intervalMs: 100 })
        client.counters.end()
        vanillaCounters.end()
        client.peer.mark('observe-end')
      })

      const lifecycleMs = settings.lifecycleSeconds * 1000
      const waitForFrame = async (from, type, reason = undefined) => {
        let entry = null
        await context.waitUntil(() => {
          alive()
          entry = entryAfter(client.peer, from, type, reason)
          return entry !== null
        }, { timeoutMs: lifecycleMs, label: reason === undefined ? type : `${type}{${reason}}` })
        return entry
      }
      const waitForPlates = (since, label) => context.waitUntil(() => alive() && framesAfter(client.peer, since, PLATE_ENDS).length >= settings.minPlates,
        { timeoutMs: lifecycleMs, label, intervalMs: 100 })
      const lifecycle = {
        dimension: async () => {
          const peer = client.peer
          const from = peer.log.length
          vanilla.chat(tpIn(settings.farDimension, settings.username, settings.farPose))
          const departed = await waitForFrame(from, 'SESSION_RESET', 'DIMENSION')
          await context.sleep(settings.quietSeconds * 1000)
          alive()
          context.expect(peer.logDropped === 0, 'The frame log overflowed; raise WORMHOLES_CV_FRAME_LOG')
          const leaked = framesAfter(peer, departed, STREAM_TYPES)
          context.expect(leaked.length === 0, `${leaked.length} stream frames arrived in ${settings.farDimension} after SESSION_RESET{DIMENSION}`, leaked.slice(0, 20))
          context.expect(peer.state === 'CLIENT_VIEW', `The session ended in ${peer.state} after the dimension change`, plain(peer.negotiations))
          const back = peer.log.length
          vanilla.chat(tpIn(settings.homeDimension, settings.username, settings.pose))
          const returned = await waitForFrame(back, 'SESSION_RESET', 'DIMENSION')
          await waitForPlates(returned, `${settings.minPlates} plates after returning to ${settings.homeDimension}`)
        },
        respawn: async () => {
          const peer = client.peer
          const from = peer.log.length
          vanilla.chat(`/kill ${settings.username}`)
          const respawned = await waitForFrame(from, 'SESSION_RESET', 'RESPAWN')
          vanilla.chat(tpIn(settings.homeDimension, settings.username, settings.pose))
          await waitForPlates(respawned, `${settings.minPlates} plates after the respawn`)
          context.expect(peer.state === 'CLIENT_VIEW', `The session ended in ${peer.state} after the respawn`, plain(peer.negotiations))
        },
        toggle: async () => {
          const peer = client.peer
          const negotiations = peer.negotiations.length
          const from = peer.log.length
          await context.command('/wormholes clientview off', /ClientView is off/i, lifecycleMs)
          await waitForFrame(from, 'SESSION_RESET', 'DISABLED')
          context.expect(peer.state === 'VANILLA', `The session is ${peer.state} after the kill switch`, plain(peer.negotiations))
          await context.sleep(settings.quietSeconds * 1000)
          alive()
          context.expect(peer.negotiations.length === negotiations, 'The server offered ClientView while it was switched off', plain(peer.negotiations))
          const off = peer.log.length
          await context.command('/wormholes clientview on', /ClientView is offered again/i, lifecycleMs)
          const accepted = await waitForFrame(off, 'ACCEPT')
          const renewed = peer.negotiations.at(-1)
          context.expect(peer.negotiations.length === negotiations + 1 && renewed.outcome === 'ACCEPT' && renewed.phase === 'play',
            `The renewed negotiation ended in ${renewed.outcome} during ${renewed.phase}`, plain(peer.negotiations))
          await waitForPlates(accepted, `${settings.minPlates} plates after switching ClientView back on`)
        }
      }
      for (const name of settings.lifecycle) {
        await context.step(`lifecycle: ${name}`, lifecycle[name])
      }

      await context.step('verify the protocol invariants', async () => {
        const peer = client.peer
        context.expect(peer.violationCount === 0, `${peer.violationCount} ClientView protocol violations`, peer.violations.slice(0, 20))
        if (settings.expect === 'accept') {
          context.expect(peer.state === 'CLIENT_VIEW', `The session ended in ${peer.state}`, plain(peer.negotiations))
          context.expect(peer.plates.length >= settings.minPlates, `Only ${peer.plates.length} complete plates arrived; expected at least ${settings.minPlates}`, { portals: peer.report().portals })
        } else {
          const streamed = Object.keys(peer.s2c.byType).filter((type) => !['OFFER', 'ACCEPT', 'DECLINE'].includes(type))
          context.expect(streamed.length === 0, `A session that did not negotiate received ${streamed.join(', ')}`, peer.s2c.byType)
        }
        if (settings.bots === 'ab') {
          const leaked = vanillaCounters.playWormholes
          context.expect(leaked.frames === 0, `The vanilla bot received ${leaked.frames} ${CHANNEL} frames in play`, leaked.byType)
        }
      })
    } finally {
      if (client) {
        client.peer.close()
        evidence.clientA = { username: settings.username, registration: client.registration, window: client.counters.snapshot(), session: client.peer.report() }
        if (settings.capture) {
          await writeFile(settings.capture, client.peer.captured.map((entry) => JSON.stringify(entry)).join('\n') + '\n')
          evidence.capture = { path: settings.capture, frames: client.peer.captured.length }
        }
        client.counters.detach()
        await closeBot(client.bot)
      }
      evidence.vanillaB = { username: vanilla.username, role: settings.bots === 'ab' ? 'vanilla observer' : 'operator', window: vanillaCounters.snapshot() }
      vanillaCounters.detach()
    }
  }
}
