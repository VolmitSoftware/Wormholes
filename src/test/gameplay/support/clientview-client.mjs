import { createRequire } from 'node:module'
import { deflateSync } from 'node:zlib'

import {
  ByteWriter,
  CHANNEL,
  ClientViewProtocolError,
  DEFAULT_MAX_FRAME_BYTES,
  HARD_MAX_FRAME_BYTES,
  MAX_C2S_MESSAGES_PER_SECOND,
  MIN_MAX_FRAME_BYTES,
  RESERVED_PALETTE_IDS,
  WIRE_VERSION,
  WORLD_FX_KEY,
  brickMissBitset,
  brickPaletteIds,
  capabilityNames,
  capabilitySet,
  decodeS2C,
  encodeC2S,
  hasCapability,
  hello,
  hex64,
  peekType,
  summarize
} from './clientview-codec.mjs'

export const DEFAULT_CLIENT_CAPS = capabilitySet('PLATES', 'BRICK_CACHE', 'DEST_LIGHT', 'ENTITY_FRAMES', 'FX_EMITTERS', 'ATMOSPHERE', 'CONFIG_PHASE')

const HANDSHAKE_TYPES = new Set(['OFFER', 'ACCEPT', 'DECLINE'])
const TERMINAL_RESETS = new Set(['DISABLED', 'PROTOCOL', 'OVERLOAD'])
const CAPABILITY_GATES = Object.freeze({
  PORTAL: 'PLATES',
  PORTAL_DROP: 'PLATES',
  PLATE_BEGIN: 'PLATES',
  PLATE_BRICKS: 'PLATES',
  PLATE_END: 'PLATES',
  PLATE_PATCH: 'PLATES',
  PLATE_HANDLE: 'ZERO_COPY',
  ENTITY_FRAME: 'ENTITY_FRAMES',
  ENTITY_EVENT: 'ENTITY_EVENTS',
  ENTITY_SELF: 'ENTITY_SELF',
  FX: 'FX_EMITTERS',
  ATMOSPHERE: 'ATMOSPHERE',
  MESH_BEGIN: 'MESH_RENDER',
  MESH_SECTION: 'MESH_RENDER',
  MESH_DROP: 'MESH_RENDER',
  MESH_REUSE: 'MESH_REUSE',
  TRAVEL_BEGIN: 'PREPARED_TRAVEL',
  TRAVEL_CHUNK: 'PREPARED_TRAVEL',
  TRAVEL_END: 'PREPARED_TRAVEL',
  TRAVEL_COMMIT: 'PREPARED_TRAVEL',
  TRAVEL_CANCEL: 'PREPARED_TRAVEL',
  TRAVEL_REUSE: 'PREPARED_TRAVEL_CACHE',
  REMOTE_LEVEL_OPEN: 'REMOTE_VIEW',
  REMOTE_LEVEL_CLOSE: 'REMOTE_VIEW',
  ROUTED_PACKET: 'REMOTE_VIEW',
  TRAVEL_ACCEPT: 'SEAMLESS_TRAVEL'
})
const MAX_VIOLATIONS = 200
const ACK_BUDGET_PER_SECOND = 10

export function loadHarnessModule(name) {
  const base = process.env.WORMHOLES_MINEFLAYER_HARNESS ? `${process.env.WORMHOLES_MINEFLAYER_HARNESS}/package.json` : process.argv[1]
  return createRequire(base)(name)
}

const VANILLA_COMPRESSION_THRESHOLD = 256

function counter() {
  return { frames: 0, bytes: 0, wire: 0, byType: {} }
}

export function wireBytes(payload) {
  return payload.length < VANILLA_COMPRESSION_THRESHOLD ? payload.length : deflateSync(payload).length
}

function count(target, type, bytes, wire = bytes) {
  target.frames++
  target.bytes += bytes
  target.wire += wire
  const entry = target.byType[type] ??= { frames: 0, bytes: 0, wire: 0 }
  entry.frames++
  entry.bytes += bytes
  entry.wire += wire
}

function copyCounter(source) {
  return { frames: source.frames, bytes: source.bytes, wire: source.wire, byType: Object.fromEntries(Object.entries(source.byType).map(([type, entry]) => [type, { ...entry }])) }
}

export class ClientViewPeer {
  constructor({ send, now = () => Date.now(), mcDataVersion = 'echo', caps = DEFAULT_CLIENT_CAPS, maxFrameBytes = DEFAULT_MAX_FRAME_BYTES,
    plateMemoryMb = 256, brandTag = 'fabric', cache = 'warm', ack = 'coalesced', ackIntervalMillis = 60, frameLogLimit = 5000, capture = false } = {}) {
    this.transport = send
    this.now = now
    this.settings = { mcDataVersion, caps: BigInt(caps), maxFrameBytes, plateMemoryMb, brandTag, cache, ack, ackIntervalMillis, frameLogLimit, capture }
    this.startedAt = now()
    this.state = 'IDLE'
    this.negotiations = []
    this.current = null
    this.caps = 0n
    this.maxFrameBytes = HARD_MAX_FRAME_BYTES
    this.s2c = counter()
    this.c2s = counter()
    this.phases = {}
    this.violations = []
    this.violationCount = 0
    this.log = []
    this.logDropped = 0
    this.captured = []
    this.marks = []
    this.plates = []
    this.groups = { count: 0, open: null, largestBytes: 0 }
    this.worldFx = {}
    this.resetSession()
    this.hashCache = new Set()
    this.pendingAckSeq = null
    this.ackTimer = null
    this.lastC2sAt = 0
    this.c2sWindow = []
    this.c2sPeakPerSecond = 0
    this.closed = false
  }

  resetSession() {
    this.palette = new Map([[0, 'minecraft:air'], [1, 'wormholes:occluded'], [2, 'wormholes:backing']])
    this.paletteIds = new Map([...this.palette].map(([id, state]) => [state, id]))
    this.nextPaletteId = RESERVED_PALETTE_IDS
    this.portals = new Map()
    this.openPlates = new Map()
    this.lastSeq = null
  }

  elapsed() {
    return this.now() - this.startedAt
  }

  violation(message) {
    this.violationCount++
    if (this.violations.length < MAX_VIOLATIONS) this.violations.push({ t: this.elapsed(), message })
  }

  mark(label) {
    const entry = { label, t: this.elapsed(), s2c: copyCounter(this.s2c), c2s: copyCounter(this.c2s), plates: this.plates.length }
    this.marks.push(entry)
    return entry
  }

  settled() {
    return this.state === 'CLIENT_VIEW' || this.state === 'DECLINED'
  }

  handle(payload, phase = 'play', packetBytes = payload.length) {
    const t = this.elapsed()
    const type = peekType(payload) ?? 'UNKNOWN'
    const wire = wireBytes(payload)
    count(this.s2c, type, payload.length, wire)
    const phaseCounter = this.phases[phase] ??= counter()
    count(phaseCounter, type, payload.length, wire)
    if (this.settings.capture) this.captured.push({ t, phase, dir: 'S2C', hex: Buffer.from(payload).toString('hex') })
    let frame
    try {
      frame = decodeS2C(payload, this.caps)
    } catch (error) {
      if (!(error instanceof ClientViewProtocolError)) throw error
      this.violation(`undecodable ${type} frame of ${payload.length} bytes: ${error.message}`)
      this.record({ t, phase, dir: 'S2C', type, bytes: payload.length, packetBytes, error: error.message })
      return
    }
    this.record({ t, phase, dir: 'S2C', type, seq: frame.seq, flags: frame.flags, bytes: payload.length, packetBytes, summary: summarize(frame.message) })
    this.trackGroup(frame, payload.length)
    this.checkEnvelope(frame, payload.length)
    this.apply(frame, phase, t)
    if (!frame.last || this.state !== 'CLIENT_VIEW') return
    if (this.settings.ack === 'coalesced') this.queueAck(frame.seq)
    else if (this.settings.ack === 'every') this.sendAck(frame.seq)
  }

  record(entry) {
    if (this.log.length < this.settings.frameLogLimit) this.log.push(entry)
    else this.logDropped++
  }

  trackGroup(frame, bytes) {
    if (HANDSHAKE_TYPES.has(frame.type)) return
    const group = this.groups.open ??= { frames: 0, bytes: 0 }
    group.frames++
    group.bytes += bytes
    if (frame.last) {
      this.groups.count++
      this.groups.largestBytes = Math.max(this.groups.largestBytes, group.bytes)
      this.groups.open = null
    }
  }

  checkEnvelope(frame, bytes) {
    if (HANDSHAKE_TYPES.has(frame.type)) return
    if (this.state !== 'CLIENT_VIEW') {
      this.violation(`${frame.type} arrived in session state ${this.state}`)
      return
    }
    if (bytes > this.maxFrameBytes) this.violation(`${frame.type} frame of ${bytes} bytes exceeds the negotiated ${this.maxFrameBytes}`)
    if (frame.deflated && !hasCapability(this.caps, 'LINK_UNCOMPRESSED')) this.violation(`${frame.type} frame is DEFLATED without LINK_UNCOMPRESSED`)
    const gate = CAPABILITY_GATES[frame.type]
    if (gate && !hasCapability(this.caps, gate)) this.violation(`${frame.type} sent without the ${gate} capability`)
    if (this.lastSeq !== null && ((frame.seq - this.lastSeq) >>> 0) === 0) this.violation(`seq ${frame.seq} repeated`)
    else if (this.lastSeq !== null && ((frame.seq - this.lastSeq) >>> 0) > 0x7fffffff) this.violation(`seq ${frame.seq} went backwards from ${this.lastSeq}`)
    this.lastSeq = frame.seq
  }

  apply(frame, phase, t) {
    const message = frame.message
    switch (message.type) {
      case 'OFFER': return this.onOffer(message, phase, t)
      case 'ACCEPT': return this.onAccept(message, phase, t)
      case 'DECLINE': return this.onDecline(message, phase, t)
      case 'PALETTE': return this.onPalette(message)
      case 'PORTAL': return this.onPortal(message, t)
      case 'PORTAL_DROP': return this.onPortalDrop(message)
      case 'PLATE_BEGIN': return this.onPlateBegin(message, t)
      case 'PLATE_BRICKS': return this.onPlateBricks(message)
      case 'PLATE_END': return this.onPlateEnd(message, t)
      case 'PLATE_PATCH': return this.onPlatePatch(message, frame.last)
      case 'FX':
        return message.portalKey === WORLD_FX_KEY ? this.onWorldFx(message) : this.requirePortal(message.type, message.portalKey)
      case 'ENTITY_FRAME':
      case 'ENTITY_EVENT':
      case 'ENVIRONMENT':
      case 'ATMOSPHERE':
        return this.requirePortal(message.type, message.portalKey)
      case 'SESSION_RESET': return this.onSessionReset(message, t)
      default: return undefined
    }
  }

  onOffer(offer, phase, t) {
    if (this.state === 'CLIENT_VIEW') this.violation(`OFFER arrived in ${phase} while a session is active`)
    if (this.state === 'OFFERED') this.violation(`second OFFER arrived in ${phase} before ACCEPT or DECLINE`)
    const mcDataVersion = this.settings.mcDataVersion === 'echo' ? offer.mcDataVersion : this.settings.mcDataVersion
    const reply = hello({ offer, mcDataVersion, clientCaps: this.settings.caps, maxFrameBytes: this.settings.maxFrameBytes, plateMemoryMb: this.settings.plateMemoryMb, brandTag: this.settings.brandTag })
    this.current = { phase, offerAt: t, offer: summarize(offer), offerCaps: offer.serverCaps, hello: summarize(reply), helloCaps: reply.clientCaps, helloAt: null }
    this.negotiations.push(this.current)
    if (offer.wire !== WIRE_VERSION) this.violation(`OFFER carries wire ${offer.wire}`)
    this.state = 'OFFERED'
    this.send(reply)
    this.current.helloAt = this.elapsed()
  }

  onAccept(accept, phase, t) {
    if (this.state !== 'OFFERED' || !this.current) {
      this.violation(`ACCEPT arrived in session state ${this.state}`)
      return
    }
    const allowed = this.current.offerCaps & this.current.helloCaps
    if ((accept.caps & ~allowed) !== 0n) this.violation(`ACCEPT grants caps ${capabilityNames(accept.caps & ~allowed).join(',')} outside OFFER and HELLO`)
    if (accept.maxFrameBytes < MIN_MAX_FRAME_BYTES || accept.maxFrameBytes > HARD_MAX_FRAME_BYTES || accept.maxFrameBytes > this.settings.maxFrameBytes) {
      this.violation(`ACCEPT negotiates maxFrameBytes ${accept.maxFrameBytes}`)
    }
    this.state = 'CLIENT_VIEW'
    this.caps = accept.caps
    this.maxFrameBytes = accept.maxFrameBytes
    this.hashCache.clear()
    Object.assign(this.current, { outcome: 'ACCEPT', outcomePhase: phase, outcomeAt: t, handshakeMillis: t - this.current.offerAt, accept: summarize(accept) })
  }

  onDecline(decline, phase, t) {
    if (this.state !== 'OFFERED' || !this.current) {
      this.violation(`DECLINE arrived in session state ${this.state}`)
      return
    }
    this.state = 'DECLINED'
    Object.assign(this.current, { outcome: 'DECLINE', outcomePhase: phase, outcomeAt: t, handshakeMillis: t - this.current.offerAt, reason: decline.reason })
  }

  onSessionReset(reset, t) {
    if (this.state !== 'CLIENT_VIEW') return
    const terminal = TERMINAL_RESETS.has(reset.reason)
    this.resetSession()
    if (terminal) {
      this.state = 'VANILLA'
      this.caps = 0n
      this.cancelAck()
      this.hashCache.clear()
    }
    if (this.current) {
      this.current.resets ??= []
      this.current.resets.push({ t, reason: reset.reason, terminal })
    }
  }

  onPalette(palette) {
    for (const entry of palette.entries) {
      const known = this.palette.get(entry.id)
      if (known !== undefined) {
        if (known !== entry.state) this.violation(`palette id ${entry.id} redefined from ${known} to ${entry.state}`)
        continue
      }
      const alias = this.paletteIds.get(entry.state)
      if (alias !== undefined && alias !== entry.id) this.violation(`palette state ${entry.state} is defined as both ${alias} and ${entry.id}`)
      this.palette.set(entry.id, entry.state)
      this.paletteIds.set(entry.state, entry.id)
      this.nextPaletteId = Math.max(this.nextPaletteId, entry.id + 1)
    }
  }

  knownPaletteId(id, where) {
    if (!this.palette.has(id)) this.violation(`${where} references palette id ${id} before PALETTE`)
  }

  onWorldFx(message) {
    for (const emitter of message.emitters) {
      const oneShot = emitter.kind === 'ANIMATION' || emitter.kind === 'BURST' || emitter.ticks === 0
      if (!oneShot) this.violation(`world FX carries a continuous ${emitter.kind} emitter`)
      this.worldFx[emitter.kind] = (this.worldFx[emitter.kind] ?? 0) + 1
    }
    return undefined
  }

  requirePortal(type, portalKey) {
    const portal = this.portals.get(portalKey)
    if (!portal) this.violation(`${type} for portal ${portalKey} before PORTAL`)
    return portal
  }

  onPortal(portal, t) {
    if (portal.portalKey === 0) this.violation('PORTAL uses the reserved key 0')
    this.knownPaletteId(portal.geometry.blackoutState, `PORTAL ${portal.portalKey} blackout state`)
    if (portal.geometry.nested.length > 0 && !hasCapability(this.caps, 'CLIENT_RECURSION')) this.violation(`PORTAL ${portal.portalKey} carries nested geometry without CLIENT_RECURSION`)
    const existing = this.portals.get(portal.portalKey)
    if (existing) {
      if (portal.geometryRevision <= existing.geometryRevision) this.violation(`PORTAL ${portal.portalKey} geometry revision ${portal.geometryRevision} does not advance ${existing.geometryRevision}`)
      existing.geometryRevision = portal.geometryRevision
      existing.geometry = summarize(portal)
      return
    }
    this.portals.set(portal.portalKey, { portalKey: portal.portalKey, geometryRevision: portal.geometryRevision, geometry: summarize(portal), announcedAt: t, plate: null })
  }

  onPortalDrop(drop) {
    if (!this.portals.delete(drop.portalKey)) this.violation(`PORTAL_DROP for unknown portal ${drop.portalKey}`)
    this.openPlates.delete(drop.portalKey)
  }

  onPlateBegin(begin, t) {
    const portal = this.requirePortal('PLATE_BEGIN', begin.portalKey)
    if (this.openPlates.has(begin.portalKey)) this.violation(`PLATE_BEGIN for portal ${begin.portalKey} while revision ${this.openPlates.get(begin.portalKey).plateRevision} is still open`)
    if (portal?.plate && begin.plateRevision <= portal.plate.plateRevision) this.violation(`PLATE_BEGIN revision ${begin.plateRevision} does not advance ${portal.plate.plateRevision} for portal ${begin.portalKey}`)
    this.knownPaletteId(begin.backingState, `PLATE_BEGIN ${begin.portalKey} backing state`)
    const expected = new Set()
    let requested = null
    if (begin.brickHashes) {
      requested = []
      const hashes = new Map()
      for (let index = 0; index < begin.brickCount; index++) {
        const key = hex64(begin.brickHashes[index])
        hashes.set(index, key)
        if (this.settings.cache === 'cold' || !this.hashCache.has(key)) requested.push(index)
      }
      for (const index of requested) expected.add(index)
      this.send({ type: 'BRICK_MISS', plates: [{ portalKey: begin.portalKey, plateRevision: begin.plateRevision, bitset: brickMissBitset(begin.brickCount, requested) }] })
      this.openPlates.set(begin.portalKey, { ...this.plateHeader(begin), t, hashes, expected, received: new Set(), bytes: 0, requested: requested.length })
      return
    }
    for (let index = 0; index < begin.brickCount; index++) expected.add(index)
    this.openPlates.set(begin.portalKey, { ...this.plateHeader(begin), t, hashes: null, expected, received: new Set(), bytes: 0, requested: null })
  }

  plateHeader(begin) {
    return { portalKey: begin.portalKey, plateRevision: begin.plateRevision, brickCount: begin.brickCount, sections: begin.sections, cells: begin.cells, backingState: begin.backingState }
  }

  onPlateBricks(bricks) {
    this.requirePortal('PLATE_BRICKS', bricks.portalKey)
    const open = this.openPlates.get(bricks.portalKey)
    if (!open || open.plateRevision !== bricks.plateRevision) {
      this.violation(`PLATE_BRICKS for portal ${bricks.portalKey} revision ${bricks.plateRevision} outside an open plate`)
      return
    }
    for (const brick of bricks.bricks) {
      const where = `brick ${brick.brickIndex} of portal ${bricks.portalKey} revision ${bricks.plateRevision}`
      if (brick.brickIndex >= open.brickCount) this.violation(`${where} is outside the ${open.brickCount}-brick box`)
      else if (!open.expected.has(brick.brickIndex)) this.violation(`${where} was not requested`)
      if (open.received.has(brick.brickIndex)) this.violation(`${where} arrived twice`)
      open.received.add(brick.brickIndex)
      open.bytes += brick.bytes
      if (brick.blockLight && !hasCapability(this.caps, 'DEST_LIGHT')) this.violation(`${where} carries light without DEST_LIGHT`)
      for (const id of brickPaletteIds(brick)) this.knownPaletteId(id, where)
      const hash = open.hashes?.get(brick.brickIndex)
      if (hash) this.hashCache.add(hash)
    }
  }

  onPlateEnd(end, t) {
    const portal = this.requirePortal('PLATE_END', end.portalKey)
    const open = this.openPlates.get(end.portalKey)
    if (!open || open.plateRevision !== end.plateRevision) {
      this.violation(`PLATE_END for portal ${end.portalKey} revision ${end.plateRevision} without PLATE_BEGIN`)
      return
    }
    this.openPlates.delete(end.portalKey)
    const missing = [...open.expected].filter((index) => !open.received.has(index))
    if (missing.length > 0) this.violation(`PLATE_END for portal ${end.portalKey} revision ${end.plateRevision} is missing ${missing.length} bricks`)
    const completed = { portalKey: end.portalKey, plateRevision: end.plateRevision, brickCount: open.brickCount, requested: open.requested, received: open.received.size,
      brickBytes: open.bytes, millis: t - open.t, t, s2cBytes: this.s2c.bytes, s2cWire: this.s2c.wire, sections: open.sections }
    this.plates.push(completed)
    if (portal) {
      portal.plate = { plateRevision: end.plateRevision, brickCount: open.brickCount }
      portal.firstPlateAt ??= t
    }
  }

  onPlatePatch(patch, last) {
    const portal = this.requirePortal('PLATE_PATCH', patch.portalKey)
    if (!portal) return
    if (!portal.plate) {
      this.violation(`PLATE_PATCH for portal ${patch.portalKey} before a complete plate`)
      return
    }
    if (patch.fromRevision !== portal.plate.plateRevision) this.violation(`PLATE_PATCH for portal ${patch.portalKey} starts at ${patch.fromRevision} but the plate is at ${portal.plate.plateRevision}`)
    if (last && patch.toRevision <= patch.fromRevision) this.violation(`PLATE_PATCH for portal ${patch.portalKey} does not advance ${patch.fromRevision} -> ${patch.toRevision}`)
    if (!last && patch.toRevision !== patch.fromRevision) this.violation(`PLATE_PATCH for portal ${patch.portalKey} advances ${patch.fromRevision} -> ${patch.toRevision} before its group closes`)
    for (const op of patch.ops) {
      const where = `PLATE_PATCH brick ${op.brickIndex} of portal ${patch.portalKey}`
      if (op.brickIndex >= portal.plate.brickCount) this.violation(`${where} is outside the ${portal.plate.brickCount}-brick box`)
      if (op.op === 'FULL') for (const id of brickPaletteIds(op.brick)) this.knownPaletteId(id, where)
      if (op.op === 'SPARSE') for (const id of op.paletteIds) this.knownPaletteId(id, where)
    }
    portal.plate.plateRevision = patch.toRevision
    if (last) portal.patches = (portal.patches ?? 0) + 1
  }

  queueAck(seq) {
    this.pendingAckSeq = seq
    if (this.ackTimer) return
    const now = this.now()
    this.trimC2sWindow(now)
    const spacing = this.lastC2sAt + this.settings.ackIntervalMillis - now
    const budget = this.c2sWindow.length >= ACK_BUDGET_PER_SECOND ? this.c2sWindow[this.c2sWindow.length - ACK_BUDGET_PER_SECOND] + 1000 - now : 0
    const wait = Math.max(spacing, budget)
    if (wait <= 0) {
      this.flushAck()
      return
    }
    this.ackTimer = setTimeout(() => {
      this.ackTimer = null
      this.flushAck()
    }, wait + 1)
  }

  flushAck() {
    if (this.pendingAckSeq === null || this.closed || this.state !== 'CLIENT_VIEW') return
    const seq = this.pendingAckSeq
    this.pendingAckSeq = null
    this.sendAck(seq)
  }

  sendAck(seq) {
    this.send({ type: 'ACK', seq, clientTick: Math.floor(this.elapsed() / 50) >>> 0, appliedCells: 0 })
  }

  trimC2sWindow(now) {
    while (this.c2sWindow.length > 0 && this.c2sWindow[0] <= now - 1000) this.c2sWindow.shift()
  }

  cancelAck() {
    if (this.ackTimer) clearTimeout(this.ackTimer)
    this.ackTimer = null
    this.pendingAckSeq = null
  }

  send(message) {
    if (this.closed) return
    const payload = encodeC2S(message)
    const now = this.now()
    this.lastC2sAt = now
    this.c2sWindow.push(now)
    this.trimC2sWindow(now)
    if (this.c2sWindow.length > MAX_C2S_MESSAGES_PER_SECOND && this.c2sPeakPerSecond <= MAX_C2S_MESSAGES_PER_SECOND) {
      this.violation(`client sent more than ${MAX_C2S_MESSAGES_PER_SECOND} messages in one second`)
    }
    this.c2sPeakPerSecond = Math.max(this.c2sPeakPerSecond, this.c2sWindow.length)
    count(this.c2s, message.type, payload.length)
    this.record({ t: this.elapsed(), dir: 'C2S', type: message.type, bytes: payload.length, summary: summarize(message) })
    if (this.settings.capture) this.captured.push({ t: this.elapsed(), dir: 'C2S', hex: payload.toString('hex') })
    this.transport(payload)
  }

  close() {
    this.closed = true
    this.cancelAck()
  }

  report() {
    return {
      state: this.state,
      caps: capabilityNames(this.caps),
      maxFrameBytes: this.state === 'CLIENT_VIEW' ? this.maxFrameBytes : undefined,
      negotiations: this.negotiations.map(({ offerCaps, helloCaps, ...rest }) => rest),
      s2c: this.s2c,
      c2s: this.c2s,
      c2sPeakPerSecond: this.c2sPeakPerSecond,
      phases: this.phases,
      groups: { count: this.groups.count, open: this.groups.open, largestBytes: this.groups.largestBytes },
      worldFx: { ...this.worldFx },
      palette: { entries: this.palette.size - RESERVED_PALETTE_IDS, nextId: this.nextPaletteId },
      portals: [...this.portals.values()],
      openPlates: [...this.openPlates.values()].map(({ hashes, expected, received, ...rest }) => ({ ...rest, expected: expected.size, received: received.size })),
      plates: this.plates,
      brickCache: { mode: this.settings.cache, hashes: this.hashCache.size },
      marks: this.marks,
      violations: this.violations,
      violationCount: this.violationCount,
      frames: this.log,
      framesDropped: this.logDropped
    }
  }
}

export function attachPacketCounters(bot) {
  const counters = { counting: false, startedAt: null, stoppedAt: null, packets: {}, bytes: {}, totalPackets: 0, totalBytes: 0, socketBytes: 0, wormholes: counter(), playWormholes: counter() }
  const onPacket = (data, meta, buffer) => {
    if (meta.name === 'custom_payload' && data?.channel === CHANNEL) {
      const type = peekType(data.data) ?? 'UNKNOWN'
      if (meta.state === 'play') count(counters.playWormholes, type, data.data.length)
      if (counters.counting) count(counters.wormholes, type, data.data.length)
    }
    if (!counters.counting || meta.state !== 'play') return
    const length = buffer?.length ?? 0
    const name = meta.name === 'custom_payload' ? `custom_payload[${data?.channel}]` : meta.name
    counters.packets[name] = (counters.packets[name] ?? 0) + 1
    counters.bytes[name] = (counters.bytes[name] ?? 0) + length
    counters.totalPackets++
    counters.totalBytes += length
  }
  const onData = (chunk) => {
    if (counters.counting) counters.socketBytes += chunk.length
  }
  let socket = null
  const attachSocket = () => {
    if (socket || !bot._client.socket) return
    socket = bot._client.socket
    socket.on('data', onData)
  }
  bot._client.on('packet', onPacket)
  bot._client.on('connect', attachSocket)
  attachSocket()
  counters.begin = () => {
    attachSocket()
    counters.counting = true
    counters.startedAt = Date.now()
  }
  counters.end = () => {
    counters.counting = false
    counters.stoppedAt = Date.now()
  }
  counters.detach = () => {
    bot._client.removeListener('packet', onPacket)
    bot._client.removeListener('connect', attachSocket)
    socket?.removeListener('data', onData)
  }
  counters.snapshot = () => {
    const seconds = Math.max(0.001, ((counters.stoppedAt ?? Date.now()) - (counters.startedAt ?? Date.now())) / 1000)
    return {
      seconds,
      packets: counters.packets,
      bytes: counters.bytes,
      totalPackets: counters.totalPackets,
      totalBytes: counters.totalBytes,
      socketBytes: counters.socketBytes,
      wormholes: counters.wormholes,
      playWormholes: counters.playWormholes
    }
  }
  return counters
}

export function installMovementFilter(bot) {
  const write = bot._client.write.bind(bot._client)
  let replyBudget = 0
  bot._client.prependListener('position', () => { replyBudget += 1 })
  bot._client.write = (name, data) => {
    if (name === 'position' || name === 'look' || name === 'flying') return undefined
    if (name === 'position_look') {
      if (replyBudget <= 0) return undefined
      replyBudget -= 1
    }
    return write(name, data)
  }
}

function brandPayload(brand) {
  const writer = new ByteWriter(brand.length + 5)
  writer.string(brand)
  return writer.toBuffer()
}

export function createClientViewBot(mineflayer, { host, port, username, version, brand = 'fabric', register = true, freeze = true, peer = {} }) {
  const bot = mineflayer.createBot({ host, port, username, version, auth: 'offline', brand, hideErrors: true, logErrors: false })
  if (freeze) {
    bot.physicsEnabled = false
    installMovementFilter(bot)
  }
  const client = bot._client
  const clientView = new ClientViewPeer({ ...peer, brandTag: peer.brandTag ?? brand, send: (payload) => client.write('custom_payload', { channel: CHANNEL, data: payload }) })
  const registration = { configuration: 0, play: 0 }
  const announce = (phase) => {
    if (!register) return
    client.writeChannel('minecraft:register', [CHANNEL])
    registration[phase]++
  }
  client.on('state', (state) => {
    if (state !== 'configuration') return
    client.write('custom_payload', { channel: 'minecraft:brand', data: brandPayload(brand) })
    announce('configuration')
  })
  client.once('login', () => announce('play'))
  client.on('packet', (data, meta, buffer) => {
    if (meta.name !== 'custom_payload' || data?.channel !== CHANNEL) return
    clientView.handle(data.data, meta.state, buffer?.length ?? data.data.length)
  })
  const counters = attachPacketCounters(bot)
  const spawned = new Promise((resolve, reject) => {
    const fail = (error) => reject(error instanceof Error ? error : new Error(`${username}: ${String(error)}`))
    bot.once('spawn', () => {
      bot.removeListener('error', fail)
      bot.removeListener('end', fail)
      bot.removeListener('kicked', kicked)
      resolve()
    })
    const kicked = (reason) => reject(new Error(`${username} kicked before spawn: ${JSON.stringify(reason)}`))
    bot.once('error', fail)
    bot.once('end', fail)
    bot.once('kicked', kicked)
  })
  spawned.catch(() => {})
  return { bot, peer: clientView, counters, registration, spawned }
}

export async function closeBot(bot, timeoutMillis = 3000) {
  if (!bot || bot._client.ended) return
  await new Promise((resolve) => {
    const timer = setTimeout(() => {
      bot.end('ClientView harness cleanup')
      resolve()
    }, timeoutMillis)
    bot.once('end', () => {
      clearTimeout(timer)
      resolve()
    })
    bot.quit('ClientView harness complete')
  })
}
