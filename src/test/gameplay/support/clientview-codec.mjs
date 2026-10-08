import { deflateSync, inflateSync } from 'node:zlib'

export const CHANNEL = 'wormholes:v7'
export const WIRE_VERSION = 7
export const S2C_HEADER_BYTES = 6
export const FLAG_DEFLATED = 1
export const FLAG_LAST = 2
export const FLAG_RESERVED = 4
export const FLAG_MASK = FLAG_DEFLATED | FLAG_LAST | FLAG_RESERVED
export const DEFAULT_MAX_FRAME_BYTES = 512 * 1024
export const MIN_MAX_FRAME_BYTES = 64 * 1024
export const HARD_MAX_FRAME_BYTES = 1024 * 1024
export const MAX_C2S_BYTES = 32767
export const MAX_C2S_MESSAGES_PER_SECOND = 512
export const DEFLATE_THRESHOLD_BYTES = 256
export const MAX_STRING_BYTES = 256
export const BRICK_EDGE = 16
export const BRICK_CELLS = BRICK_EDGE * BRICK_EDGE * BRICK_EDGE
export const LIGHT_NIBBLE_BYTES = BRICK_CELLS / 2
export const MAX_BRICKS_PER_PLATE = 65535
export const MAX_BRICK_BLOCK_ENTITIES = 512
export const MAX_BRICK_BLOCK_ENTITY_BYTES = 32 * 1024
export const MAX_BLOCK_ENTITY_PAYLOAD_BYTES = 2048 + MAX_STRING_BYTES + 4
export const MAX_BRICK_BYTES = 48 * 1024
export const MAX_SECTION_BOX_EDGE = 255
export const PALETTE_AIR = 0
export const PALETTE_OCCLUDED = 1
export const PALETTE_BACKING = 2
export const RESERVED_PALETTE_IDS = 3
export const MAX_PALETTE_ENTRIES_PER_MESSAGE = 65535
export const MAX_SESSION_PALETTE_SIZE = 1 << 20
export const MAX_PATCH_OPS = 65535
export const MAX_ENTITIES_PER_FRAME = 255
export const MAX_PRESENT_IDS_PER_FRAME = 1024
export const MAX_ENTITY_VISUAL_BYTES = 16 * 1024
export const MAX_FX_EMITTERS = 255
export const WORLD_FX_KEY = 0
export const MAX_APERTURE_MASK_WORDS = 1024
export const MAX_NESTED_GEOMETRY = 16
export const MAX_SHAPE_BYTES = 2048
export const MAX_GEOMETRY_DEPTH = 6
export const MAX_BRICK_MISS_WORDS = (MAX_BRICKS_PER_PLATE + 63) >> 6
export const MAX_BRICK_MISS_PLATES = 255
export const OP_FULL = 0
export const OP_SPARSE = 1
export const OP_CLEAR = 2
export const PRESENCE_UNCHANGED = 0xffff
export const LIGHT_UNIFORM = 0
export const LIGHT_RUNS = 1
export const LIGHT_RAW = 2
export const BRICK_FLAG_LIGHT = 1
export const BRICK_FLAG_BLOCK_ENTITIES = 2
export const BRICK_FLAG_MASK = BRICK_FLAG_LIGHT | BRICK_FLAG_BLOCK_ENTITIES
export const ENCODINGS = Object.freeze(['EMPTY', 'SINGLE', 'PALETTED'])
export const DECLINE_REASONS = Object.freeze(['WIRE_MISMATCH', 'DATA_VERSION_MISMATCH', 'DISABLED', 'CAPACITY'])
export const RESET_REASONS = Object.freeze(['TELEPORT', 'DIMENSION', 'RESPAWN', 'DISABLED', 'PROTOCOL', 'OVERLOAD'])
export const FX_KINDS = Object.freeze(['RIM_DUST', 'SURFACE', 'SOUND', 'DOOR_ANIM', 'ANIMATION', 'BURST'])
export const PORTAL_KINDS = Object.freeze(['FRAME', 'RTP', 'DOOR', 'VANILLA_REPLACEMENT'])
export const SKYBOXES = Object.freeze(['NONE', 'OVERWORLD', 'END'])
export const CARDINAL_LIGHTING = Object.freeze(['DEFAULT', 'NETHER'])
export const EYE_MEDIA = Object.freeze(['NONE', 'WATER', 'LAVA', 'POWDER_SNOW'])
export const ORIENTATION_RULES = Object.freeze(['FRAME', 'LOOK', 'SNAP', 'MIRROR'])
export const MOMENTUM_MODES = Object.freeze(['PRESERVE', 'SCALE', 'CLAMP', 'ZERO', 'IMPULSE'])
export const SCALE_MODES = Object.freeze(['OFF', 'MOTION', 'RATIO'])
export const AXIS_PERMUTATIONS = 48
export const TRANSFORM_BYTES = 25
export const MAX_MESH_LOCAL_SECTIONS = 512
export const MAX_MESH_LOCAL_ENTITIES = 256
export const MAX_MESH_CLAIMS = 512
export const TRAVEL_HASH_BYTES = 32
export const MAX_TRAVEL_CHUNKS = 1089
export const TRAVEL_FRAGMENT_BYTES = 48 * 1024

export const MESSAGE_TYPES = Object.freeze({
  OFFER: { id: 1, direction: 'S2C' },
  ACCEPT: { id: 2, direction: 'S2C' },
  DECLINE: { id: 3, direction: 'S2C' },
  PALETTE: { id: 4, direction: 'S2C' },
  PORTAL: { id: 5, direction: 'S2C' },
  PORTAL_DROP: { id: 6, direction: 'S2C' },
  PLATE_BEGIN: { id: 7, direction: 'S2C' },
  PLATE_BRICKS: { id: 8, direction: 'S2C' },
  PLATE_END: { id: 9, direction: 'S2C' },
  PLATE_PATCH: { id: 10, direction: 'S2C' },
  PLATE_HANDLE: { id: 11, direction: 'S2C' },
  ENTITY_FRAME: { id: 12, direction: 'S2C' },
  FX: { id: 13, direction: 'S2C' },
  ATMOSPHERE: { id: 14, direction: 'S2C' },
  SESSION_RESET: { id: 15, direction: 'S2C' },
  MESH_BEGIN: { id: 16, direction: 'S2C' },
  MESH_SECTION: { id: 17, direction: 'S2C' },
  MESH_DROP: { id: 18, direction: 'S2C' },
  ENVIRONMENT: { id: 19, direction: 'S2C' },
  ENTITY_EVENT: { id: 20, direction: 'S2C' },
  ENTITY_SELF: { id: 21, direction: 'S2C' },
  HELLO: { id: 32, direction: 'C2S' },
  BRICK_MISS: { id: 33, direction: 'C2S' },
  ACK: { id: 34, direction: 'C2S' },
  VIEW_STATS: { id: 35, direction: 'C2S' },
  PLATE_REFUSED: { id: 36, direction: 'C2S' },
  MESH_ACK: { id: 37, direction: 'C2S' },
  MESH_LOCAL: { id: 38, direction: 'C2S' },
  MESH_CACHED: { id: 39, direction: 'C2S' },
  MESH_REUSE: { id: 40, direction: 'S2C' },
  TRAVEL_BEGIN: { id: 41, direction: 'S2C' },
  TRAVEL_CHUNK: { id: 42, direction: 'S2C' },
  TRAVEL_END: { id: 43, direction: 'S2C' },
  TRAVEL_COMMIT: { id: 45, direction: 'S2C' },
  TRAVEL_CANCEL: { id: 46, direction: 'S2C' },
  TRAVEL_CROSS: { id: 47, direction: 'C2S' },
  TRAVEL_REUSE: { id: 48, direction: 'S2C' },
  REMOTE_LEVEL_OPEN: { id: 51, direction: 'S2C' },
  REMOTE_LEVEL_CLOSE: { id: 52, direction: 'S2C' },
  ROUTED_PACKET: { id: 53, direction: 'S2C' },
  TRAVEL_ACCEPT: { id: 54, direction: 'S2C' },
  REMOTE_VIEW_ACK: { id: 55, direction: 'C2S' }
})

const TYPE_BY_ID = new Map(Object.entries(MESSAGE_TYPES).map(([name, type]) => [type.id, name]))

export const CAPABILITIES = Object.freeze({
  PLATES: 0,
  BRICK_CACHE: 1,
  DEST_LIGHT: 2,
  ENTITY_FRAMES: 3,
  ATMOSPHERE: 5,
  ZERO_COPY: 6,
  CLIENT_RECURSION: 7,
  CLIENT_MIRROR: 8,
  CONFIG_PHASE: 9,
  LINK_UNCOMPRESSED: 10,
  VIEW_STATS: 11,
  MESH_RENDER: 12,
  ENTITY_EVENTS: 13,
  LOCAL_MESH: 14,
  MESH_REUSE: 15,
  ENTITY_SELF: 18,
  FX_EMITTERS: 32,
  REMOTE_VIEW: 35,
  SEAMLESS_TRAVEL: 36
})

export const FIRST_EXTENSION_BIT = 32
export const EXTENSION_CAPS = BigInt.asUintN(64, -1n << BigInt(FIRST_EXTENSION_BIT))
export const ALL_CAPS = Object.values(CAPABILITIES).filter((bit) => bit < FIRST_EXTENSION_BIT)
  .reduce((set, bit) => set | (1n << BigInt(bit)), EXTENSION_CAPS)

export class ClientViewProtocolError extends Error {
  constructor(message, options) {
    super(message, options)
    this.name = 'ClientViewProtocolError'
  }
}

export function typeName(id) {
  return TYPE_BY_ID.get(id)
}

export function capabilityMask(name) {
  const bit = CAPABILITIES[name]
  if (bit === undefined) throw new ClientViewProtocolError(`unknown capability ${name}`)
  return 1n << BigInt(bit)
}

export function hasCapability(caps, name) {
  return (BigInt(caps) & capabilityMask(name)) !== 0n
}

export function capabilitySet(...names) {
  return names.reduce((set, name) => set | capabilityMask(name), 0n)
}

export function capabilityNames(caps) {
  const value = BigInt(caps)
  return Object.keys(CAPABILITIES).filter((name) => (value & capabilityMask(name)) !== 0n)
}

export function parseCapabilities(text) {
  const trimmed = String(text).trim()
  if (/^0x[0-9a-f]+$/i.test(trimmed)) return BigInt(trimmed) & ALL_CAPS
  if (trimmed === '') return 0n
  return capabilitySet(...trimmed.split(',').map((name) => name.trim().toUpperCase()).filter(Boolean))
}

export function clampMaxFrameBytes(requested) {
  return Math.max(MIN_MAX_FRAME_BYTES, Math.min(HARD_MAX_FRAME_BYTES, requested))
}

export function hex64(value) {
  return `0x${BigInt.asUintN(64, BigInt(value)).toString(16).padStart(16, '0')}`
}

export function brickCellIndex(x, y, z) {
  return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
}

export function brickCellX(cellIndex) {
  return cellIndex & 15
}

export function brickCellY(cellIndex) {
  return (cellIndex >> 8) & 15
}

export function brickCellZ(cellIndex) {
  return (cellIndex >> 4) & 15
}

export function packedBytes(bits) {
  return (BRICK_CELLS * bits) / 8
}

export function validBits(bits) {
  return bits === 1 || bits === 2 || bits === 4 || bits === 8 || bits === 16
}

export function brickLocalIndex(brick, cellIndex) {
  const bits = brick.bitsPerIndex
  const bytes = brick.packed
  switch (bits) {
    case 1: return (bytes[cellIndex >> 3] >> (cellIndex & 7)) & 1
    case 2: return (bytes[cellIndex >> 2] >> ((cellIndex & 3) << 1)) & 3
    case 4: return (bytes[cellIndex >> 1] >> ((cellIndex & 1) << 2)) & 15
    case 8: return bytes[cellIndex]
    case 16: return bytes[cellIndex << 1] | (bytes[(cellIndex << 1) + 1] << 8)
    default: throw new ClientViewProtocolError(`invalid bits per index ${bits}`)
  }
}

export function brickCellId(brick, cellIndex) {
  switch (brick.encoding) {
    case 'EMPTY': return PALETTE_AIR
    case 'SINGLE': return brick.singlePaletteId
    default: return brick.localPalette[brickLocalIndex(brick, cellIndex)]
  }
}

export function brickCells(brick) {
  const cells = new Uint32Array(BRICK_CELLS)
  for (let i = 0; i < BRICK_CELLS; i++) cells[i] = brickCellId(brick, i)
  return cells
}

export function brickPaletteIds(brick) {
  switch (brick.encoding) {
    case 'EMPTY': return [PALETTE_AIR]
    case 'SINGLE': return [brick.singlePaletteId]
    default: return [...brick.localPalette]
  }
}

export function lightNibble(nibbles, cellIndex) {
  const packed = nibbles[cellIndex >> 1]
  return (cellIndex & 1) === 0 ? packed & 15 : packed >> 4
}

export function setLightNibble(nibbles, cellIndex, value) {
  const slot = cellIndex >> 1
  const packed = nibbles[slot]
  nibbles[slot] = (cellIndex & 1) === 0 ? (packed & 0xf0) | (value & 0x0f) : (packed & 0x0f) | ((value & 0x0f) << 4)
}

export function varintSize(value) {
  let size = 1
  let remaining = value >>> 0
  while ((remaining & ~0x7f) !== 0) {
    remaining >>>= 7
    size++
  }
  return size
}

export function sectionOfBrick(sections, brickIndex) {
  return {
    x: sections.minSectionX + Math.floor(brickIndex / (sections.sizeY * sections.sizeZ)),
    y: sections.minSectionY + Math.floor(brickIndex / sections.sizeZ) % sections.sizeY,
    z: sections.minSectionZ + brickIndex % sections.sizeZ
  }
}

export function brickMissBitset(brickCount, missed) {
  const words = new BigUint64Array((brickCount + 63) >> 6)
  for (const index of missed) {
    if (index < 0 || index >= brickCount) throw new ClientViewProtocolError(`missed brick ${index} outside ${brickCount}`)
    words[index >> 6] |= 1n << BigInt(index & 63)
  }
  return [...words].map((word) => BigInt.asIntN(64, word))
}

export function brickMissIndices(bitset, brickCount) {
  const indices = []
  for (let word = 0; word < bitset.length; word++) {
    const bits = BigInt.asUintN(64, bitset[word])
    if (bits === 0n) continue
    for (let bit = 0; bit < 64; bit++) {
      if ((bits >> BigInt(bit)) & 1n) {
        const index = (word << 6) | bit
        if (brickCount === undefined || index < brickCount) indices.push(index)
      }
    }
  }
  return indices
}

export function apertureOpenCells(geometry) {
  const cells = geometry.apertureWidth * geometry.apertureHeight
  let open = 0
  for (let word = 0; word < geometry.apertureMask.length; word++) {
    let bits = BigInt.asUintN(64, geometry.apertureMask[word])
    const remaining = cells - (word << 6)
    if (remaining <= 0) break
    if (remaining < 64) bits &= (1n << BigInt(remaining)) - 1n
    while (bits !== 0n) {
      bits &= bits - 1n
      open++
    }
  }
  return open
}

export class ByteReader {
  constructor(buffer, offset = 0, length = buffer.length - offset) {
    this.buffer = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer.buffer, buffer.byteOffset, buffer.byteLength)
    this.position = offset
    this.end = offset + length
  }

  remaining() {
    return this.end - this.position
  }

  require(bytes) {
    if (!Number.isInteger(bytes) || bytes < 0 || bytes > this.end - this.position) {
      throw new ClientViewProtocolError(`truncated: need ${bytes} bytes, have ${this.end - this.position}`)
    }
  }

  expectEnd() {
    if (this.position !== this.end) throw new ClientViewProtocolError(`trailing ${this.end - this.position} bytes after message`)
  }

  u8() {
    this.require(1)
    return this.buffer[this.position++]
  }

  u16() {
    this.require(2)
    const value = this.buffer.readUInt16LE(this.position)
    this.position += 2
    return value
  }

  i32() {
    this.require(4)
    const value = this.buffer.readInt32LE(this.position)
    this.position += 4
    return value
  }

  u32() {
    this.require(4)
    const value = this.buffer.readUInt32LE(this.position)
    this.position += 4
    return value
  }

  i64() {
    this.require(8)
    const value = this.buffer.readBigInt64LE(this.position)
    this.position += 8
    return value
  }

  u64() {
    this.require(8)
    const value = this.buffer.readBigUInt64LE(this.position)
    this.position += 8
    return value
  }

  f32() {
    this.require(4)
    const value = this.buffer.readFloatLE(this.position)
    this.position += 4
    return value
  }

  f64() {
    this.require(8)
    const value = this.buffer.readDoubleLE(this.position)
    this.position += 8
    return value
  }

  varint(max = 0x7fffffff) {
    let value = 0
    for (let shift = 0; ; shift += 7) {
      if (shift > 28) throw new ClientViewProtocolError('varint longer than 5 bytes')
      const byte = this.u8()
      value += (byte & 0x7f) * 2 ** shift
      if ((byte & 0x80) === 0) break
    }
    if (value > 0x7fffffff) throw new ClientViewProtocolError('varint overflow')
    if (value > max) throw new ClientViewProtocolError(`varint ${value} exceeds ${max}`)
    return value
  }

  string() {
    const length = this.varint(MAX_STRING_BYTES)
    this.require(length)
    const value = this.buffer.toString('utf8', this.position, this.position + length)
    this.position += length
    return value
  }

  bytes(length) {
    this.require(length)
    const copy = Buffer.from(this.buffer.subarray(this.position, this.position + length))
    this.position += length
    return copy
  }

  longs(count) {
    this.require(count * 8)
    const values = new Array(count)
    for (let i = 0; i < count; i++) values[i] = this.i64()
    return values
  }

  flag() {
    const value = this.u8()
    if (value > 1) throw new ClientViewProtocolError(`flag byte ${value}`)
    return value === 1
  }

  checkedCount(count, max, minBytesEach) {
    if (count < 0 || count > max) throw new ClientViewProtocolError(`count ${count} exceeds ${max}`)
    if (count * minBytesEach > this.remaining()) throw new ClientViewProtocolError(`count ${count} does not fit in ${this.remaining()} bytes`)
    return count
  }
}

export class ByteWriter {
  constructor(capacity = 256) {
    this.buffer = Buffer.alloc(Math.max(16, capacity))
    this.size = 0
  }

  ensure(extra) {
    const required = this.size + extra
    if (required <= this.buffer.length) return
    const grown = Buffer.alloc(Math.max(required, this.buffer.length + (this.buffer.length >> 1)))
    this.buffer.copy(grown, 0, 0, this.size)
    this.buffer = grown
  }

  toBuffer() {
    return Buffer.from(this.buffer.subarray(0, this.size))
  }

  u8(value) {
    this.ensure(1)
    this.buffer[this.size++] = value & 0xff
  }

  u16(value) {
    this.ensure(2)
    this.buffer.writeUInt16LE(value & 0xffff, this.size)
    this.size += 2
  }

  i32(value) {
    this.ensure(4)
    this.buffer.writeInt32LE(value | 0, this.size)
    this.size += 4
  }

  u32(value) {
    this.ensure(4)
    this.buffer.writeUInt32LE(value >>> 0, this.size)
    this.size += 4
  }

  i64(value) {
    this.ensure(8)
    this.buffer.writeBigInt64LE(BigInt.asIntN(64, BigInt(value)), this.size)
    this.size += 8
  }

  u64(value) {
    this.ensure(8)
    this.buffer.writeBigUInt64LE(BigInt.asUintN(64, BigInt(value)), this.size)
    this.size += 8
  }

  f32(value) {
    this.ensure(4)
    this.buffer.writeFloatLE(value, this.size)
    this.size += 4
  }

  f64(value) {
    this.ensure(8)
    this.buffer.writeDoubleLE(value, this.size)
    this.size += 8
  }

  varint(value) {
    if (!Number.isInteger(value) || value < 0 || value > 0x7fffffff) throw new ClientViewProtocolError(`varint out of range: ${value}`)
    this.ensure(5)
    let remaining = value
    while (remaining > 0x7f) {
      this.buffer[this.size++] = (remaining & 0x7f) | 0x80
      remaining = Math.floor(remaining / 128)
    }
    this.buffer[this.size++] = remaining
  }

  string(value) {
    const bytes = Buffer.from(value, 'utf8')
    if (bytes.length > MAX_STRING_BYTES) throw new ClientViewProtocolError(`string of ${bytes.length} bytes exceeds ${MAX_STRING_BYTES}`)
    this.varint(bytes.length)
    this.bytes(bytes)
  }

  bytes(value) {
    this.ensure(value.length)
    Buffer.from(value.buffer, value.byteOffset, value.byteLength).copy(this.buffer, this.size)
    this.size += value.length
  }

  longs(values) {
    for (const value of values) this.i64(value)
  }
}

export function readBrick(reader) {
  const brickIndex = reader.u16()
  return readBrickBody(reader, brickIndex)
}

export function readBrickBody(reader, brickIndex) {
  const start = reader.position
  const encoding = ENCODINGS[reader.u8()]
  if (encoding === undefined) throw new ClientViewProtocolError('unknown brick encoding')
  const bitsPerIndex = reader.u8()
  const flags = reader.u8()
  if ((flags & ~BRICK_FLAG_MASK) !== 0) throw new ClientViewProtocolError(`unknown brick flags ${flags}`)
  let singlePaletteId = PALETTE_AIR
  let localPalette = []
  let packed = Buffer.alloc(0)
  if (encoding === 'EMPTY') {
    if (bitsPerIndex !== 0) throw new ClientViewProtocolError(`empty brick with bits ${bitsPerIndex}`)
  } else if (encoding === 'SINGLE') {
    if (bitsPerIndex !== 0) throw new ClientViewProtocolError(`single brick with bits ${bitsPerIndex}`)
    singlePaletteId = reader.varint(MAX_SESSION_PALETTE_SIZE - 1)
    if (singlePaletteId === PALETTE_AIR) throw new ClientViewProtocolError('single brick of air must be EMPTY')
  } else {
    if (!validBits(bitsPerIndex)) throw new ClientViewProtocolError(`invalid bits per index ${bitsPerIndex}`)
    const maxPalette = Math.min(2 ** bitsPerIndex, BRICK_CELLS)
    const size = reader.varint(maxPalette)
    if (size < 2) throw new ClientViewProtocolError(`paletted brick with ${size} entries`)
    reader.checkedCount(size, maxPalette, 1)
    localPalette = new Array(size)
    for (let i = 0; i < size; i++) localPalette[i] = reader.varint(MAX_SESSION_PALETTE_SIZE - 1)
    packed = reader.bytes(packedBytes(bitsPerIndex))
    if (size - 1 < 2 ** bitsPerIndex - 1) {
      const probe = { bitsPerIndex, packed }
      for (let cell = 0; cell < BRICK_CELLS; cell++) {
        if (brickLocalIndex(probe, cell) >= size) throw new ClientViewProtocolError('packed index outside the local palette')
      }
    }
  }
  let blockLight = null
  let skyLight = null
  if ((flags & BRICK_FLAG_LIGHT) !== 0) {
    blockLight = readLightLayer(reader)
    skyLight = readLightLayer(reader)
  }
  const blockEntities = []
  if ((flags & BRICK_FLAG_BLOCK_ENTITIES) !== 0) {
    const count = reader.checkedCount(reader.u16(), MAX_BRICK_BLOCK_ENTITIES, 3)
    if (count === 0) throw new ClientViewProtocolError('BLOCK_ENTITIES flag with zero entries')
    let total = 0
    for (let i = 0; i < count; i++) {
      const cellIndex = reader.u16()
      if (cellIndex >= BRICK_CELLS) throw new ClientViewProtocolError(`block entity cell ${cellIndex} outside the brick`)
      const length = reader.varint(MAX_BLOCK_ENTITY_PAYLOAD_BYTES)
      total += length
      if (total > MAX_BRICK_BLOCK_ENTITY_BYTES) throw new ClientViewProtocolError(`brick block entity payload exceeds ${MAX_BRICK_BLOCK_ENTITY_BYTES}`)
      blockEntities.push({ cellIndex, payload: reader.bytes(length) })
    }
  }
  const bytes = reader.position - start
  if (bytes > MAX_BRICK_BYTES) throw new ClientViewProtocolError(`brick body exceeds ${MAX_BRICK_BYTES} bytes`)
  return { brickIndex, encoding, bitsPerIndex, flags, singlePaletteId, localPalette, packed, blockLight, skyLight, blockEntities, bytes: bytes + 2 }
}

export function writeBrick(writer, brick) {
  writer.u16(brick.brickIndex)
  writeBrickBody(writer, brick)
}

export function writeBrickBody(writer, brick) {
  const encoding = ENCODINGS.indexOf(brick.encoding)
  if (encoding < 0) throw new ClientViewProtocolError(`unknown brick encoding ${brick.encoding}`)
  const light = brick.blockLight !== null && brick.blockLight !== undefined
  const blockEntities = brick.blockEntities ?? []
  const flags = (light ? BRICK_FLAG_LIGHT : 0) | (blockEntities.length > 0 ? BRICK_FLAG_BLOCK_ENTITIES : 0)
  writer.u8(encoding)
  writer.u8(brick.encoding === 'PALETTED' ? brick.bitsPerIndex : 0)
  writer.u8(flags)
  if (brick.encoding === 'SINGLE') {
    writer.varint(brick.singlePaletteId)
  } else if (brick.encoding === 'PALETTED') {
    writer.varint(brick.localPalette.length)
    for (const id of brick.localPalette) writer.varint(id)
    if (brick.packed.length !== packedBytes(brick.bitsPerIndex)) throw new ClientViewProtocolError('packed index length does not match bits')
    writer.bytes(brick.packed)
  }
  if (light) {
    writeLightLayer(writer, brick.blockLight)
    writeLightLayer(writer, brick.skyLight)
  }
  if (blockEntities.length > 0) {
    writer.u16(blockEntities.length)
    for (const cell of blockEntities) {
      writer.u16(cell.cellIndex)
      writer.varint(cell.payload.length)
      writer.bytes(cell.payload)
    }
  }
}

export function readLightLayer(reader) {
  const mode = reader.u8()
  if (mode === LIGHT_UNIFORM) {
    const value = reader.u8()
    if (value > 15) throw new ClientViewProtocolError(`uniform light value ${value} exceeds 15`)
    return Buffer.alloc(LIGHT_NIBBLE_BYTES, value | (value << 4))
  }
  if (mode === LIGHT_RUNS) {
    const runs = reader.checkedCount(reader.varint(BRICK_CELLS), BRICK_CELLS, 2)
    if (runs === 0) throw new ClientViewProtocolError('light runs without a run')
    const nibbles = Buffer.alloc(LIGHT_NIBBLE_BYTES)
    let cell = 0
    for (let run = 0; run < runs; run++) {
      const value = reader.u8()
      if (value > 15) throw new ClientViewProtocolError(`light run value ${value} exceeds 15`)
      const length = reader.varint(BRICK_CELLS)
      if (length === 0 || cell + length > BRICK_CELLS) throw new ClientViewProtocolError('light runs do not fit the brick')
      for (const end = cell + length; cell < end; cell++) setLightNibble(nibbles, cell, value)
    }
    if (cell !== BRICK_CELLS) throw new ClientViewProtocolError(`light runs cover ${cell} of ${BRICK_CELLS} cells`)
    return nibbles
  }
  if (mode === LIGHT_RAW) {
    reader.require(LIGHT_NIBBLE_BYTES)
    return reader.bytes(LIGHT_NIBBLE_BYTES)
  }
  throw new ClientViewProtocolError(`unknown light layer mode ${mode}`)
}

export function lightRuns(nibbles) {
  const runs = []
  let value = lightNibble(nibbles, 0)
  let length = 1
  for (let cell = 1; cell < BRICK_CELLS; cell++) {
    const next = lightNibble(nibbles, cell)
    if (next === value) {
      length++
      continue
    }
    runs.push({ value, length })
    value = next
    length = 1
  }
  runs.push({ value, length })
  return runs
}

export function writeLightLayer(writer, nibbles) {
  if (nibbles.length !== LIGHT_NIBBLE_BYTES) throw new ClientViewProtocolError('light layer must hold 2048 nibble bytes')
  const first = nibbles[0]
  if ((first & 0x0f) === (first >>> 4) && nibbles.every((byte) => byte === first)) {
    writer.u8(LIGHT_UNIFORM)
    writer.u8(first & 0x0f)
    return
  }
  const runs = lightRuns(nibbles)
  const runsSize = varintSize(runs.length) + runs.reduce((size, run) => size + 1 + varintSize(run.length), 0)
  if (runsSize >= LIGHT_NIBBLE_BYTES) {
    writer.u8(LIGHT_RAW)
    writer.bytes(nibbles)
    return
  }
  writer.u8(LIGHT_RUNS)
  writer.varint(runs.length)
  for (const run of runs) {
    writer.u8(run.value)
    writer.varint(run.length)
  }
}

export function packBrick(brickIndex, cells) {
  if (cells.length !== BRICK_CELLS) throw new ClientViewProtocolError(`a brick holds ${BRICK_CELLS} cells`)
  const first = cells[0]
  let uniform = true
  for (let i = 1; i < cells.length && uniform; i++) uniform = cells[i] === first
  if (uniform) {
    return first === PALETTE_AIR
      ? { brickIndex, encoding: 'EMPTY', bitsPerIndex: 0, flags: 0, singlePaletteId: PALETTE_AIR, localPalette: [], packed: Buffer.alloc(0), blockLight: null, skyLight: null, blockEntities: [] }
      : { brickIndex, encoding: 'SINGLE', bitsPerIndex: 0, flags: 0, singlePaletteId: first, localPalette: [], packed: Buffer.alloc(0), blockLight: null, skyLight: null, blockEntities: [] }
  }
  const local = new Map()
  const indices = new Uint32Array(cells.length)
  for (let i = 0; i < cells.length; i++) {
    let index = local.get(cells[i])
    if (index === undefined) {
      index = local.size
      local.set(cells[i], index)
    }
    indices[i] = index
  }
  const size = local.size
  const bitsPerIndex = size <= 2 ? 1 : size <= 4 ? 2 : size <= 16 ? 4 : size <= 256 ? 8 : 16
  const packed = Buffer.alloc(packedBytes(bitsPerIndex))
  for (let i = 0; i < indices.length; i++) {
    const bit = i * bitsPerIndex
    if (bitsPerIndex === 16) {
      packed.writeUInt16LE(indices[i], i * 2)
    } else {
      packed[bit >> 3] |= indices[i] << (bit & 7)
    }
  }
  return { brickIndex, encoding: 'PALETTED', bitsPerIndex, flags: 0, singlePaletteId: PALETTE_AIR, localPalette: [...local.keys()], packed, blockLight: null, skyLight: null, blockEntities: [] }
}

function readGeometry(reader, depth) {
  if (depth >= MAX_GEOMETRY_DEPTH) throw new ClientViewProtocolError(`portal geometry nested deeper than ${MAX_GEOMETRY_DEPTH}`)
  const geometry = {
    originX: reader.i32(),
    originY: reader.i32(),
    originZ: reader.i32(),
    facing: reader.u8(),
    frontSide: reader.flag(),
    quarterTurns: reader.u8(),
    mirror: reader.flag(),
    apertureWidth: reader.u16(),
    apertureHeight: reader.u16()
  }
  const words = reader.checkedCount(reader.varint(), MAX_APERTURE_MASK_WORDS, 8)
  geometry.apertureMask = reader.longs(words)
  geometry.shape = reader.bytes(reader.checkedCount(reader.varint(), MAX_SHAPE_BYTES, 1))
  geometry.nearPlanePadding = reader.f32()
  geometry.aperturePadding = reader.f32()
  geometry.frustumCullingRatio = reader.f32()
  geometry.depthBlocks = reader.u16()
  geometry.recursionDepth = reader.u8()
  geometry.blackoutPolicy = reader.u8()
  geometry.blackoutState = reader.varint(MAX_SESSION_PALETTE_SIZE - 1)
  geometry.maskAirPolicy = reader.u8()
  geometry.lightingPolicy = reader.u8()
  geometry.fidelityFlags = reader.u8()
  geometry.kind = reader.u8()
  geometry.planeOffset = reader.f64()
  geometry.parentPortalKey = reader.varint()
  geometry.targetIdentity = reader.i64()
  const nestedCount = reader.checkedCount(reader.u8(), MAX_NESTED_GEOMETRY, 40)
  geometry.nested = []
  for (let i = 0; i < nestedCount; i++) geometry.nested.push(readGeometry(reader, depth + 1))
  return geometry
}

function writeGeometry(writer, geometry, depth) {
  if (depth >= MAX_GEOMETRY_DEPTH) throw new ClientViewProtocolError(`portal geometry nested deeper than ${MAX_GEOMETRY_DEPTH}`)
  writer.i32(geometry.originX)
  writer.i32(geometry.originY)
  writer.i32(geometry.originZ)
  writer.u8(geometry.facing)
  writer.u8(geometry.frontSide ? 1 : 0)
  writer.u8(geometry.quarterTurns)
  writer.u8(geometry.mirror ? 1 : 0)
  writer.u16(geometry.apertureWidth)
  writer.u16(geometry.apertureHeight)
  writer.varint(geometry.apertureMask.length)
  writer.longs(geometry.apertureMask)
  const shape = geometry.shape ?? Buffer.alloc(0)
  if (shape.length > MAX_SHAPE_BYTES) throw new ClientViewProtocolError(`portal shape of ${shape.length} bytes`)
  writer.varint(shape.length)
  writer.bytes(shape)
  writer.f32(geometry.nearPlanePadding)
  writer.f32(geometry.aperturePadding)
  writer.f32(geometry.frustumCullingRatio)
  writer.u16(geometry.depthBlocks)
  writer.u8(geometry.recursionDepth)
  writer.u8(geometry.blackoutPolicy)
  writer.varint(geometry.blackoutState)
  writer.u8(geometry.maskAirPolicy)
  writer.u8(geometry.lightingPolicy)
  writer.u8(geometry.fidelityFlags)
  writer.u8(geometry.kind)
  writer.f64(geometry.planeOffset ?? 0)
  writer.varint(geometry.parentPortalKey)
  writer.i64(geometry.targetIdentity)
  const nested = geometry.nested ?? []
  if (nested.length > MAX_NESTED_GEOMETRY) throw new ClientViewProtocolError(`portal geometry with ${nested.length} nested portals`)
  writer.u8(nested.length)
  for (const child of nested) writeGeometry(writer, child, depth + 1)
}

function readFlag(reader, label) {
  const value = reader.u8()
  if (value > 1) throw new ClientViewProtocolError(`invalid ${label} flag ${value}`)
  return value === 1
}

function finite(value, label) {
  if (!Number.isFinite(value)) throw new ClientViewProtocolError(`${label} must be finite`)
  return value
}

export function readTransform(reader) {
  reader.require(TRANSFORM_BYTES)
  const permutation = reader.u8()
  if (permutation >= AXIS_PERMUTATIONS) throw new ClientViewProtocolError(`axis permutation ${permutation} out of range`)
  const translation = {}
  for (const axis of ['x', 'y', 'z']) {
    translation[axis] = finite(reader.buffer.readDoubleBE(reader.position), 'transform translation')
    reader.position += 8
  }
  return { permutation, translation }
}

export function writeTransform(writer, transform) {
  writer.u8(transform.permutation)
  for (const axis of ['x', 'y', 'z']) {
    writer.ensure(8)
    writer.buffer.writeDoubleBE(transform.translation[axis], writer.size)
    writer.size += 8
  }
}

const IDENTIFIER = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/

function readEnvironment(reader) {
  const rgb = () => ({ red: reader.f32(), green: reader.f32(), blue: reader.f32() })
  const rgba = () => ({ ...rgb(), alpha: reader.f32() })
  const gameTime = reader.i64()
  const sky = { skybox: enumName(SKYBOXES, reader.u8(), 'skybox'), sunAngle: reader.f32(), moonAngle: reader.f32(), starAngle: reader.f32(), starBrightness: reader.f32(),
    sunrise: rgba(), color: rgb(), moonPhase: reader.u8(), rain: reader.f32(), thunder: reader.f32() }
  const fog = { color: rgb(), start: reader.f32(), end: reader.f32(), skyEnd: reader.f32(), cloudEnd: reader.f32(),
    waterColor: rgb(), waterStart: reader.f32(), waterEnd: reader.f32() }
  const lighting = { blockTint: rgb(), skyFactor: reader.f32(), skyColor: rgb(), ambient: rgb() }
  const clouds = { color: rgba(), height: reader.f32() }
  const transform = readTransform(reader)
  const scale = reader.f32()
  const dimension = { minY: reader.i32(), height: reader.i32(), hasSkyLight: readFlag(reader, 'sky light'),
    cardinalLighting: enumName(CARDINAL_LIGHTING, reader.u8(), 'cardinal lighting'), horizonHeight: reader.f64(), endFlashes: readFlag(reader, 'end flashes') }
  const world = { dimensionKey: reader.string(), clockTime: reader.i64(), biomeKey: reader.string(), seaLevel: reader.i32(), blockLight: reader.u8(),
    skyLight: reader.u8(), logicalHeight: reader.i32(), hasCeiling: readFlag(reader, 'ceiling'), ambientLight: reader.f32(),
    eyeMedium: enumName(EYE_MEDIA, reader.u8(), 'eye medium'), hasFixedTime: readFlag(reader, 'fixed time') }
  const floats = [sky.sunAngle, sky.moonAngle, sky.starAngle, sky.starBrightness, sky.rain, sky.thunder, ...Object.values(sky.sunrise), ...Object.values(sky.color),
    fog.start, fog.end, fog.skyEnd, fog.cloudEnd, fog.waterStart, fog.waterEnd, ...Object.values(fog.color), ...Object.values(fog.waterColor),
    lighting.skyFactor, ...Object.values(lighting.blockTint), ...Object.values(lighting.skyColor), ...Object.values(lighting.ambient),
    ...Object.values(clouds.color), clouds.height, world.ambientLight]
  if (sky.moonPhase > 7 || dimension.height <= 0 || !Number.isFinite(dimension.horizonHeight) || floats.some((value) => !Number.isFinite(value))
    || !Number.isFinite(scale) || scale <= 0
    || !IDENTIFIER.test(world.dimensionKey) || !IDENTIFIER.test(world.biomeKey) || world.blockLight > 15 || world.skyLight > 15 || world.logicalHeight < 0) {
    throw new ClientViewProtocolError('invalid destination environment')
  }
  return { gameTime, sky, fog, lighting, clouds, transform, scale, dimension, world }
}

function writeEnvironment(writer, environment) {
  const rgb = color => { writer.f32(color.red); writer.f32(color.green); writer.f32(color.blue) }
  const rgba = color => { rgb(color); writer.f32(color.alpha) }
  const { sky, fog, lighting, clouds, transform, scale, dimension, world } = environment
  writer.i64(environment.gameTime)
  writer.u8(enumId(SKYBOXES, sky.skybox, 'skybox'))
  writer.f32(sky.sunAngle)
  writer.f32(sky.moonAngle)
  writer.f32(sky.starAngle)
  writer.f32(sky.starBrightness)
  rgba(sky.sunrise)
  rgb(sky.color)
  writer.u8(sky.moonPhase)
  writer.f32(sky.rain)
  writer.f32(sky.thunder)
  rgb(fog.color)
  for (const key of ['start', 'end', 'skyEnd', 'cloudEnd']) writer.f32(fog[key])
  rgb(fog.waterColor)
  writer.f32(fog.waterStart)
  writer.f32(fog.waterEnd)
  rgb(lighting.blockTint)
  writer.f32(lighting.skyFactor)
  rgb(lighting.skyColor)
  rgb(lighting.ambient)
  rgba(clouds.color)
  writer.f32(clouds.height)
  writeTransform(writer, transform)
  writer.f32(scale)
  writer.i32(dimension.minY)
  writer.i32(dimension.height)
  writer.u8(dimension.hasSkyLight ? 1 : 0)
  writer.u8(enumId(CARDINAL_LIGHTING, dimension.cardinalLighting, 'cardinal lighting'))
  writer.f64(dimension.horizonHeight)
  writer.u8(dimension.endFlashes ? 1 : 0)
  writer.string(world.dimensionKey)
  writer.i64(world.clockTime)
  writer.string(world.biomeKey)
  writer.i32(world.seaLevel)
  writer.u8(world.blockLight)
  writer.u8(world.skyLight)
  writer.i32(world.logicalHeight)
  writer.u8(world.hasCeiling ? 1 : 0)
  writer.f32(world.ambientLight)
  writer.u8(enumId(EYE_MEDIA, world.eyeMedium, 'eye medium'))
  writer.u8(world.hasFixedTime ? 1 : 0)
}

function readPatchOp(reader) {
  const brickIndex = reader.u16()
  const op = reader.u8()
  if (op === OP_FULL) {
    const brick = readBrick(reader)
    if (brick.brickIndex !== brickIndex) throw new ClientViewProtocolError('FULL op brick index mismatch')
    return { op: 'FULL', brickIndex, brick }
  }
  if (op === OP_SPARSE) {
    const count = reader.checkedCount(reader.u16(), BRICK_CELLS, 3)
    const cellIndices = new Array(count)
    const paletteIds = new Array(count)
    for (let i = 0; i < count; i++) {
      cellIndices[i] = reader.u16()
      if (cellIndices[i] >= BRICK_CELLS) throw new ClientViewProtocolError('sparse cell outside the brick')
      paletteIds[i] = reader.varint(MAX_SESSION_PALETTE_SIZE - 1)
    }
    return { op: 'SPARSE', brickIndex, cellIndices, paletteIds }
  }
  if (op === OP_CLEAR) return { op: 'CLEAR', brickIndex }
  throw new ClientViewProtocolError(`unknown patch op ${op}`)
}

function writePatchOp(writer, op) {
  writer.u16(op.brickIndex)
  if (op.op === 'FULL') {
    writer.u8(OP_FULL)
    writeBrick(writer, op.brick)
  } else if (op.op === 'SPARSE') {
    writer.u8(OP_SPARSE)
    writer.u16(op.cellIndices.length)
    for (let i = 0; i < op.cellIndices.length; i++) {
      writer.u16(op.cellIndices[i])
      writer.varint(op.paletteIds[i])
    }
  } else if (op.op === 'CLEAR') {
    writer.u8(OP_CLEAR)
  } else {
    throw new ClientViewProtocolError(`unknown patch op ${op.op}`)
  }
}

function uuidFromLongs(most, least) {
  const hex = BigInt.asUintN(64, most).toString(16).padStart(16, '0') + BigInt.asUintN(64, least).toString(16).padStart(16, '0')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function uuidToLongs(uuid) {
  const hex = uuid.replaceAll('-', '')
  if (!/^[0-9a-f]{32}$/i.test(hex)) throw new ClientViewProtocolError(`invalid uuid ${uuid}`)
  return [BigInt(`0x${hex.slice(0, 16)}`), BigInt(`0x${hex.slice(16)}`)]
}

function enumName(names, id, label) {
  const name = names[id]
  if (name === undefined) throw new ClientViewProtocolError(`unknown ${label}`)
  return name
}

function enumId(names, name, label) {
  const id = names.indexOf(name)
  if (id < 0) throw new ClientViewProtocolError(`unknown ${label} ${name}`)
  return id
}

function readUuid(reader) {
  const most = reader.i64()
  return uuidFromLongs(most, reader.i64())
}

function writeUuid(writer, uuid) {
  const [most, least] = uuidToLongs(uuid)
  writer.i64(most)
  writer.i64(least)
}

function readVector(reader) {
  return { x: reader.f64(), y: reader.f64(), z: reader.f64() }
}

function writeVector(writer, vector) {
  writer.f64(vector.x)
  writer.f64(vector.y)
  writer.f64(vector.z)
}

function readPose(reader) {
  return { x: reader.f64(), y: reader.f64(), z: reader.f64(), yaw: reader.f32(), pitch: reader.f32() }
}

function writePose(writer, pose) {
  writer.f64(pose.x)
  writer.f64(pose.y)
  writer.f64(pose.z)
  writer.f32(pose.yaw)
  writer.f32(pose.pitch)
}

function readTravelWorld(reader) {
  return { dimension: reader.string(), dimensionType: reader.string(), seed: reader.i64(), debug: readFlag(reader, 'travel debug'),
    flat: readFlag(reader, 'travel flat'), seaLevel: reader.i32(), minY: reader.i32(), height: reader.i32() }
}

function writeTravelWorld(writer, world) {
  writer.string(world.dimension)
  writer.string(world.dimensionType)
  writer.i64(world.seed)
  writer.u8(world.debug ? 1 : 0)
  writer.u8(world.flat ? 1 : 0)
  writer.i32(world.seaLevel)
  writer.i32(world.minY)
  writer.i32(world.height)
}

function readArrivalRules(reader) {
  const orientation = enumName(ORIENTATION_RULES, reader.u8(), 'orientation rule')
  const gravityFlip = readFlag(reader, 'gravity flip')
  const mode = enumName(MOMENTUM_MODES, reader.u8(), 'momentum mode')
  const factor = reader.f64()
  const maxSpeed = reader.f64()
  const impulse = readVector(reader)
  const scale = { mode: enumName(SCALE_MODES, reader.u8(), 'scale mode'), min: reader.f32(), max: reader.f32() }
  if (!Number.isFinite(factor) || !Number.isFinite(maxSpeed) || maxSpeed < 0 || !Number.isFinite(scale.min) || !Number.isFinite(scale.max)
    || scale.min > scale.max) throw new ClientViewProtocolError('invalid travel arrival rules')
  return { orientation, gravityFlip, momentum: { mode, factor, maxSpeed, impulse }, scale }
}

function writeArrivalRules(writer, rules) {
  writer.u8(enumId(ORIENTATION_RULES, rules.orientation, 'orientation rule'))
  writer.u8(rules.gravityFlip ? 1 : 0)
  writer.u8(enumId(MOMENTUM_MODES, rules.momentum.mode, 'momentum mode'))
  writer.f64(rules.momentum.factor)
  writer.f64(rules.momentum.maxSpeed)
  writeVector(writer, rules.momentum.impulse)
  writer.u8(enumId(SCALE_MODES, rules.scale.mode, 'scale mode'))
  writer.f32(rules.scale.min)
  writer.f32(rules.scale.max)
}

function readTravelCount(reader) {
  const count = reader.u16()
  if (count <= 0 || count > MAX_TRAVEL_CHUNKS) throw new ClientViewProtocolError(`travel manifest count ${count}`)
  return count
}

function readFragment(reader) {
  const size = reader.i32()
  if (size <= 0 || size > TRAVEL_FRAGMENT_BYTES) throw new ClientViewProtocolError(`travel fragment size ${size}`)
  return reader.bytes(size)
}

function writeFragment(writer, payload) {
  writer.i32(payload.length)
  writer.bytes(payload)
}

function readMeshCount(reader, max, label) {
  const count = reader.u16()
  if (count > max) throw new ClientViewProtocolError(`${label} count ${count} exceeds ${max}`)
  return count
}

function readTravel(reader, type) {
  const token = readUuid(reader)
  const generation = reader.i64()
  switch (type) {
    case 'TRAVEL_BEGIN': {
      const sourcePortal = readUuid(reader)
      const sourceWorld = reader.string()
      const sourceGeometry = readGeometry(reader, 0)
      const destinationToSource = readTransform(reader)
      const scale = reader.f32()
      if (!Number.isFinite(scale) || scale <= 0) throw new ClientViewProtocolError('invalid travel scale')
      const world = readTravelWorld(reader)
      const arrival = readPose(reader)
      const count = readTravelCount(reader)
      const chunks = []
      for (let i = 0; i < count; i++) chunks.push({ x: reader.i32(), z: reader.i32() })
      return { type, token, generation, sourcePortal, sourceWorld, sourceGeometry, destinationToSource, scale, world, arrival, chunks,
        environment: readEnvironment(reader), expiresMillis: reader.i32(), rules: readArrivalRules(reader), resident: readFlag(reader, 'resident'),
        levelHandle: reader.u8() }
    }
    case 'TRAVEL_CHUNK':
      return { type, token, generation, chunkX: reader.i32(), chunkZ: reader.i32(), revision: reader.i32(), fragmentIndex: reader.u16(),
        fragmentCount: reader.u16(), totalBytes: reader.i32(), payload: readFragment(reader) }
    case 'TRAVEL_END': {
      const contentRevision = reader.i64()
      const count = readTravelCount(reader)
      const chunks = []
      for (let i = 0; i < count; i++) chunks.push({ x: reader.i32(), z: reader.i32(), revision: reader.i32() })
      return { type, token, generation, contentRevision, chunks }
    }
    case 'TRAVEL_COMMIT':
      return { type, token, generation, contentRevision: reader.i64(), sourceWorld: reader.string(), destinationWorld: reader.string(),
        arrival: readPose(reader), velocity: readVector(reader) }
    case 'TRAVEL_CANCEL':
      return { type, token, generation }
    case 'TRAVEL_CROSS':
      return { type, token, generation, contentRevision: reader.i64(), sourcePose: readPose(reader), previousEye: readVector(reader), currentEye: readVector(reader) }
    case 'TRAVEL_REUSE':
      return { type, token, generation, chunkX: reader.i32(), chunkZ: reader.i32(), revision: reader.i32(), hash: reader.bytes(TRAVEL_HASH_BYTES) }
    case 'TRAVEL_ACCEPT':
      return { type, token, generation, contentRevision: reader.i64(), pose: readPose(reader), velocity: readVector(reader), levelHandle: reader.u8(),
        dimensionChanged: readFlag(reader, 'dimension changed'), serverTick: reader.i64() }
    default:
      throw new ClientViewProtocolError(`unknown travel message ${type}`)
  }
}

function writeTravel(writer, message) {
  writeUuid(writer, message.token)
  writer.i64(message.generation)
  switch (message.type) {
    case 'TRAVEL_BEGIN':
      writeUuid(writer, message.sourcePortal)
      writer.string(message.sourceWorld)
      writeGeometry(writer, message.sourceGeometry, 0)
      writeTransform(writer, message.destinationToSource)
      writer.f32(message.scale)
      writeTravelWorld(writer, message.world)
      writePose(writer, message.arrival)
      writer.u16(message.chunks.length)
      for (const chunk of message.chunks) {
        writer.i32(chunk.x)
        writer.i32(chunk.z)
      }
      writeEnvironment(writer, message.environment)
      writer.i32(message.expiresMillis)
      writeArrivalRules(writer, message.rules)
      writer.u8(message.resident ? 1 : 0)
      writer.u8(message.levelHandle)
      return
    case 'TRAVEL_CHUNK':
      writer.i32(message.chunkX)
      writer.i32(message.chunkZ)
      writer.i32(message.revision)
      writer.u16(message.fragmentIndex)
      writer.u16(message.fragmentCount)
      writer.i32(message.totalBytes)
      writeFragment(writer, message.payload)
      return
    case 'TRAVEL_END':
      writer.i64(message.contentRevision)
      writer.u16(message.chunks.length)
      for (const chunk of message.chunks) {
        writer.i32(chunk.x)
        writer.i32(chunk.z)
        writer.i32(chunk.revision)
      }
      return
    case 'TRAVEL_COMMIT':
      writer.i64(message.contentRevision)
      writer.string(message.sourceWorld)
      writer.string(message.destinationWorld)
      writePose(writer, message.arrival)
      writeVector(writer, message.velocity)
      return
    case 'TRAVEL_CANCEL':
      return
    case 'TRAVEL_CROSS':
      writer.i64(message.contentRevision)
      writePose(writer, message.sourcePose)
      writeVector(writer, message.previousEye)
      writeVector(writer, message.currentEye)
      return
    case 'TRAVEL_REUSE':
      writer.i32(message.chunkX)
      writer.i32(message.chunkZ)
      writer.i32(message.revision)
      if (message.hash.length !== TRAVEL_HASH_BYTES) throw new ClientViewProtocolError(`travel hash of ${message.hash.length} bytes`)
      writer.bytes(message.hash)
      return
    case 'TRAVEL_ACCEPT':
      writer.i64(message.contentRevision)
      writePose(writer, message.pose)
      writeVector(writer, message.velocity)
      writer.u8(message.levelHandle)
      writer.u8(message.dimensionChanged ? 1 : 0)
      writer.i64(message.serverTick)
      return
    default:
      throw new ClientViewProtocolError(`unknown travel message ${message.type}`)
  }
}

export function readBody(reader, type, caps) {
  switch (type) {
    case 'OFFER':
      return { type, wire: reader.u16(), mcDataVersion: reader.i32(), serverCaps: reader.u64(), maxFrameBytes: reader.i32(), zeroCopyNonce: reader.i64() }
    case 'HELLO':
      return { type, wire: reader.u16(), mcDataVersion: reader.i32(), clientCaps: reader.u64(), maxFrameBytes: reader.i32(), plateMemoryMb: reader.u16(), zeroCopyNonceEcho: reader.i64(), brandTag: reader.string() }
    case 'ACCEPT':
      return { type, sessionId: reader.u32(), caps: reader.u64(), tickRate: reader.u8(), maxFrameBytes: reader.i32(), hashSalt: reader.i64(), ackWindowFrames: reader.u8() }
    case 'DECLINE':
      return { type, reason: enumName(DECLINE_REASONS, reader.u8(), 'decline reason') }
    case 'PALETTE': {
      const count = reader.checkedCount(reader.varint(), MAX_PALETTE_ENTRIES_PER_MESSAGE, 2)
      const entries = []
      for (let i = 0; i < count; i++) {
        const id = reader.varint(MAX_SESSION_PALETTE_SIZE - 1)
        entries.push({ id, state: reader.string() })
      }
      return { type, entries }
    }
    case 'PORTAL':
      return { type, portalKey: reader.varint(), geometryRevision: reader.u32(), geometry: readGeometry(reader, 0) }
    case 'PORTAL_DROP':
      return { type, portalKey: reader.varint() }
    case 'MESH_BEGIN': {
      const message = { type, portalKey: reader.varint(), generation: reader.u32(), bounds: { minX: reader.i32(), minY: reader.i32(), minZ: reader.i32(), sizeX: reader.u16(), sizeY: reader.u16(), sizeZ: reader.u16() }, maxResidentSections: reader.varint() }
      if (!message.maxResidentSections || !message.bounds.sizeX || !message.bounds.sizeY || !message.bounds.sizeZ) throw new ClientViewProtocolError('mesh view requires nonempty bounds and a resident budget')
      return message
    }
    case 'MESH_SECTION': {
      const message = { type, portalKey: reader.varint(), generation: reader.u32(), sectionX: reader.i32(), sectionY: reader.i32(), sectionZ: reader.i32(), revision: reader.u32(), backingState: reader.varint(MAX_SESSION_PALETTE_SIZE - 1), brick: readBrick(reader) }
      if (message.brick.brickIndex !== 0) throw new ClientViewProtocolError('mesh section brick index must be zero')
      const biomeCount = reader.u16()
      if (biomeCount > 512) throw new ClientViewProtocolError('section biome palette exceeds 512 entries')
      const palette = Array.from({ length: biomeCount }, () => reader.string())
      const indices = biomeCount > 1 ? reader.bytes(1024) : Buffer.alloc(0)
      for (let offset = 0; offset < indices.length; offset += 2) {
        if (indices.readUInt16LE(offset) >= biomeCount) throw new ClientViewProtocolError('biome index exceeds palette')
      }
      message.biomes = { palette, indices }
      return message
    }
    case 'MESH_DROP':
      return { type, portalKey: reader.varint(), generation: reader.u32(), sectionX: reader.i32(), sectionY: reader.i32(), sectionZ: reader.i32() }
    case 'MESH_ACK':
      return { type, portalKey: reader.varint(), generation: reader.u32(), sectionX: reader.i32(), sectionY: reader.i32(), sectionZ: reader.i32(), revision: reader.u32() }
    case 'PLATE_BEGIN': {
      const portalKey = reader.varint()
      const plateRevision = reader.u32()
      const sections = { minSectionX: reader.i32(), minSectionY: reader.i32(), minSectionZ: reader.i32(), sizeX: reader.u8(), sizeY: reader.u8(), sizeZ: reader.u8() }
      if (sections.sizeX * sections.sizeY * sections.sizeZ > MAX_BRICKS_PER_PLATE) throw new ClientViewProtocolError('section box exceeds the brick cap')
      const cells = { minX: reader.i32(), minY: reader.i32(), minZ: reader.i32(), sizeX: reader.u16(), sizeY: reader.u16(), sizeZ: reader.u16() }
      const backingState = reader.varint(MAX_SESSION_PALETTE_SIZE - 1)
      const brickCount = reader.u16()
      if (brickCount !== sections.sizeX * sections.sizeY * sections.sizeZ) throw new ClientViewProtocolError(`brick count ${brickCount} does not match the section box`)
      let brickHashes = null
      if (hasCapability(caps, 'BRICK_CACHE') && (brickCount === 0 || reader.remaining() > 0)) {
        if (reader.remaining() !== brickCount * 8) throw new ClientViewProtocolError('brick hash manifest does not match the brick count')
        brickHashes = reader.longs(brickCount)
      }
      return { type, portalKey, plateRevision, sections, cells, backingState, brickCount, brickHashes }
    }
    case 'PLATE_BRICKS': {
      const portalKey = reader.varint()
      const plateRevision = reader.u32()
      const count = reader.checkedCount(reader.u16(), MAX_BRICKS_PER_PLATE, 5)
      const bricks = []
      for (let i = 0; i < count; i++) bricks.push(readBrick(reader))
      return { type, portalKey, plateRevision, bricks }
    }
    case 'PLATE_END':
      return { type, portalKey: reader.varint(), plateRevision: reader.u32() }
    case 'PLATE_PATCH': {
      const portalKey = reader.varint()
      const fromRevision = reader.u32()
      const toRevision = reader.u32()
      const count = reader.checkedCount(reader.u16(), MAX_PATCH_OPS, 3)
      const ops = []
      for (let i = 0; i < count; i++) ops.push(readPatchOp(reader))
      return { type, portalKey, fromRevision, toRevision, ops }
    }
    case 'PLATE_HANDLE':
      return { type, portalKey: reader.varint(), plateRevision: reader.u32(), handle: reader.i64() }
    case 'BRICK_MISS': {
      const count = reader.checkedCount(reader.u8(), MAX_BRICK_MISS_PLATES, 6)
      if (count === 0) throw new ClientViewProtocolError('brick miss without plates')
      const plates = []
      for (let i = 0; i < count; i++) {
        const portalKey = reader.varint()
        const plateRevision = reader.u32()
        const words = reader.checkedCount(reader.varint(), MAX_BRICK_MISS_WORDS, 8)
        plates.push({ portalKey, plateRevision, bitset: reader.longs(words) })
      }
      return { type, plates }
    }
    case 'ENTITY_FRAME': {
      const portalKey = reader.varint()
      const entitySeq = reader.u32()
      const count = reader.checkedCount(reader.u8(), MAX_ENTITIES_PER_FRAME, 1)
      const entities = []
      for (let i = 0; i < count; i++) entities.push(reader.bytes(reader.varint(MAX_ENTITY_VISUAL_BYTES)))
      const presentCount = reader.u16()
      if (presentCount === PRESENCE_UNCHANGED) return { type, portalKey, entitySeq, entities, presentIds: [], presence: false }
      const present = reader.checkedCount(presentCount, MAX_PRESENT_IDS_PER_FRAME, 16)
      const presentIds = []
      for (let i = 0; i < present; i++) {
        const most = reader.i64()
        presentIds.push(uuidFromLongs(most, reader.i64()))
      }
      return { type, portalKey, entitySeq, entities, presentIds, presence: true }
    }
    case 'FX': {
      const portalKey = reader.varint()
      const count = reader.checkedCount(reader.u8(), MAX_FX_EMITTERS, 37)
      const emitters = []
      for (let i = 0; i < count; i++) {
        emitters.push({
          kind: enumName(FX_KINDS, reader.u8(), 'fx kind'),
          key: reader.string(),
          x: reader.f64(),
          y: reader.f64(),
          z: reader.f64(),
          paramA: reader.f32(),
          paramB: reader.f32(),
          ticks: reader.u16(),
          flags: reader.u8()
        })
      }
      return { type, portalKey, emitters }
    }
    case 'ENVIRONMENT':
      return { type, portalKey: reader.varint(), environment: readEnvironment(reader) }
    case 'ENTITY_EVENT': {
      const message = { type, portalKey: reader.varint(), eventSeq: reader.u32(), entityId: readUuid(reader), hurt: readFlag(reader, 'entity hurt'),
        animation: reader.u8(), yaw: reader.f32() }
      if (!Number.isFinite(message.yaw) || (!message.hurt && ![0, 2, 3, 4, 5].includes(message.animation))) throw new ClientViewProtocolError('invalid entity event')
      return message
    }
    case 'ENTITY_SELF':
      return { type, projectedId: readUuid(reader) }
    case 'MESH_LOCAL': {
      const portalKey = reader.varint()
      const generation = reader.i32()
      const sequence = reader.i32()
      const available = reader.u8()
      const sectionCount = readMeshCount(reader, MAX_MESH_LOCAL_SECTIONS, 'local mesh section')
      if (generation <= 0 || sequence <= 0 || available > 1) throw new ClientViewProtocolError('invalid local mesh availability')
      const sections = []
      for (let i = 0; i < sectionCount; i++) sections.push({ x: reader.i32(), y: reader.i32(), z: reader.i32() })
      const entityCount = readMeshCount(reader, MAX_MESH_LOCAL_ENTITIES, 'local mesh entity')
      const entities = []
      for (let i = 0; i < entityCount; i++) entities.push(readUuid(reader))
      return { type, portalKey, generation, sequence, available: available === 1, sections, entities }
    }
    case 'MESH_CACHED': {
      const portalKey = reader.varint()
      const generation = reader.i32()
      const sequence = reader.i32()
      const available = reader.u8()
      const count = readMeshCount(reader, MAX_MESH_CLAIMS, 'mesh cache claim')
      if (generation <= 0 || sequence <= 0 || available > 1) throw new ClientViewProtocolError('invalid mesh cache claims')
      const claims = []
      for (let i = 0; i < count; i++) claims.push({ x: reader.i32(), y: reader.i32(), z: reader.i32(), hash: reader.i64() })
      return { type, portalKey, generation, sequence, available: available === 1, claims }
    }
    case 'MESH_REUSE': {
      const message = { type, portalKey: reader.varint(), generation: reader.i32(), sectionX: reader.i32(), sectionY: reader.i32(), sectionZ: reader.i32(),
        revision: reader.i32(), hash: reader.i64() }
      if (message.generation <= 0 || message.revision <= 0) throw new ClientViewProtocolError('invalid mesh reuse acknowledgment')
      return message
    }
    case 'TRAVEL_BEGIN':
    case 'TRAVEL_CHUNK':
    case 'TRAVEL_END':
    case 'TRAVEL_COMMIT':
    case 'TRAVEL_CANCEL':
    case 'TRAVEL_CROSS':
    case 'TRAVEL_REUSE':
    case 'TRAVEL_ACCEPT':
      return readTravel(reader, type)
    case 'REMOTE_LEVEL_OPEN':
      return { type, levelHandle: reader.u8(), world: readTravelWorld(reader), environment: readEnvironment(reader), viewRadius: reader.u8(),
        center: { x: reader.i32(), z: reader.i32() } }
    case 'REMOTE_LEVEL_CLOSE':
      return { type, levelHandle: reader.u8() }
    case 'ROUTED_PACKET':
      return { type, levelHandle: reader.u8(), sequence: reader.i32(), fragmentIndex: reader.u16(), fragmentCount: reader.u16(), totalBytes: reader.i32(),
        payload: readFragment(reader) }
    case 'REMOTE_VIEW_ACK':
      return { type, levelHandle: reader.u8(), lastSequence: reader.i32(), chunksPerTickHint: reader.u8() }
    case 'ATMOSPHERE':
      return { type, portalKey: reader.varint(), dayTime: reader.i64(), rain: reader.f32(), thunder: reader.f32(), flags: reader.u8() }
    case 'SESSION_RESET':
      return { type, reason: enumName(RESET_REASONS, reader.u8(), 'reset reason') }
    case 'ACK':
      return { type, seq: reader.u32(), clientTick: reader.u32(), appliedCells: reader.u32() }
    case 'VIEW_STATS':
      return { type, clientTick: reader.u32(), attended: reader.u16(), overlayCells: reader.u32(), unknownStates: reader.u16(), sweepMicrosP50: reader.u16(), applyMicrosP50: reader.u16(), plateMb: reader.u16() }
    case 'PLATE_REFUSED':
      return { type, portalKey: reader.varint(), plateRevision: reader.u32() }
    default:
      throw new ClientViewProtocolError(`unknown message type ${type}`)
  }
}

export function writeBody(writer, message) {
  switch (message.type) {
    case 'OFFER':
      writer.u16(message.wire)
      writer.i32(message.mcDataVersion)
      writer.u64(message.serverCaps)
      writer.i32(message.maxFrameBytes)
      writer.i64(message.zeroCopyNonce)
      return
    case 'HELLO':
      writer.u16(message.wire)
      writer.i32(message.mcDataVersion)
      writer.u64(message.clientCaps)
      writer.i32(message.maxFrameBytes)
      writer.u16(message.plateMemoryMb)
      writer.i64(message.zeroCopyNonceEcho)
      writer.string(message.brandTag)
      return
    case 'ACCEPT':
      writer.u32(message.sessionId)
      writer.u64(message.caps)
      writer.u8(message.tickRate)
      writer.i32(message.maxFrameBytes)
      writer.i64(message.hashSalt)
      writer.u8(message.ackWindowFrames)
      return
    case 'DECLINE':
      writer.u8(enumId(DECLINE_REASONS, message.reason, 'decline reason'))
      return
    case 'PALETTE':
      if (message.entries.length > MAX_PALETTE_ENTRIES_PER_MESSAGE) throw new ClientViewProtocolError(`palette message with ${message.entries.length} entries`)
      writer.varint(message.entries.length)
      for (const entry of message.entries) {
        writer.varint(entry.id)
        writer.string(entry.state)
      }
      return
    case 'PORTAL':
      writer.varint(message.portalKey)
      writer.u32(message.geometryRevision)
      writeGeometry(writer, message.geometry, 0)
      return
    case 'PORTAL_DROP':
      writer.varint(message.portalKey)
      return
    case 'MESH_BEGIN':
      writer.varint(message.portalKey)
      writer.u32(message.generation)
      writer.i32(message.bounds.minX)
      writer.i32(message.bounds.minY)
      writer.i32(message.bounds.minZ)
      writer.u16(message.bounds.sizeX)
      writer.u16(message.bounds.sizeY)
      writer.u16(message.bounds.sizeZ)
      writer.varint(message.maxResidentSections)
      return
    case 'MESH_SECTION':
    case 'MESH_DROP':
    case 'MESH_ACK':
      writer.varint(message.portalKey)
      writer.u32(message.generation)
      writer.i32(message.sectionX)
      writer.i32(message.sectionY)
      writer.i32(message.sectionZ)
      if (message.type !== 'MESH_DROP') writer.u32(message.revision)
      if (message.type === 'MESH_SECTION') {
        writer.varint(message.backingState)
        writeBrick(writer, message.brick)
        const biomes = message.biomes ?? { palette: [], indices: Buffer.alloc(0) }
        if (biomes.palette.length > 512 || biomes.indices.length !== (biomes.palette.length > 1 ? 1024 : 0)) {
          throw new ClientViewProtocolError('invalid section biome palette or indices')
        }
        for (let offset = 0; offset < biomes.indices.length; offset += 2) {
          if (biomes.indices.readUInt16LE(offset) >= biomes.palette.length) throw new ClientViewProtocolError('biome index exceeds palette')
        }
        writer.u16(biomes.palette.length)
        for (const biome of biomes.palette) writer.string(biome)
        writer.bytes(biomes.indices)
      }
      return
    case 'PLATE_BEGIN': {
      const { sections, cells } = message
      writer.varint(message.portalKey)
      writer.u32(message.plateRevision)
      writer.i32(sections.minSectionX)
      writer.i32(sections.minSectionY)
      writer.i32(sections.minSectionZ)
      writer.u8(sections.sizeX)
      writer.u8(sections.sizeY)
      writer.u8(sections.sizeZ)
      writer.i32(cells.minX)
      writer.i32(cells.minY)
      writer.i32(cells.minZ)
      writer.u16(cells.sizeX)
      writer.u16(cells.sizeY)
      writer.u16(cells.sizeZ)
      writer.varint(message.backingState)
      writer.u16(message.brickCount)
      if (message.brickHashes) {
        if (message.brickHashes.length !== message.brickCount) throw new ClientViewProtocolError('hash manifest does not match the brick count')
        writer.longs(message.brickHashes)
      }
      return
    }
    case 'PLATE_BRICKS':
      writer.varint(message.portalKey)
      writer.u32(message.plateRevision)
      writer.u16(message.bricks.length)
      for (const brick of message.bricks) writeBrick(writer, brick)
      return
    case 'PLATE_END':
      writer.varint(message.portalKey)
      writer.u32(message.plateRevision)
      return
    case 'PLATE_PATCH':
      writer.varint(message.portalKey)
      writer.u32(message.fromRevision)
      writer.u32(message.toRevision)
      writer.u16(message.ops.length)
      for (const op of message.ops) writePatchOp(writer, op)
      return
    case 'PLATE_HANDLE':
      writer.varint(message.portalKey)
      writer.u32(message.plateRevision)
      writer.i64(message.handle)
      return
    case 'BRICK_MISS':
      writer.u8(message.plates.length)
      for (const plate of message.plates) {
        writer.varint(plate.portalKey)
        writer.u32(plate.plateRevision)
        writer.varint(plate.bitset.length)
        writer.longs(plate.bitset)
      }
      return
    case 'ENTITY_FRAME':
      writer.varint(message.portalKey)
      writer.u32(message.entitySeq)
      writer.u8(message.entities.length)
      for (const entity of message.entities) {
        writer.varint(entity.length)
        writer.bytes(entity)
      }
      if (message.presence === false) {
        writer.u16(PRESENCE_UNCHANGED)
        return
      }
      writer.u16(message.presentIds.length)
      for (const id of message.presentIds) {
        const [most, least] = uuidToLongs(id)
        writer.i64(most)
        writer.i64(least)
      }
      return
    case 'FX':
      writer.varint(message.portalKey)
      writer.u8(message.emitters.length)
      for (const emitter of message.emitters) {
        writer.u8(enumId(FX_KINDS, emitter.kind, 'fx kind'))
        writer.string(emitter.key)
        writer.f64(emitter.x)
        writer.f64(emitter.y)
        writer.f64(emitter.z)
        writer.f32(emitter.paramA)
        writer.f32(emitter.paramB)
        writer.u16(emitter.ticks)
        writer.u8(emitter.flags)
      }
      return
    case 'ENVIRONMENT':
      writer.varint(message.portalKey)
      writeEnvironment(writer, message.environment)
      return
    case 'ENTITY_EVENT':
      writer.varint(message.portalKey)
      writer.u32(message.eventSeq)
      writeUuid(writer, message.entityId)
      writer.u8(message.hurt ? 1 : 0)
      writer.u8(message.animation)
      writer.f32(message.yaw)
      return
    case 'ENTITY_SELF':
      writeUuid(writer, message.projectedId)
      return
    case 'MESH_LOCAL':
      writer.varint(message.portalKey)
      writer.i32(message.generation)
      writer.i32(message.sequence)
      writer.u8(message.available ? 1 : 0)
      writer.u16(message.sections.length)
      for (const section of message.sections) {
        writer.i32(section.x)
        writer.i32(section.y)
        writer.i32(section.z)
      }
      writer.u16(message.entities.length)
      for (const entity of message.entities) writeUuid(writer, entity)
      return
    case 'MESH_CACHED':
      writer.varint(message.portalKey)
      writer.i32(message.generation)
      writer.i32(message.sequence)
      writer.u8(message.available ? 1 : 0)
      writer.u16(message.claims.length)
      for (const claim of message.claims) {
        writer.i32(claim.x)
        writer.i32(claim.y)
        writer.i32(claim.z)
        writer.i64(claim.hash)
      }
      return
    case 'MESH_REUSE':
      writer.varint(message.portalKey)
      writer.i32(message.generation)
      writer.i32(message.sectionX)
      writer.i32(message.sectionY)
      writer.i32(message.sectionZ)
      writer.i32(message.revision)
      writer.i64(message.hash)
      return
    case 'TRAVEL_BEGIN':
    case 'TRAVEL_CHUNK':
    case 'TRAVEL_END':
    case 'TRAVEL_COMMIT':
    case 'TRAVEL_CANCEL':
    case 'TRAVEL_CROSS':
    case 'TRAVEL_REUSE':
    case 'TRAVEL_ACCEPT':
      writeTravel(writer, message)
      return
    case 'REMOTE_LEVEL_OPEN':
      writer.u8(message.levelHandle)
      writeTravelWorld(writer, message.world)
      writeEnvironment(writer, message.environment)
      writer.u8(message.viewRadius)
      writer.i32(message.center.x)
      writer.i32(message.center.z)
      return
    case 'REMOTE_LEVEL_CLOSE':
      writer.u8(message.levelHandle)
      return
    case 'ROUTED_PACKET':
      writer.u8(message.levelHandle)
      writer.i32(message.sequence)
      writer.u16(message.fragmentIndex)
      writer.u16(message.fragmentCount)
      writer.i32(message.totalBytes)
      writeFragment(writer, message.payload)
      return
    case 'REMOTE_VIEW_ACK':
      writer.u8(message.levelHandle)
      writer.i32(message.lastSequence)
      writer.u8(message.chunksPerTickHint)
      return
    case 'ATMOSPHERE':
      writer.varint(message.portalKey)
      writer.i64(message.dayTime)
      writer.f32(message.rain)
      writer.f32(message.thunder)
      writer.u8(message.flags)
      return
    case 'SESSION_RESET':
      writer.u8(enumId(RESET_REASONS, message.reason, 'reset reason'))
      return
    case 'ACK':
      writer.u32(message.seq)
      writer.u32(message.clientTick)
      writer.u32(message.appliedCells)
      return
    case 'VIEW_STATS':
      writer.u32(message.clientTick)
      writer.u16(message.attended)
      writer.u32(message.overlayCells)
      writer.u16(message.unknownStates)
      writer.u16(message.sweepMicrosP50)
      writer.u16(message.applyMicrosP50)
      writer.u16(message.plateMb)
      return
    case 'PLATE_REFUSED':
      writer.varint(message.portalKey)
      writer.u32(message.plateRevision)
      return
    default:
      throw new ClientViewProtocolError(`unknown message type ${message.type}`)
  }
}

export function decodeS2C(payload, caps = 0n) {
  const buffer = Buffer.isBuffer(payload) ? payload : Buffer.from(payload)
  if (buffer.length > HARD_MAX_FRAME_BYTES) throw new ClientViewProtocolError(`S2C payload of ${buffer.length} bytes exceeds the hard cap`)
  const header = new ByteReader(buffer)
  const id = header.u8()
  const type = typeName(id)
  if (type === undefined || MESSAGE_TYPES[type].direction === 'C2S') throw new ClientViewProtocolError(`unknown clientbound message type ${id}`)
  const seq = header.u32()
  const flags = header.u8()
  if ((flags & ~FLAG_MASK) !== 0) throw new ClientViewProtocolError(`unknown frame flags ${flags}`)
  let body = header
  let bodyBytes = header.remaining()
  if ((flags & FLAG_DEFLATED) !== 0) {
    let inflated
    try {
      inflated = inflateSync(buffer.subarray(header.position), { maxOutputLength: HARD_MAX_FRAME_BYTES })
    } catch (error) {
      throw new ClientViewProtocolError(`corrupt deflate stream: ${error.message}`, { cause: error })
    }
    body = new ByteReader(inflated)
    bodyBytes = inflated.length
  }
  const message = readBody(body, type, BigInt(caps))
  body.expectEnd()
  return { type, id, seq, flags, last: (flags & FLAG_LAST) !== 0, deflated: (flags & FLAG_DEFLATED) !== 0, bytes: buffer.length, bodyBytes, message }
}

export function decodeC2S(payload) {
  const buffer = Buffer.isBuffer(payload) ? payload : Buffer.from(payload)
  if (buffer.length > MAX_C2S_BYTES) throw new ClientViewProtocolError(`C2S payload of ${buffer.length} bytes exceeds the cap`)
  const reader = new ByteReader(buffer)
  const id = reader.u8()
  const type = typeName(id)
  if (type === undefined || MESSAGE_TYPES[type].direction === 'S2C') throw new ClientViewProtocolError(`unknown serverbound message type ${id}`)
  const message = readBody(reader, type, 0n)
  reader.expectEnd()
  return message
}

export function encodeBody(message) {
  const writer = new ByteWriter(256)
  writeBody(writer, message)
  return writer.toBuffer()
}

export function encodeS2C(message, seq, flags = 0, { deflate = false } = {}) {
  const type = MESSAGE_TYPES[message.type]
  if (!type || type.direction === 'C2S') throw new ClientViewProtocolError(`${message.type} is not clientbound`)
  if ((flags & ~(FLAG_LAST | FLAG_RESERVED)) !== 0) throw new ClientViewProtocolError(`caller flags ${flags} are not allowed`)
  let body = encodeBody(message)
  let outFlags = flags
  if (deflate && body.length >= DEFLATE_THRESHOLD_BYTES) {
    const deflated = deflateSync(body)
    if (deflated.length < body.length) {
      body = deflated
      outFlags |= FLAG_DEFLATED
    }
  }
  if (body.length + S2C_HEADER_BYTES > HARD_MAX_FRAME_BYTES) throw new ClientViewProtocolError(`${message.type} frame exceeds the hard cap`)
  const writer = new ByteWriter(S2C_HEADER_BYTES + body.length)
  writer.u8(type.id)
  writer.u32(seq)
  writer.u8(outFlags)
  writer.bytes(body)
  return writer.toBuffer()
}

export function encodeC2S(message) {
  const type = MESSAGE_TYPES[message.type]
  if (!type || type.direction === 'S2C') throw new ClientViewProtocolError(`${message.type} is not serverbound`)
  const writer = new ByteWriter(64)
  writer.u8(type.id)
  writeBody(writer, message)
  if (writer.size > MAX_C2S_BYTES) throw new ClientViewProtocolError(`${message.type} payload of ${writer.size} bytes exceeds the C2S cap`)
  return writer.toBuffer()
}

export function peekType(payload) {
  if (!payload || payload.length === 0) return undefined
  return typeName(payload[0])
}

export function hello({ offer, mcDataVersion, clientCaps, maxFrameBytes = DEFAULT_MAX_FRAME_BYTES, plateMemoryMb = 256, nonceFound = 0n, brandTag = '' }) {
  const nonce = BigInt(nonceFound)
  const echo = offer && offer.zeroCopyNonce !== 0n && offer.zeroCopyNonce === nonce ? nonce : 0n
  return {
    type: 'HELLO',
    wire: WIRE_VERSION,
    mcDataVersion,
    clientCaps: BigInt(clientCaps) & ALL_CAPS,
    maxFrameBytes: clampMaxFrameBytes(maxFrameBytes),
    plateMemoryMb: Math.max(0, Math.min(65535, plateMemoryMb)),
    zeroCopyNonceEcho: echo,
    brandTag
  }
}

export function summarize(message) {
  switch (message.type) {
    case 'OFFER':
      return { wire: message.wire, mcDataVersion: message.mcDataVersion, serverCaps: capabilityNames(message.serverCaps), maxFrameBytes: message.maxFrameBytes, zeroCopyNonce: message.zeroCopyNonce !== 0n }
    case 'HELLO':
      return { wire: message.wire, mcDataVersion: message.mcDataVersion, clientCaps: capabilityNames(message.clientCaps), maxFrameBytes: message.maxFrameBytes, plateMemoryMb: message.plateMemoryMb, zeroCopyNonceEcho: message.zeroCopyNonceEcho !== 0n, brandTag: message.brandTag }
    case 'ACCEPT':
      return { sessionId: message.sessionId, caps: capabilityNames(message.caps), tickRate: message.tickRate, maxFrameBytes: message.maxFrameBytes, hashSalt: hex64(message.hashSalt), ackWindowFrames: message.ackWindowFrames }
    case 'DECLINE':
    case 'SESSION_RESET':
      return { reason: message.reason }
    case 'PALETTE':
      return { entries: message.entries.length, firstId: message.entries[0]?.id, lastId: message.entries.at(-1)?.id }
    case 'PORTAL': {
      const geometry = message.geometry
      return { portalKey: message.portalKey, geometryRevision: message.geometryRevision, origin: [geometry.originX, geometry.originY, geometry.originZ], facing: geometry.facing, aperture: [geometry.apertureWidth, geometry.apertureHeight], openCells: apertureOpenCells(geometry), depthBlocks: geometry.depthBlocks, kind: PORTAL_KINDS[geometry.kind] ?? geometry.kind, parentPortalKey: geometry.parentPortalKey, nested: geometry.nested.length }
    }
    case 'PORTAL_DROP':
      return { portalKey: message.portalKey }
    case 'PLATE_BEGIN':
      return { portalKey: message.portalKey, plateRevision: message.plateRevision, sections: message.sections, cells: message.cells, backingState: message.backingState, brickCount: message.brickCount, hashes: message.brickHashes !== null }
    case 'PLATE_BRICKS': {
      const encodings = { EMPTY: 0, SINGLE: 0, PALETTED: 0 }
      let light = 0
      let blockEntities = 0
      let brickBytes = 0
      for (const brick of message.bricks) {
        encodings[brick.encoding]++
        if (brick.blockLight) light++
        blockEntities += brick.blockEntities.length
        brickBytes += brick.bytes
      }
      return { portalKey: message.portalKey, plateRevision: message.plateRevision, bricks: message.bricks.length, encodings, light, blockEntities, brickBytes }
    }
    case 'PLATE_END':
      return { portalKey: message.portalKey, plateRevision: message.plateRevision }
    case 'PLATE_PATCH': {
      const ops = { FULL: 0, SPARSE: 0, CLEAR: 0 }
      for (const op of message.ops) ops[op.op]++
      return { portalKey: message.portalKey, fromRevision: message.fromRevision, toRevision: message.toRevision, ops }
    }
    case 'PLATE_HANDLE':
      return { portalKey: message.portalKey, plateRevision: message.plateRevision }
    case 'BRICK_MISS':
      return { plates: message.plates.map((plate) => ({ portalKey: plate.portalKey, plateRevision: plate.plateRevision, missed: brickMissIndices(plate.bitset).length })) }
    case 'ENTITY_FRAME':
      return { portalKey: message.portalKey, entitySeq: message.entitySeq, entities: message.entities.length, present: message.presentIds.length, presence: message.presence }
    case 'FX':
      return { portalKey: message.portalKey, emitters: message.emitters.map((emitter) => emitter.kind) }
    case 'ENVIRONMENT':
      return { portalKey: message.portalKey, skybox: message.environment.sky.skybox, gameTime: Number(message.environment.gameTime), dimension: message.environment.world.dimensionKey }
    case 'ENTITY_EVENT':
      return { portalKey: message.portalKey, eventSeq: message.eventSeq, entityId: message.entityId, hurt: message.hurt, animation: message.animation }
    case 'ENTITY_SELF':
      return { projectedId: message.projectedId }
    case 'MESH_LOCAL':
      return { portalKey: message.portalKey, generation: message.generation, sections: message.sections.length, entities: message.entities.length }
    case 'MESH_CACHED':
      return { portalKey: message.portalKey, generation: message.generation, claims: message.claims.length }
    case 'MESH_REUSE':
      return { portalKey: message.portalKey, generation: message.generation, section: [message.sectionX, message.sectionY, message.sectionZ], revision: message.revision }
    case 'TRAVEL_BEGIN':
      return { token: message.token, generation: Number(message.generation), sourceWorld: message.sourceWorld, destination: message.world.dimension, chunks: message.chunks.length, resident: message.resident }
    case 'TRAVEL_CHUNK':
      return { token: message.token, chunk: [message.chunkX, message.chunkZ], fragment: `${message.fragmentIndex + 1}/${message.fragmentCount}`, bytes: message.payload.length }
    case 'TRAVEL_END':
    case 'TRAVEL_COMMIT':
    case 'TRAVEL_CANCEL':
    case 'TRAVEL_CROSS':
    case 'TRAVEL_ACCEPT':
      return { token: message.token, generation: Number(message.generation) }
    case 'TRAVEL_REUSE':
      return { token: message.token, chunk: [message.chunkX, message.chunkZ], revision: message.revision }
    case 'REMOTE_LEVEL_OPEN':
      return { levelHandle: message.levelHandle, dimension: message.world.dimension, viewRadius: message.viewRadius }
    case 'REMOTE_LEVEL_CLOSE':
      return { levelHandle: message.levelHandle }
    case 'ROUTED_PACKET':
      return { levelHandle: message.levelHandle, sequence: message.sequence, bytes: message.payload.length }
    case 'REMOTE_VIEW_ACK':
      return { levelHandle: message.levelHandle, lastSequence: message.lastSequence }
    case 'ATMOSPHERE':
      return { portalKey: message.portalKey, dayTime: Number(message.dayTime), rain: message.rain, thunder: message.thunder, flags: message.flags }
    case 'ACK':
      return { seq: message.seq, clientTick: message.clientTick, appliedCells: message.appliedCells }
    case 'VIEW_STATS':
      return { clientTick: message.clientTick, attended: message.attended, overlayCells: message.overlayCells }
    case 'PLATE_REFUSED':
      return { portalKey: message.portalKey, plateRevision: message.plateRevision }
    default:
      return {}
  }
}
