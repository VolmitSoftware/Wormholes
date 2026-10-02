import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { ClientViewPeer } from './clientview-client.mjs'
import { ALL_CAPS, FLAG_LAST, WORLD_FX_KEY, brickMissIndices, capabilitySet, decodeC2S, encodeS2C } from './clientview-codec.mjs'
import { goldenContent } from './clientview-fake-server.mjs'

const CONTENT = goldenContent()
const CAPS = capabilitySet('PLATES', 'BRICK_CACHE', 'DEST_LIGHT', 'ENTITY_FRAMES')

function harness(t, peerOptions = {}) {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const clock = { now: 0 }
  const sent = []
  const peer = new ClientViewPeer({ send: (payload) => sent.push(decodeC2S(payload)), now: () => clock.now, caps: CAPS, ...peerOptions })
  let seq = 0
  const frame = (message, flags = 0, phase = 'play') => peer.handle(encodeS2C(message, seq++, flags), phase)
  const advance = (millis) => {
    clock.now += millis
    t.mock.timers.tick(millis)
  }
  const accept = (caps = CAPS) => {
    frame({ type: 'OFFER', wire: 3, mcDataVersion: 4325, serverCaps: ALL_CAPS, maxFrameBytes: 524288, zeroCopyNonce: 0n }, FLAG_LAST, 'configuration')
    frame({ type: 'ACCEPT', sessionId: 1, caps, tickRate: 20, maxFrameBytes: 524288, hashSalt: 9n, ackWindowFrames: 8 }, FLAG_LAST, 'configuration')
  }
  return { peer, sent, frame, advance, accept, setSeq: (value) => { seq = value } }
}

function messages(peer) {
  return peer.violations.map((violation) => violation.message)
}

function plate(frame, portalKey, plateRevision, bricks = CONTENT.bricks) {
  frame({ ...CONTENT.begin, portalKey, plateRevision }, FLAG_LAST)
  frame({ type: 'PLATE_BRICKS', portalKey, plateRevision, bricks })
  frame({ type: 'PLATE_END', portalKey, plateRevision }, FLAG_LAST)
}

describe('ClientViewPeer', () => {
  it('answers OFFER with a HELLO echoing the server data version and records the ACCEPT', (t) => {
    const { peer, sent, accept } = harness(t)
    accept()
    assert.equal(peer.state, 'CLIENT_VIEW')
    assert.deepEqual(sent.map((message) => [message.type, message.mcDataVersion, message.clientCaps]), [['HELLO', 4325, CAPS]])
    assert.equal(peer.negotiations[0].outcome, 'ACCEPT')
    assert.equal(peer.negotiations[0].phase, 'configuration')
    assert.equal(peer.violationCount, 0)
  })

  it('flags an ACCEPT that grants capabilities the client never asked for', (t) => {
    const { peer, accept } = harness(t)
    accept(CAPS | capabilitySet('ZERO_COPY'))
    assert.match(messages(peer)[0], /ZERO_COPY outside OFFER and HELLO/)
  })

  it('accepts a clean golden stream and requests only uncached bricks', (t) => {
    const { peer, sent, frame, accept } = harness(t)
    accept()
    frame(CONTENT.palette)
    frame(CONTENT.portal)
    plate(frame, 7, 3)
    frame({ ...CONTENT.patch, portalKey: 7, fromRevision: 3, toRevision: 4 }, FLAG_LAST)
    frame({ ...CONTENT.portal, portalKey: 8, geometryRevision: 1 })
    frame({ ...CONTENT.begin, portalKey: 8, plateRevision: 1 }, FLAG_LAST)
    frame({ type: 'PLATE_END', portalKey: 8, plateRevision: 1 }, FLAG_LAST)
    frame({ ...CONTENT.entityFrame, portalKey: 7 }, FLAG_LAST)
    frame({ type: 'PORTAL_DROP', portalKey: 8 }, FLAG_LAST)
    assert.deepEqual(messages(peer), [])
    assert.deepEqual(sent.filter((message) => message.type === 'BRICK_MISS').flatMap((message) => message.plates.map((plate) => brickMissIndices(plate.bitset))), [[0, 1, 2, 3], []])
    assert.deepEqual(peer.plates.map((entry) => [entry.portalKey, entry.plateRevision, entry.received]), [[7, 3, 4], [8, 1, 0]])
    assert.equal(peer.report().portals[0].plate.plateRevision, 4)
  })

  it('requests every brick again with a cold cache', (t) => {
    const { sent, frame, accept } = harness(t, { cache: 'cold' })
    accept()
    frame(CONTENT.palette)
    frame(CONTENT.portal)
    plate(frame, 7, 3)
    frame({ ...CONTENT.begin, portalKey: 7, plateRevision: 4 }, FLAG_LAST)
    assert.deepEqual(brickMissIndices(sent.filter((message) => message.type === 'BRICK_MISS').at(-1).plates[0].bitset), [0, 1, 2, 3])
  })

  it('flags ordering, palette, revision and capability violations', (t) => {
    const { peer, frame, accept, setSeq } = harness(t)
    accept()
    frame({ type: 'PLATE_BRICKS', portalKey: 9, plateRevision: 1, bricks: CONTENT.bricks.slice(0, 1) })
    frame({ type: 'PALETTE', entries: [{ id: 5, state: 'minecraft:stone' }, { id: 6, state: 'minecraft:stone' }, { id: 1, state: 'minecraft:dirt' }] })
    frame(CONTENT.portal)
    plate(frame, 7, 3)
    frame({ ...CONTENT.patch, portalKey: 7, fromRevision: 2, toRevision: 4 }, FLAG_LAST)
    frame(CONTENT.fx, FLAG_LAST)
    setSeq(3)
    frame({ type: 'PORTAL_DROP', portalKey: 7 }, FLAG_LAST)
    frame({ type: 'SESSION_RESET', reason: 'DISABLED' }, FLAG_LAST)
    frame(CONTENT.palette)
    const found = messages(peer)
    for (const pattern of [/PLATE_BRICKS for portal 9 before PORTAL/, /minecraft:stone is defined as both 5 and 6/, /palette id 1 redefined from wormholes:occluded to minecraft:dirt/, /references palette id 4 before PALETTE/,
      /starts at 2 but the plate is at 3/, /FX sent without the FX_EMITTERS capability/, /went backwards/, /PALETTE arrived in session state VANILLA/]) {
      assert.ok(found.some((message) => pattern.test(message)), `${pattern} in ${JSON.stringify(found)}`)
    }
  })

  it('accepts world FX one-shots without a PORTAL and flags continuous emitters there', (t) => {
    const fxCaps = CAPS | capabilitySet('FX_EMITTERS')
    const { peer, frame, accept } = harness(t, { caps: fxCaps })
    accept(fxCaps)
    const oneShots = CONTENT.fx.emitters.filter((emitter) => emitter.kind === 'ANIMATION' || emitter.kind === 'BURST' || emitter.ticks === 0)
    frame({ type: 'FX', portalKey: WORLD_FX_KEY, emitters: oneShots }, FLAG_LAST)
    assert.deepEqual(messages(peer), [])
    assert.deepEqual(peer.report().worldFx, { SOUND: 1, ANIMATION: 1, BURST: 1 })
    frame({ type: 'FX', portalKey: WORLD_FX_KEY, emitters: CONTENT.fx.emitters.slice(0, 1) }, FLAG_LAST)
    assert.match(messages(peer)[0], /world FX carries a continuous RIM_DUST emitter/)
  })

  it('keeps the session through a teleport reset and accepts the restreamed scope', (t) => {
    const { peer, frame, accept } = harness(t)
    accept()
    frame(CONTENT.palette)
    frame(CONTENT.portal)
    plate(frame, 7, 3)
    frame({ type: 'SESSION_RESET', reason: 'TELEPORT' }, FLAG_LAST)
    assert.equal(peer.state, 'CLIENT_VIEW')
    assert.equal(peer.portals.size, 0)
    frame(CONTENT.palette)
    frame({ ...CONTENT.portal, portalKey: 8 })
    plate(frame, 8, 1, [])
    assert.deepEqual(messages(peer), [])
    assert.equal(peer.plates.at(-1).requested, 0)
    assert.deepEqual(peer.negotiations[0].resets.map((reset) => [reset.reason, reset.terminal]), [['TELEPORT', false]])
  })

  it('ends the session on a terminal reset', (t) => {
    const { peer, frame, accept } = harness(t)
    accept()
    frame({ type: 'SESSION_RESET', reason: 'PROTOCOL' }, FLAG_LAST)
    assert.equal(peer.state, 'VANILLA')
    assert.equal(peer.caps, 0n)
  })

  it('keeps every ACK inside the budget while LAST frames arrive every 10 ms', (t) => {
    const { peer, sent, frame, advance, accept } = harness(t, { caps: capabilitySet('PLATES', 'ATMOSPHERE') })
    accept(capabilitySet('PLATES', 'ATMOSPHERE'))
    frame(CONTENT.palette)
    frame(CONTENT.portal, FLAG_LAST)
    let lastSeq = 0
    for (let i = 0; i < 300; i++) {
      frame({ ...CONTENT.atmosphere, portalKey: 7 }, FLAG_LAST)
      lastSeq = peer.lastSeq
      advance(10)
    }
    advance(2000)
    const acks = sent.filter((message) => message.type === 'ACK')
    assert.ok(peer.c2sPeakPerSecond <= 10, `peak ${peer.c2sPeakPerSecond}`)
    assert.equal(acks.at(-1).seq, lastSeq)
    assert.ok(acks.length >= 25 && acks.length <= 45, `${acks.length} ACKs`)
    assert.deepEqual(messages(peer), [])
  })
})
