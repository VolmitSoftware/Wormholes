import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

import {
  ALL_CAPS,
  BRICK_CELLS,
  ClientViewProtocolError,
  FLAG_DEFLATED,
  FLAG_LAST,
  PALETTE_BACKING,
  PALETTE_OCCLUDED,
  apertureOpenCells,
  brickCellIndex,
  brickCellY,
  brickCells,
  brickMissBitset,
  brickMissIndices,
  capabilityNames,
  capabilitySet,
  decodeC2S,
  decodeS2C,
  encodeC2S,
  encodeS2C,
  hello,
  lightNibble,
  lightRuns,
  packBrick,
  sectionOfBrick
} from './clientview-codec.mjs'

const GOLDENS = process.env.WORMHOLES_CLIENTVIEW_GOLDENS
  ?? fileURLToPath(new URL('../../../../core/src/test/resources/clientview/', import.meta.url))
const HELLO_CAPS = capabilitySet('PLATES', 'BRICK_CACHE', 'DEST_LIGHT', 'ENTITY_FRAMES', 'VIEW_STATS')

function manifest() {
  return readFileSync(`${GOLDENS}/vectors.txt`, 'utf8').trim().split('\n').map((line) => {
    const [name, direction, caps, seq, flags] = line.trim().split(/\s+/)
    return { name, direction, caps: BigInt(`0x${caps}`), seq: Number(seq), flags: Number(flags), bytes: golden(name) }
  })
}

function golden(name) {
  return Buffer.from(readFileSync(`${GOLDENS}/${name}.hex`, 'utf8').trim(), 'hex')
}

function vector(name) {
  const found = manifest().find((entry) => entry.name === name)
  assert.ok(found, `vector ${name} is listed`)
  return found
}

function decodeVector(entry) {
  return entry.direction === 'S2C' ? decodeS2C(entry.bytes, entry.caps).message : decodeC2S(entry.bytes)
}

function fixtureBrickCells() {
  const cells = new Array(BRICK_CELLS)
  for (let i = 0; i < BRICK_CELLS; i++) {
    const y = brickCellY(i)
    cells[i] = y < 6 ? PALETTE_OCCLUDED : y < 8 ? PALETTE_BACKING : y === 8 ? 4 : (i % 97 === 0 ? 5 : 0)
  }
  return cells
}

function f32(value) {
  return Math.fround(value)
}

function portalGeometry(nested) {
  return {
    originX: 635, originY: 64, originZ: -4682, facing: 3, frontSide: true, quarterTurns: 0, mirror: false,
    apertureWidth: 3, apertureHeight: 3, apertureMask: [0x1efn],
    nearPlanePadding: 0.25, aperturePadding: 0.75, frustumCullingRatio: f32(1.2), depthBlocks: 64, recursionDepth: 1,
    blackoutPolicy: 1, blackoutState: 6, maskAirPolicy: 0, lightingPolicy: 1, fidelityFlags: 5, kind: 1,
    parentPortalKey: 0, targetIdentity: 0x7a7a7a7a7a7a7a7an, nested
  }
}

function randomSource(seed) {
  let state = seed >>> 0
  return () => {
    state ^= state << 13
    state ^= state >>> 17
    state ^= state << 5
    return (state >>> 0) / 4294967296
  }
}

describe('ClientView golden vectors', () => {
  it('lists every golden file in the manifest and nothing else', () => {
    const files = readdirSync(GOLDENS).filter((name) => name.endsWith('.hex')).map((name) => name.slice(0, -4)).sort()
    const listed = manifest().map((entry) => entry.name).sort()
    assert.deepEqual(listed, files)
  })

  it('decodes every golden with the envelope the manifest records and re-encodes it byte for byte', () => {
    for (const entry of manifest()) {
      if (entry.direction === 'S2C') {
        const frame = decodeS2C(entry.bytes, entry.caps)
        assert.equal(frame.type, entry.name.startsWith('plate_begin') ? 'PLATE_BEGIN' : entry.name.startsWith('portal_nested') ? 'PORTAL' : entry.name.startsWith('entity_frame') ? 'ENTITY_FRAME' : entry.name.startsWith('mesh_section') ? 'MESH_SECTION' : entry.name.toUpperCase(), entry.name)
        assert.equal(frame.seq, entry.seq, `${entry.name} seq`)
        assert.equal(frame.flags, entry.flags, `${entry.name} flags`)
        assert.equal(frame.last, (entry.flags & FLAG_LAST) !== 0, `${entry.name} last`)
        assert.equal(frame.bytes, entry.bytes.length)
        assert.deepEqual(encodeS2C(frame.message, frame.seq, frame.flags), entry.bytes, `${entry.name} re-encodes`)
      } else {
        const message = decodeC2S(entry.bytes)
        assert.equal(message.type, entry.name.toUpperCase(), entry.name)
        assert.deepEqual(encodeC2S(message), entry.bytes, `${entry.name} re-encodes`)
      }
    }
  })

  it('preserves all 512 biome halo samples and rejects malformed unsigned indices', () => {
    const entry = vector('mesh_section_biomes')
    const message = decodeVector(entry)
    assert.equal(message.biomes.palette.length, 512)
    assert.equal(message.biomes.indices.length, 1024)
    for (let cell = 0; cell < 512; cell++) {
      assert.equal(message.biomes.indices.readUInt16LE(cell * 2), cell)
      assert.equal(message.biomes.palette[cell], `test:biome_${cell}`)
    }
    const invalid = Buffer.from(entry.bytes)
    invalid.writeUInt16LE(512, invalid.length - 2)
    assert.throws(() => decodeS2C(invalid, ALL_CAPS), ClientViewProtocolError)
    assert.throws(() => decodeS2C(entry.bytes.subarray(0, entry.bytes.length - 1), ALL_CAPS), ClientViewProtocolError)
    const empty = decodeVector(vector('mesh_section'))
    empty.biomes = { palette: [], indices: Buffer.alloc(0) }
    const badCount = encodeS2C(empty, 0, 0)
    badCount.writeUInt16LE(513, badCount.length - 2)
    assert.throws(() => decodeS2C(badCount, ALL_CAPS), ClientViewProtocolError)
  })

  it('decodes the handshake fields', () => {
    const offer = decodeVector(vector('offer'))
    assert.deepEqual(offer, { type: 'OFFER', wire: 4, mcDataVersion: 4325, serverCaps: ALL_CAPS, maxFrameBytes: 524288, zeroCopyNonce: 0x1122334455667788n })
    assert.deepEqual(decodeVector(vector('hello')), { type: 'HELLO', wire: 4, mcDataVersion: 4325, clientCaps: HELLO_CAPS, maxFrameBytes: 524288, plateMemoryMb: 256, zeroCopyNonceEcho: 0x1122334455667788n, brandTag: 'fabric' })
    assert.deepEqual(decodeVector(vector('accept')), { type: 'ACCEPT', sessionId: 42, caps: HELLO_CAPS, tickRate: 20, maxFrameBytes: 524288, hashSalt: 0x0f1e2d3c4b5a6978n, ackWindowFrames: 8 })
    assert.deepEqual(decodeVector(vector('decline')), { type: 'DECLINE', reason: 'DATA_VERSION_MISMATCH' })
    assert.deepEqual(capabilityNames(HELLO_CAPS), ['PLATES', 'BRICK_CACHE', 'DEST_LIGHT', 'ENTITY_FRAMES', 'VIEW_STATS'])
  })

  it('builds the hand-encoded HELLO the Java client encoder produces', () => {
    const offer = decodeVector(vector('offer'))
    const message = hello({ offer, mcDataVersion: 4325, clientCaps: HELLO_CAPS, plateMemoryMb: 256, nonceFound: 0x1122334455667788n, brandTag: 'fabric' })
    assert.deepEqual(encodeC2S(message), vector('hello').bytes)
    assert.equal(hello({ offer, mcDataVersion: 4325, clientCaps: HELLO_CAPS, nonceFound: 5n, brandTag: 'fabric' }).zeroCopyNonceEcho, 0n)
  })

  it('decodes the session palette', () => {
    assert.deepEqual(decodeVector(vector('palette')).entries, [
      { id: 3, state: 'minecraft:stone' },
      { id: 4, state: 'minecraft:grass_block[snowy=false]' },
      { id: 5, state: 'minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]' }
    ])
  })

  it('decodes portal geometry including nested portals', () => {
    const portal = decodeVector(vector('portal'))
    assert.deepEqual(portal, { type: 'PORTAL', portalKey: 7, geometryRevision: 2, geometry: portalGeometry([]) })
    assert.equal(apertureOpenCells(portal.geometry), 8)
    const child = {
      originX: 2, originY: 0, originZ: 5, facing: 2, frontSide: false, quarterTurns: 1, mirror: true,
      apertureWidth: 2, apertureHeight: 2, apertureMask: [0xfn], nearPlanePadding: 0.25, aperturePadding: 0.5, frustumCullingRatio: 1,
      depthBlocks: 32, recursionDepth: 0, blackoutPolicy: 0, blackoutState: 0, maskAirPolicy: 1, lightingPolicy: 0, fidelityFlags: 0,
      kind: 0, parentPortalKey: 7, targetIdentity: 0n, nested: []
    }
    assert.deepEqual(decodeVector(vector('portal_nested')), { type: 'PORTAL', portalKey: 8, geometryRevision: 1, geometry: portalGeometry([child]) })
    assert.deepEqual(decodeVector(vector('portal_drop')), { type: 'PORTAL_DROP', portalKey: 7 })
  })

  it('decodes plate headers with and without the brick hash manifest', () => {
    const sections = { minSectionX: 37, minSectionY: 1, minSectionZ: -294, sizeX: 2, sizeY: 1, sizeZ: 2 }
    const cells = { minX: 595, minY: 22, minZ: -4700, sizeX: 25, sizeY: 16, sizeZ: 20 }
    assert.deepEqual(decodeVector(vector('plate_begin_hashes')), { type: 'PLATE_BEGIN', portalKey: 7, plateRevision: 3, sections, cells, backingState: 3, brickCount: 4, brickHashes: [1n, 2n, 3n, -1n] })
    assert.deepEqual(decodeVector(vector('plate_begin_plain')), { type: 'PLATE_BEGIN', portalKey: 7, plateRevision: 3, sections, cells, backingState: 3, brickCount: 4, brickHashes: null })
    assert.equal(decodeS2C(vector('plate_begin_plain').bytes, vector('plate_begin_hashes').caps).message.brickHashes, null)
    assert.deepEqual(decodeVector(vector('plate_end')), { type: 'PLATE_END', portalKey: 7, plateRevision: 3 })
    assert.deepEqual(sectionOfBrick(sections, 3), { x: 38, y: 1, z: -293 })
    assert.throws(() => decodeS2C(vector('plate_begin_hashes').bytes, vector('plate_begin_plain').caps), ClientViewProtocolError)
  })

  it('decodes every brick encoding, light nibbles and block entities', () => {
    const bricks = decodeVector(vector('plate_bricks'))
    assert.equal(bricks.portalKey, 7)
    assert.equal(bricks.plateRevision, 3)
    assert.deepEqual(bricks.bricks.map((brick) => [brick.brickIndex, brick.encoding, brick.bitsPerIndex, brick.flags]), [
      [0, 'EMPTY', 0, 0], [1, 'SINGLE', 0, 1], [2, 'PALETTED', 4, 0], [3, 'PALETTED', 4, 3]
    ])
    assert.equal(bricks.bricks[1].singlePaletteId, 3)
    const expected = fixtureBrickCells()
    assert.deepEqual([...brickCells(bricks.bricks[2])], expected)
    assert.deepEqual([...brickCells(bricks.bricks[3])], expected)
    const lit = bricks.bricks[3]
    for (let i = 0; i < BRICK_CELLS; i++) {
      assert.equal(lightNibble(lit.blockLight, i), (i * 7) & 15)
      assert.equal(lightNibble(lit.skyLight, i), brickCellY(i) >= 8 ? 15 : 0)
    }
    assert.deepEqual(lit.blockEntities, [{ cellIndex: brickCellIndex(3, 8, 3), payload: Buffer.from([10, 0, 0, 0]) }])
    const uniform = bricks.bricks[1]
    for (let i = 0; i < BRICK_CELLS; i++) {
      assert.equal(lightNibble(uniform.blockLight, i), 0)
      assert.equal(lightNibble(uniform.skyLight, i), 15)
    }
    assert.equal(uniform.bytes, 2 + 3 + 1 + 2 + 2)
    assert.deepEqual(lightRuns(lit.skyLight), [{ value: 0, length: BRICK_CELLS / 2 }, { value: 15, length: BRICK_CELLS / 2 }])
    const packed = packBrick(2, expected)
    assert.deepEqual([packed.encoding, packed.bitsPerIndex, packed.localPalette], ['PALETTED', 4, bricks.bricks[2].localPalette])
    assert.deepEqual(packed.packed, bricks.bricks[2].packed)
  })

  it('decodes plate patches, handles and brick misses', () => {
    const patch = decodeVector(vector('plate_patch'))
    assert.deepEqual([patch.portalKey, patch.fromRevision, patch.toRevision], [7, 3, 4])
    assert.deepEqual(patch.ops.map((op) => [op.op, op.brickIndex]), [['CLEAR', 0], ['SPARSE', 1], ['FULL', 2]])
    assert.deepEqual(patch.ops[1].cellIndices, [0, 17, 4095])
    assert.deepEqual(patch.ops[1].paletteIds, [4, 0, 5])
    assert.deepEqual([...brickCells(patch.ops[2].brick)], fixtureBrickCells())
    assert.deepEqual(decodeVector(vector('plate_handle')), { type: 'PLATE_HANDLE', portalKey: 7, plateRevision: 4, handle: 0x0123456789abcdefn })
    const miss = decodeVector(vector('brick_miss'))
    assert.deepEqual(miss.plates.map((plate) => [plate.portalKey, plate.plateRevision, brickMissIndices(plate.bitset)]), [[7, 3, [0, 2, 63, 64, 65]], [8, 1, [0]]])
    assert.deepEqual(encodeC2S({ type: 'BRICK_MISS', plates: [
      { portalKey: 7, plateRevision: 3, bitset: brickMissBitset(66, [0, 2, 63, 64, 65]) },
      { portalKey: 8, plateRevision: 1, bitset: brickMissBitset(1, [0]) }
    ] }), vector('brick_miss').bytes)
  })

  it('decodes entity frames, fx, atmosphere, resets and client telemetry', () => {
    const frame = decodeVector(vector('entity_frame'))
    assert.deepEqual([frame.portalKey, frame.entitySeq, frame.entities.length], [7, 77, 2])
    assert.equal(frame.presence, true)
    const delta = decodeVector(vector('entity_frame_delta'))
    assert.deepEqual([delta.portalKey, delta.entitySeq, delta.entities.length, delta.presentIds.length, delta.presence], [7, 78, 1, 0, false])
    assert.deepEqual(frame.presentIds, ['10000000-0000-0001-2000-000000000002', '30000000-0000-0003-4000-000000000004'])
    assert.deepEqual(decodeVector(vector('fx')).emitters, [
      { kind: 'RIM_DUST', key: 'minecraft:portal', x: 635.5, y: 64.5, z: -4681.5, paramA: 1, paramB: 0, ticks: 5, flags: 0 },
      { kind: 'SOUND', key: 'minecraft:block.portal.ambient', x: 636, y: 65, z: -4681, paramA: 0.5, paramB: f32(1.1), ticks: 0, flags: 1 },
      { kind: 'ANIMATION', key: '', x: 637.5, y: 66, z: -4686.5, paramA: 3, paramB: 4, ticks: 0, flags: 0x42 },
      { kind: 'BURST', key: 'minecraft:reverse_portal', x: 637.5, y: 66, z: -4686.5, paramA: f32(0.4), paramB: f32(0.6), ticks: 12, flags: 40 }
    ])
    assert.deepEqual(decodeVector(vector('atmosphere')), { type: 'ATMOSPHERE', portalKey: 7, dayTime: 18000n, rain: 0.25, thunder: 0, flags: 3 })
    assert.deepEqual(decodeVector(vector('session_reset')), { type: 'SESSION_RESET', reason: 'TELEPORT' })
    assert.deepEqual(decodeVector(vector('ack')), { type: 'ACK', seq: 13, clientTick: 400, appliedCells: 12345 })
    assert.deepEqual(decodeVector(vector('view_stats')), { type: 'VIEW_STATS', clientTick: 500, attended: 3, overlayCells: 250000, unknownStates: 2, sweepMicrosP50: 640, applyMicrosP50: 1900, plateMb: 96 })
    assert.deepEqual(decodeVector(vector('plate_refused')), { type: 'PLATE_REFUSED', portalKey: 7, plateRevision: 3 })
    assert.deepEqual(encodeC2S({ type: 'PLATE_REFUSED', portalKey: 7, plateRevision: 3 }), vector('plate_refused').bytes)
  })

  it('inflates DEFLATED frames', () => {
    const entry = vector('plate_bricks')
    const message = decodeVector(entry)
    const deflated = encodeS2C(message, 6, 0, { deflate: true })
    const frame = decodeS2C(deflated, entry.caps)
    assert.equal(frame.flags & FLAG_DEFLATED, FLAG_DEFLATED)
    assert.ok(deflated.length < entry.bytes.length)
    assert.deepEqual(encodeS2C(frame.message, 6, 0), entry.bytes)
  })

  it('rejects truncated, trailing and misdirected payloads with protocol errors', () => {
    for (const entry of manifest()) {
      const decode = entry.direction === 'S2C' ? (bytes) => decodeS2C(bytes, entry.caps) : decodeC2S
      assert.throws(() => decode(entry.bytes.subarray(0, entry.bytes.length - 1)), ClientViewProtocolError, `${entry.name} truncated`)
      assert.throws(() => decode(Buffer.concat([entry.bytes, Buffer.from([0])])), ClientViewProtocolError, `${entry.name} trailing`)
    }
    assert.throws(() => decodeC2S(vector('offer').bytes), ClientViewProtocolError)
    assert.throws(() => decodeS2C(vector('hello').bytes, ALL_CAPS), ClientViewProtocolError)
    assert.throws(() => decodeS2C(Buffer.from([99, 0, 0, 0, 0, 0]), ALL_CAPS), ClientViewProtocolError)
  })

  it('throws nothing but protocol errors for 10000 mutated goldens', () => {
    const random = randomSource(0x5eed)
    const entries = manifest()
    let decoded = 0
    for (let round = 0; round < 10000; round++) {
      const entry = entries[Math.floor(random() * entries.length)]
      const bytes = Buffer.from(entry.bytes)
      const mode = Math.floor(random() * 3)
      if (mode === 0) {
        for (let flips = 1 + Math.floor(random() * 4); flips > 0; flips--) bytes[Math.floor(random() * bytes.length)] ^= 1 << Math.floor(random() * 8)
      } else if (mode === 1) {
        bytes[Math.floor(random() * bytes.length)] = Math.floor(random() * 256)
      }
      const payload = mode === 2 ? bytes.subarray(0, Math.floor(random() * bytes.length)) : bytes
      try {
        if (entry.direction === 'S2C') decodeS2C(payload, entry.caps)
        else decodeC2S(payload)
        decoded++
      } catch (error) {
        if (!(error instanceof ClientViewProtocolError)) throw new Error(`${entry.name} mutation threw ${error.name}: ${error.message}`)
      }
    }
    assert.ok(decoded > 0)
  })
})
