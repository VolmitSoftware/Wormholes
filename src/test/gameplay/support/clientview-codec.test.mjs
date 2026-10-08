import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
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
import { EXTENSION_GOLDENS, PROJECTION_GOLDENS } from './clientview-fake-server.mjs'

const GOLDEN_DIRECTORIES = [PROJECTION_GOLDENS, EXTENSION_GOLDENS]
const HELLO_CAPS = capabilitySet('PLATES', 'BRICK_CACHE', 'DEST_LIGHT', 'ENTITY_FRAMES', 'VIEW_STATS')
const TYPE_ALIASES = Object.freeze({
  portal_nested: 'PORTAL',
  plate_begin_hashes: 'PLATE_BEGIN',
  plate_begin_plain: 'PLATE_BEGIN',
  entity_swing: 'ENTITY_EVENT',
  entity_hurt: 'ENTITY_EVENT',
  entity_frame_delta: 'ENTITY_FRAME',
  mesh_section_biomes: 'MESH_SECTION',
  travel_begin_seamless: 'TRAVEL_BEGIN'
})
const TEST_UUID = '00000000-0000-000c-0000-000000000022'

function manifest() {
  return GOLDEN_DIRECTORIES.flatMap((directory) => readFileSync(`${directory}/vectors.txt`, 'utf8').trim().split('\n').map((line) => {
    const [name, direction, caps, seq, flags] = line.trim().split(/\s+/)
    return { name, directory, direction, caps: BigInt(`0x${caps}`), seq: Number(seq), flags: Number(flags), bytes: golden(directory, name) }
  }))
}

function golden(directory, name) {
  return Buffer.from(readFileSync(`${directory}/${name}.hex`, 'utf8').trim(), 'hex')
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

const FULL_SHAPE = Buffer.alloc(0)
const CIRCLE_SHAPE = Buffer.from('00020000803f0000803f', 'hex')

function portalGeometry(nested, shape) {
  return {
    originX: 635, originY: 64, originZ: -4682, facing: 3, frontSide: true, quarterTurns: 0, mirror: false,
    apertureWidth: 3, apertureHeight: 3, apertureMask: [0x1efn], shape,
    nearPlanePadding: 0.25, aperturePadding: 0.75, frustumCullingRatio: f32(1.2), depthBlocks: 64, recursionDepth: 1,
    blackoutPolicy: 1, blackoutState: 6, maskAirPolicy: 0, lightingPolicy: 1, fidelityFlags: 5, kind: 1, planeOffset: 0,
    parentPortalKey: 0, targetIdentity: 0x7a7a7a7a7a7a7a7an, nested
  }
}

function fixtureEnvironment(dimensionKey, transform, scale = 1) {
  const color = { red: 0.125, green: 0.5, blue: 1.25 }
  const alpha = { red: 0.75, green: 0.5, blue: 0.25, alpha: 0.5 }
  return {
    gameTime: 18000n,
    sky: { skybox: 'OVERWORLD', sunAngle: 1.5, moonAngle: 2.5, starAngle: 3.5, starBrightness: f32(0.8), sunrise: alpha, color, moonPhase: 5, rain: 0.25, thunder: 0.5 },
    fog: { color, start: -8, end: 96, skyEnd: 512, cloudEnd: 256, waterColor: color, waterStart: 0, waterEnd: 32 },
    lighting: { blockTint: color, skyFactor: 0.75, skyColor: color, ambient: color },
    clouds: { color: alpha, height: 192 },
    transform,
    scale,
    dimension: { minY: -64, height: 384, hasSkyLight: true, cardinalLighting: 'DEFAULT', horizonHeight: 63, endFlashes: false },
    world: { dimensionKey, clockTime: 72000n, biomeKey: 'minecraft:plains', seaLevel: 63, blockLight: 7, skyLight: 15, logicalHeight: 256, hasCeiling: true,
      ambientLight: f32(0.1), eyeMedium: 'WATER', hasFixedTime: true }
  }
}

const IDENTITY_TRANSFORM = { permutation: 0, translation: { x: 0, y: 0, z: 0 } }
const TRAVEL_WORLD = { dimension: 'minecraft:overworld', dimensionType: 'minecraft:overworld', seed: 123456789n, debug: false, flat: true, seaLevel: 63, minY: -64, height: 384 }
const TRAVEL_ARRIVAL = { x: -511.5, y: 81, z: -159.5, yaw: 90, pitch: -12 }
const TRAVEL_HASH = Buffer.from(Array.from({ length: 32 }, (_, index) => index))

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
  it('lists every golden file in the manifests and nothing else', () => {
    for (const directory of GOLDEN_DIRECTORIES) {
      const files = readdirSync(directory).filter((name) => name.endsWith('.hex')).map((name) => name.slice(0, -4)).sort()
      const listed = manifest().filter((entry) => entry.directory === directory).map((entry) => entry.name).sort()
      assert.deepEqual(listed, files, directory)
    }
  })

  it('decodes every golden with the envelope the manifest records and re-encodes it byte for byte', () => {
    for (const entry of manifest()) {
      if (entry.direction === 'S2C') {
        const frame = decodeS2C(entry.bytes, entry.caps)
        assert.equal(frame.type, TYPE_ALIASES[entry.name] ?? entry.name.toUpperCase(), entry.name)
        assert.equal(frame.seq, entry.seq, `${entry.name} seq`)
        assert.equal(frame.flags, entry.flags, `${entry.name} flags`)
        assert.equal(frame.last, (entry.flags & FLAG_LAST) !== 0, `${entry.name} last`)
        assert.equal(frame.bytes, entry.bytes.length)
        assert.deepEqual(encodeS2C(frame.message, frame.seq, frame.flags), entry.bytes, `${entry.name} re-encodes`)
      } else {
        const message = decodeC2S(entry.bytes)
        assert.equal(message.type, TYPE_ALIASES[entry.name] ?? entry.name.toUpperCase(), entry.name)
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
    assert.deepEqual(offer, { type: 'OFFER', wire: 7, mcDataVersion: 4325, serverCaps: ALL_CAPS, maxFrameBytes: 524288, zeroCopyNonce: 0x1122334455667788n })
    assert.deepEqual(decodeVector(vector('hello')), { type: 'HELLO', wire: 7, mcDataVersion: 4325, clientCaps: HELLO_CAPS, maxFrameBytes: 524288, plateMemoryMb: 256, zeroCopyNonceEcho: 0x1122334455667788n, brandTag: 'fabric' })
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
    assert.deepEqual(portal, { type: 'PORTAL', portalKey: 7, geometryRevision: 2, geometry: portalGeometry([], CIRCLE_SHAPE) })
    assert.equal(apertureOpenCells(portal.geometry), 8)
    const child = {
      originX: 2, originY: 0, originZ: 5, facing: 2, frontSide: false, quarterTurns: 1, mirror: true,
      apertureWidth: 2, apertureHeight: 2, apertureMask: [0xfn], shape: FULL_SHAPE, nearPlanePadding: 0.25, aperturePadding: 0.5, frustumCullingRatio: 1,
      depthBlocks: 32, recursionDepth: 0, blackoutPolicy: 0, blackoutState: 0, maskAirPolicy: 1, lightingPolicy: 0, fidelityFlags: 0,
      kind: 0, planeOffset: 0, parentPortalKey: 7, targetIdentity: 0n, nested: []
    }
    assert.deepEqual(decodeVector(vector('portal_nested')), { type: 'PORTAL', portalKey: 8, geometryRevision: 1, geometry: portalGeometry([child], CIRCLE_SHAPE) })
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

  it('decodes entity events, the projected self identity and the environment transform', () => {
    assert.deepEqual(decodeVector(vector('entity_swing')), { type: 'ENTITY_EVENT', portalKey: 7, eventSeq: 3, entityId: TEST_UUID, hurt: false, animation: 3, yaw: 0 })
    assert.deepEqual(decodeVector(vector('entity_hurt')), { type: 'ENTITY_EVENT', portalKey: 7, eventSeq: 4, entityId: TEST_UUID, hurt: true, animation: 0, yaw: 179.5 })
    assert.deepEqual(decodeVector(vector('entity_self')), { type: 'ENTITY_SELF', projectedId: TEST_UUID })
    assert.deepEqual(decodeVector(vector('environment')), { type: 'ENVIRONMENT', portalKey: 7,
      environment: fixtureEnvironment('test:destination', { permutation: 44, translation: { x: -128.5, y: 96, z: 33.25 } }, 0.5) })
  })

  it('decodes the mesh view messages', () => {
    assert.deepEqual(decodeVector(vector('mesh_begin')), { type: 'MESH_BEGIN', portalKey: 7, generation: 12, bounds: { minX: -512, minY: -64, minZ: -512, sizeX: 1024, sizeY: 512, sizeZ: 512 }, maxResidentSections: 1024 })
    assert.deepEqual(decodeVector(vector('mesh_drop')), { type: 'MESH_DROP', portalKey: 7, generation: 12, sectionX: -32, sectionY: 4, sectionZ: -10 })
    assert.deepEqual(decodeVector(vector('mesh_ack')), { type: 'MESH_ACK', portalKey: 7, generation: 12, sectionX: -32, sectionY: 4, sectionZ: -10, revision: 1 })
    assert.deepEqual(decodeVector(vector('mesh_local')), { type: 'MESH_LOCAL', portalKey: 7, generation: 12, sequence: 1, available: true, sections: [{ x: -32, y: 4, z: -10 }], entities: [TEST_UUID] })
    assert.deepEqual(decodeVector(vector('mesh_cached')), { type: 'MESH_CACHED', portalKey: 7, generation: 12, sequence: 1, available: true, claims: [{ x: -32, y: 4, z: -10, hash: 0x1122334455667788n }] })
    assert.deepEqual(decodeVector(vector('mesh_reuse')), { type: 'MESH_REUSE', portalKey: 7, generation: 12, sectionX: -32, sectionY: 4, sectionZ: -10, revision: 2, hash: 0x1122334455667788n })
  })

  it('decodes prepared travel messages', () => {
    const begin = decodeVector(vector('travel_begin'))
    assert.deepEqual(begin, {
      type: 'TRAVEL_BEGIN', token: TEST_UUID, generation: 3n, sourcePortal: '00000000-0000-0038-0000-00000000004e', sourceWorld: 'minecraft:the_nether',
      sourceGeometry: portalGeometry([], FULL_SHAPE), destinationToSource: begin.destinationToSource, scale: 1, world: TRAVEL_WORLD, arrival: TRAVEL_ARRIVAL,
      chunks: [{ x: -32, z: -10 }], environment: fixtureEnvironment('minecraft:overworld', IDENTITY_TRANSFORM), expiresMillis: 30000,
      rules: { orientation: 'FRAME', gravityFlip: false, momentum: begin.rules.momentum, scale: { mode: 'OFF', min: 0.0625, max: 16 } },
      resident: false, levelHandle: 0, seamless: false
    })
    assert.deepEqual(begin.destinationToSource.translation, { x: 4, y: 0, z: 6 })
    const seamless = decodeVector(vector('travel_begin_seamless'))
    assert.deepEqual([seamless.resident, seamless.levelHandle, seamless.seamless], [true, 4, true])
    assert.deepEqual(seamless.sourceGeometry, portalGeometry([], CIRCLE_SHAPE))
    assert.deepEqual(seamless.rules, { orientation: 'LOOK', gravityFlip: true, momentum: { mode: 'SCALE', factor: 0.75, maxSpeed: 3.5, impulse: { x: 0, y: 0.25, z: 0 } },
      scale: { mode: 'RATIO', min: 0.25, max: 4 } })
    assert.equal(seamless.scale, 3)
    assert.equal(seamless.environment.scale, f32(1 / 3))
    assert.deepEqual(decodeVector(vector('travel_chunk')), { type: 'TRAVEL_CHUNK', token: TEST_UUID, generation: 3n, chunkX: -32, chunkZ: -10, revision: 2,
      fragmentIndex: 0, fragmentCount: 1, totalBytes: 4, payload: Buffer.from([1, 2, 3, 4]) })
    assert.deepEqual(decodeVector(vector('travel_end')), { type: 'TRAVEL_END', token: TEST_UUID, generation: 3n, contentRevision: 9n, chunks: [{ x: -32, z: -10, revision: 2 }] })
    assert.deepEqual(decodeVector(vector('travel_ready')), { type: 'TRAVEL_READY', token: TEST_UUID, generation: 3n, contentRevision: 9n })
    assert.deepEqual(decodeVector(vector('travel_commit')), { type: 'TRAVEL_COMMIT', token: TEST_UUID, generation: 3n, contentRevision: 9n,
      sourceWorld: 'minecraft:the_nether', destinationWorld: 'minecraft:overworld', arrival: TRAVEL_ARRIVAL, velocity: { x: 0.25, y: -0.5, z: 1 } })
    assert.deepEqual(decodeVector(vector('travel_cancel')), { type: 'TRAVEL_CANCEL', token: TEST_UUID, generation: 3n })
    assert.deepEqual(decodeVector(vector('travel_cross')), { type: 'TRAVEL_CROSS', token: TEST_UUID, generation: 3n, contentRevision: 9n,
      sourcePose: { x: 635.5, y: 65, z: -4681.4, yaw: 90, pitch: -12 }, previousEye: { x: 635.5, y: 66.62, z: -4681.6 }, currentEye: { x: 635.5, y: 66.62, z: -4681.4 } })
    assert.deepEqual(decodeVector(vector('travel_reuse')), { type: 'TRAVEL_REUSE', token: TEST_UUID, generation: 3n, chunkX: -32, chunkZ: -10, revision: 2, hash: TRAVEL_HASH })
    assert.deepEqual(decodeVector(vector('travel_cached')), { type: 'TRAVEL_CACHED', token: TEST_UUID, generation: 3n, chunkX: -32, chunkZ: -10, revision: 2, hash: TRAVEL_HASH, available: true })
  })

  it('decodes seamless travel and remote view messages', () => {
    assert.deepEqual(decodeVector(vector('remote_level_open')), { type: 'REMOTE_LEVEL_OPEN', levelHandle: 4, world: TRAVEL_WORLD,
      environment: fixtureEnvironment('minecraft:overworld', IDENTITY_TRANSFORM), viewRadius: 8, center: { x: -32, z: -10 } })
    assert.deepEqual(decodeVector(vector('remote_level_close')), { type: 'REMOTE_LEVEL_CLOSE', levelHandle: 4 })
    assert.deepEqual(decodeVector(vector('routed_packet')), { type: 'ROUTED_PACKET', levelHandle: 4, sequence: 17, fragmentIndex: 0, fragmentCount: 1, totalBytes: 4,
      payload: Buffer.from([5, 6, 7, 8]) })
    assert.deepEqual(decodeVector(vector('travel_accept')), { type: 'TRAVEL_ACCEPT', token: TEST_UUID, generation: 3n, contentRevision: 9n, pose: TRAVEL_ARRIVAL,
      velocity: { x: 0.25, y: -0.5, z: 1 }, levelHandle: 4, dimensionChanged: true, serverTick: 1200n })
    assert.deepEqual(decodeVector(vector('remote_view_ack')), { type: 'REMOTE_VIEW_ACK', levelHandle: 4, lastSequence: 17, chunksPerTickHint: 8 })
  })

  it('names the extension capabilities at the bits the server offers', () => {
    assert.deepEqual(capabilityNames(decodeVector(vector('offer')).serverCaps), ['PLATES', 'BRICK_CACHE', 'DEST_LIGHT', 'ENTITY_FRAMES', 'ATMOSPHERE', 'ZERO_COPY', 'CLIENT_RECURSION',
      'CLIENT_MIRROR', 'CONFIG_PHASE', 'LINK_UNCOMPRESSED', 'VIEW_STATS', 'MESH_RENDER', 'ENTITY_EVENTS', 'LOCAL_MESH', 'MESH_REUSE', 'ENTITY_SELF',
      'FX_EMITTERS', 'PREPARED_TRAVEL', 'PREPARED_TRAVEL_CACHE', 'REMOTE_VIEW', 'SEAMLESS_TRAVEL'])
    assert.equal(capabilitySet('FX_EMITTERS'), 1n << 32n)
    assert.equal(capabilitySet('SEAMLESS_TRAVEL'), 1n << 36n)
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
