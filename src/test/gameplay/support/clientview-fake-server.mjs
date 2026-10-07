import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import {
  ALL_CAPS,
  CHANNEL,
  ClientViewProtocolError,
  DEFAULT_MAX_FRAME_BYTES,
  FLAG_LAST,
  WIRE_VERSION,
  brickMissIndices,
  capabilityMask,
  capabilityNames,
  clampMaxFrameBytes,
  decodeC2S,
  decodeS2C,
  encodeS2C,
  hasCapability,
  peekType,
  summarize
} from './clientview-codec.mjs'
import { wireBytes } from './clientview-client.mjs'

export const PROJECTION_GOLDENS = process.env.WORMHOLES_CLIENTVIEW_GOLDENS
  ?? fileURLToPath(new URL('../../../../optics/src/test/resources/art/arcane/optics/stream/goldens/', import.meta.url))
export const EXTENSION_GOLDENS = process.env.WORMHOLES_CLIENTVIEW_EXTENSION_GOLDENS
  ?? fileURLToPath(new URL('../../../../core/src/test/resources/clientview/', import.meta.url))

export function goldenContent(projection = PROJECTION_GOLDENS, extensions = EXTENSION_GOLDENS) {
  const read = (name, directory = projection) => decodeS2C(Buffer.from(readFileSync(`${directory}/${name}.hex`, 'utf8').trim(), 'hex'), ALL_CAPS).message
  const palette = read('palette')
  const portal = read('portal')
  const begin = read('plate_begin_hashes')
  return {
    palette: { type: 'PALETTE', entries: [...palette.entries, { id: portal.geometry.blackoutState, state: 'minecraft:black_concrete' }] },
    portal,
    begin,
    bricks: read('plate_bricks').bricks,
    patch: read('plate_patch'),
    entityFrame: read('entity_frame'),
    fx: read('fx', extensions),
    atmosphere: read('atmosphere')
  }
}

function tally(target, type, bytes, wire = bytes) {
  target.frames++
  target.bytes += bytes
  target.wire += wire
  const entry = target.byType[type] ??= { frames: 0, bytes: 0, wire: 0 }
  entry.frames++
  entry.bytes += bytes
  entry.wire += wire
}

function stripLight(brick) {
  return { ...brick, flags: brick.flags & ~1, blockLight: null, skyLight: null }
}

function readBrand(data) {
  let length = 0
  let offset = 0
  for (let shift = 0; offset < data.length; shift += 7) {
    const byte = data[offset++]
    length |= (byte & 0x7f) << shift
    if ((byte & 0x80) === 0) break
  }
  return data.toString('utf8', offset, offset + length)
}

function registeredChannels(data) {
  return data.toString('utf8').split('\0').filter(Boolean)
}

export async function startFakeClientViewServer({ minecraftProtocol, minecraftData }, {
  version = '26.1',
  host = '127.0.0.1',
  handshake = 'config',
  mcDataVersion = 4325,
  serverCaps = ALL_CAPS,
  helloGraceMillis = 100,
  ackWindowFrames = 8,
  maxFrameBytes = DEFAULT_MAX_FRAME_BYTES,
  decline = null,
  faults = [],
  streamWhenNear = null,
  content = goldenContent()
} = {}) {
  const states = { CONFIGURATION: 'configuration', PLAY: 'play' }
  const loginPacket = minecraftData(version).loginPacket
  const server = minecraftProtocol.createServer({ 'online-mode': false, version, port: 0, host, hideErrors: true })
  await new Promise((resolve, reject) => {
    server.once('listening', resolve)
    server.once('error', reject)
  })
  const sessions = new Map()
  let nextSessionId = 1
  let nextTeleportId = 1

  const sessionFor = (client) => {
    let session = sessions.get(client.username)
    if (!session) {
      session = { username: client.username, brand: null, state: 'VANILLA', phase: null, hello: null, outcome: null, caps: 0n, seq: 0, acked: -1,
        sent: { frames: 0, bytes: 0, wire: 0, byType: {} }, received: { frames: 0, bytes: 0, wire: 0, byType: {} }, brickMisses: [], refusals: [], acks: [], errors: [], streamDone: false, waiters: [] }
      sessions.set(client.username, session)
    }
    return session
  }

  const send = (client, session, message, flags = 0) => {
    const payload = encodeS2C(message, session.seq, flags)
    session.seq = (session.seq + 1) >>> 0
    tally(session.sent, message.type, payload.length, wireBytes(payload))
    client.write('custom_payload', { channel: CHANNEL, data: payload })
    return session.seq - 1
  }

  const waitFor = (session, predicate, label, timeoutMillis = 5000) => new Promise((resolve, reject) => {
    const check = () => {
      const value = predicate()
      if (value) {
        resolve(value)
        return true
      }
      return false
    }
    if (check()) return
    const waiter = { check, timer: setTimeout(() => {
      session.waiters = session.waiters.filter((entry) => entry !== waiter)
      reject(new Error(`${session.username}: timed out waiting for ${label}`))
    }, timeoutMillis) }
    session.waiters.push(waiter)
  })

  const wake = (session) => {
    for (const waiter of [...session.waiters]) {
      if (waiter.check()) {
        clearTimeout(waiter.timer)
        session.waiters = session.waiters.filter((entry) => entry !== waiter)
      }
    }
  }

  const offer = (client, session, phase) => {
    session.state = 'OFFERED'
    session.phase = phase
    session.offeredAt = Date.now()
    send(client, session, { type: 'OFFER', wire: WIRE_VERSION, mcDataVersion, serverCaps, maxFrameBytes, zeroCopyNonce: 0n }, FLAG_LAST)
  }

  const answerHello = (client, session, hello) => {
    session.hello = summarize(hello)
    let reason = decline
    if (hello.wire !== WIRE_VERSION) reason = 'WIRE_MISMATCH'
    else if (hello.mcDataVersion !== mcDataVersion) reason = 'DATA_VERSION_MISMATCH'
    if (reason) {
      session.state = 'DECLINED'
      session.outcome = { outcome: 'DECLINE', reason }
      send(client, session, { type: 'DECLINE', reason }, FLAG_LAST)
      return
    }
    session.caps = serverCaps & hello.clientCaps & ~capabilityMask('ZERO_COPY')
    session.state = 'CLIENT_VIEW'
    const accept = { type: 'ACCEPT', sessionId: nextSessionId++, caps: session.caps, tickRate: 20, maxFrameBytes: clampMaxFrameBytes(Math.min(maxFrameBytes, hello.maxFrameBytes)),
      hashSalt: randomBytes(8).readBigInt64LE(0), ackWindowFrames }
    session.outcome = { outcome: 'ACCEPT', caps: capabilityNames(session.caps), handshakeMillis: Date.now() - session.offeredAt }
    send(client, session, accept, FLAG_LAST)
  }

  const receive = (client, session, payload) => {
    const type = peekType(payload) ?? 'UNKNOWN'
    tally(session.received, type, payload.length)
    let message
    try {
      message = decodeC2S(payload)
    } catch (error) {
      if (!(error instanceof ClientViewProtocolError)) throw error
      session.errors.push(`undecodable ${type}: ${error.message}`)
      return
    }
    if (message.type === 'HELLO') {
      if (session.state !== 'OFFERED') {
        session.errors.push(`HELLO in state ${session.state}`)
        return
      }
      answerHello(client, session, message)
      startStream(client, session)
    } else if (message.type === 'BRICK_MISS') {
      for (const plate of message.plates) session.brickMisses.push({ portalKey: plate.portalKey, plateRevision: plate.plateRevision, missed: brickMissIndices(plate.bitset) })
    } else if (message.type === 'PLATE_REFUSED') {
      session.refusals.push({ portalKey: message.portalKey, plateRevision: message.plateRevision })
    } else if (message.type === 'ACK') {
      if (message.seq >= session.seq) session.errors.push(`ACK for unsent seq ${message.seq}`)
      session.acks.push(message.seq)
      session.acked = Math.max(session.acked, message.seq)
    }
    wake(session)
  }

  const plate = async (client, session, portalKey, plateRevision) => {
    const cache = hasCapability(session.caps, 'BRICK_CACHE')
    const bricks = hasCapability(session.caps, 'DEST_LIGHT') ? content.bricks : content.bricks.map(stripLight)
    const begin = { ...content.begin, portalKey, plateRevision, brickHashes: cache ? content.begin.brickHashes : null }
    if (!cache) {
      send(client, session, begin)
      send(client, session, { type: 'PLATE_BRICKS', portalKey, plateRevision, bricks })
      return send(client, session, { type: 'PLATE_END', portalKey, plateRevision }, FLAG_LAST)
    }
    send(client, session, begin, FLAG_LAST)
    const miss = await waitFor(session, () => session.brickMisses.find((entry) => entry.portalKey === portalKey && entry.plateRevision === plateRevision), `BRICK_MISS ${portalKey}/${plateRevision}`)
    const requested = bricks.filter((brick) => miss.missed.includes(brick.brickIndex))
    if (requested.length > 0) send(client, session, { type: 'PLATE_BRICKS', portalKey, plateRevision, bricks: requested })
    return send(client, session, { type: 'PLATE_END', portalKey, plateRevision }, FLAG_LAST)
  }

  const acked = (session, seq) => waitFor(session, () => session.acked >= seq, `ACK ${seq}`)

  const startStream = (client, session) => {
    if (session.streamStarted || session.state !== 'CLIENT_VIEW' || client.state !== states.PLAY) return
    const position = session.position
    if (streamWhenNear && (!position || Math.hypot(position.x - streamWhenNear.x, position.y - streamWhenNear.y, position.z - streamWhenNear.z) > streamWhenNear.radius)) return
    session.streamStarted = true
    runStream(client, session)
  }

  const runStream = async (client, session) => {
    try {
      if (faults.includes('bricksBeforePortal')) send(client, session, { type: 'PLATE_BRICKS', portalKey: 9, plateRevision: 1, bricks: content.bricks.slice(0, 1) }, FLAG_LAST)
      send(client, session, content.palette)
      send(client, session, content.portal)
      await acked(session, await plate(client, session, content.portal.portalKey, content.begin.plateRevision))
      await acked(session, send(client, session, { ...content.patch, portalKey: content.portal.portalKey, fromRevision: content.begin.plateRevision, toRevision: content.begin.plateRevision + 1 }, FLAG_LAST))
      const second = { ...content.portal, portalKey: 8, geometryRevision: 1, geometry: { ...content.portal.geometry, originX: content.portal.geometry.originX + 8 } }
      send(client, session, second)
      await acked(session, await plate(client, session, second.portalKey, 1))
      if (hasCapability(session.caps, 'ENTITY_FRAMES')) send(client, session, { ...content.entityFrame, portalKey: content.portal.portalKey }, FLAG_LAST)
      if (hasCapability(session.caps, 'FX_EMITTERS')) send(client, session, { ...content.fx, portalKey: content.portal.portalKey }, FLAG_LAST)
      if (hasCapability(session.caps, 'ATMOSPHERE')) send(client, session, { ...content.atmosphere, portalKey: content.portal.portalKey }, FLAG_LAST)
      await acked(session, send(client, session, { type: 'PORTAL_DROP', portalKey: second.portalKey }, FLAG_LAST))
      session.streamDone = true
    } catch (error) {
      session.errors.push(error.message)
    }
  }

  const finishConfiguration = (client, session) => {
    if (session.configured) return
    session.configured = true
    clearTimeout(session.graceTimer)
    if (session.state === 'OFFERED') {
      session.state = 'VANILLA'
      session.outcome = { outcome: 'VANILLA' }
    }
    client.once('finish_configuration', () => {
      client.state = states.PLAY
      server.emit('playerJoin', client)
    })
    client.write('finish_configuration', {})
  }

  const onPayload = (client, packet) => {
    const session = sessionFor(client)
    if (packet.channel === 'minecraft:brand') {
      session.brand = readBrand(packet.data)
      if (client.state === states.CONFIGURATION && session.state === 'OFFERED' && session.brand.toLowerCase() === 'vanilla') finishConfiguration(client, session)
      return
    }
    if (packet.channel === 'minecraft:register' && client.state === states.PLAY) {
      session.registeredInPlay = registeredChannels(packet.data)
      if (handshake === 'play' && session.registeredInPlay.includes(CHANNEL) && session.state === 'VANILLA' && !session.outcome) offer(client, session, 'play')
      return
    }
    if (packet.channel !== CHANNEL) return
    receive(client, session, packet.data)
    if (client.state === states.CONFIGURATION && session.state !== 'OFFERED') finishConfiguration(client, session)
  }

  server.on('login', (client) => {
    client.removeAllListeners('login_acknowledged')
    client.on('custom_payload', (packet) => onPayload(client, packet))
    client.once('login_acknowledged', () => {
      client.state = states.CONFIGURATION
      const session = sessionFor(client)
      if (handshake === 'config') {
        client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from(`${CHANNEL}\0`) })
        offer(client, session, 'configuration')
      }
      for (const key of Object.keys(server.options.registryCodec)) client.write('registry_data', server.options.registryCodec[key])
      if (handshake !== 'config') {
        finishConfiguration(client, session)
        return
      }
      const grace = session.brand?.toLowerCase() === 'vanilla' ? 0 : helloGraceMillis
      session.graceTimer = setTimeout(() => finishConfiguration(client, session), grace)
    })
  })

  server.on('playerJoin', (client) => {
    const session = sessionFor(client)
    client.write('login', { ...loginPacket, entityId: client.id + 1 })
    client.write('update_health', { health: 20, food: 20, foodSaturation: 5 })
    startStream(client, session)
    if (session.state !== 'CLIENT_VIEW' && faults.includes('leakToVanilla')) {
      session.leakTimer = setTimeout(() => {
        if (!client.ended && session.state !== 'CLIENT_VIEW') send(client, session, content.palette, FLAG_LAST)
      }, 1000)
    }
    client.on('chat_command', (packet) => command(client, packet.command))
    client.on('chat_command_signed', (packet) => command(client, packet.command))
  })

  const command = (sender, text) => {
    const parts = text.trim().split(/\s+/)
    if (parts[0] !== 'tp' || parts.length < 5) return
    const [target, x, y, z, yaw = '0', pitch = '0'] = parts.slice(1)
    const recipients = Object.values(server.clients).filter((client) => client.state === states.PLAY
      && (target === '@a' || (target === '@s' ? client === sender : client.username?.toLowerCase() === target.toLowerCase())))
    for (const client of recipients) {
      client.write('position', { teleportId: nextTeleportId++, x: Number(x), y: Number(y), z: Number(z), dx: 0, dy: 0, dz: 0, yaw: Number(yaw), pitch: Number(pitch), flags: {} })
      const session = sessionFor(client)
      session.position = { x: Number(x), y: Number(y), z: Number(z) }
      startStream(client, session)
    }
  }

  return {
    port: server.socketServer.address().port,
    sessions,
    close: () => new Promise((resolve) => {
      for (const session of sessions.values()) {
        clearTimeout(session.graceTimer)
        clearTimeout(session.leakTimer)
        for (const waiter of session.waiters) clearTimeout(waiter.timer)
      }
      server.close()
      server.once('close', resolve)
    })
  }
}
