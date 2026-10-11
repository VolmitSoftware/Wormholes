import { readFile, realpath } from 'node:fs/promises'
import { createRequire } from 'node:module'
import path from 'node:path'
import { gunzipSync, inflateSync } from 'node:zlib'

const PROMPT_UPDATE_MS = 10000
const CLAIM = { x: 8, y: 193, z: 4 }
const NEIGHBOR = { x: 9, y: 193, z: 4 }
const UNRELATED = { x: 2, y: 193, z: 13 }
const SPOOFED = new Set(['minecraft:diamond_block', 'minecraft:emerald_block', 'minecraft:lapis_block'])

export default {
  name: 'wormholes-fawe-projection',
  description: 'Apply queued and cancelled native FAWE edits through real portals, verify reload waiting and undo/redo, preserve claims across terrain packets, and check saved terrain.',
  async run(context) {
    const { bot } = context
    context.expect(context.server.host === '127.0.0.1' && context.server.directory,
      'FAWE acceptance requires a local managed instance')
    const metadata = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
    context.expect(/^isolated=true\s*$/m.test(metadata), 'FAWE acceptance requires an isolated instance')
    context.expect(context.report.viewer?.url && context.report.viewer.status === 'active',
      'Keep the Prismarine Viewer enabled for this scenario')
    const viewerState = JSON.parse(await readFile(context.report.viewer.stateFile, 'utf8'))
    context.expect(viewerState.url === context.report.viewer.url && viewerState.status === 'active',
      'The active viewer state and report must record the same URL')
    const require = createRequire(process.argv[1])
    const nbt = require('prismarine-nbt')
    const evidence = { viewerUrl: context.report.viewer.url, updates: [], nativeBatches: [], terrain: [], disk: [], movement: [] }
    context.report.faweProjection = evidence
    const originalWrite = bot._client.write
    bot._client.write = function (name, packet) {
      if (['position', 'position_look', 'look'].includes(name)) {
        evidence.movement.push({ name, ...packet })
        if (evidence.movement.length > 8) evidence.movement.shift()
      }
      return originalWrite.call(this, name, packet)
    }
    const counts = { single: 0, section: 0, chunk: 0 }
    const point = position => new bot.entity.position.constructor(position.x, position.y, position.z)
    const block = position => bot.blockAt(point(position))?.name
    let fixtureFailure
    let setupAttempted = false
    let initialFailures
    const single = packet => {
      if (packet.location?.x === CLAIM.x && packet.location?.y === CLAIM.y && packet.location?.z === CLAIM.z) counts.single++
    }
    const section = packet => {
      if (packet.chunkCoordinates?.x === 0 && packet.chunkCoordinates?.y === 12
        && packet.chunkCoordinates?.z === 0) counts.section++
    }
    const chunk = packet => {
      if (packet.x === 0 && packet.z === 0) counts.chunk++
    }
    const message = text => {
      if (/WHFAWE failure=/.test(text)) fixtureFailure = new Error(text)
    }
    bot._client.on('block_change', single)
    bot._client.on('multi_block_change', section)
    bot._client.on('map_chunk', chunk)
    bot.on('messagestr', message)
    const expectHealthy = () => {
      if (fixtureFailure) throw fixtureFailure
      context.expect(bot.health > 0, 'FAWE observer must remain alive')
    }
    const fixture = async (action, expected = new RegExp(`WHFAWE ${action}`), timeoutMs = 10000) => {
      const response = await context.command(`/whfawe ${action}`, expected, timeoutMs)
      expectHealthy()
      return response
    }
    const sample = async since => {
      const response = await fixture(`sample${since === undefined ? '' : ` ${since}`}`, /WHFAWE sample /)
      const values = Object.fromEntries([...response.matchAll(/(\w+)=([^\s]+)/g)].map(match => [match[1], match[2]]))
      context.expect(values.destinationNeighbor === 'EMERALD_BLOCK', 'Unedited destination neighbor remains emerald', values)
      if (initialFailures !== undefined) {
        context.expect(values.failures === initialFailures, 'Projection telemetry recorded a new failure', values)
      }
      return values
    }
    const waitBlocks = async (expected, timeoutMs = PROMPT_UPDATE_MS) => {
      try {
        await context.waitUntil(() => {
          expectHealthy()
          return expected.every(([position, material]) => block(position) === material)
        }, { label: `client blocks ${expected.map(([, material]) => material).join(', ')}`, timeoutMs })
      } catch (failure) {
        evidence.failedBlocks = expected.map(([position, material]) => ({ position, expected: material, actual: block(position) }))
        if (!context.signal.aborted && bot._client.state === 'play') evidence.failedState = await sample()
        throw failure
      }
    }
    const holdBlocks = async expected => {
      const until = performance.now() + 1500
      while (performance.now() < until) {
        expectHealthy()
        context.expect(expected.every(([position, material]) => block(position) === material),
          'Settled projection claims were overwritten again', expected.map(([position, material]) => ({ position, expected: material, actual: block(position) })))
        await context.sleep(50)
      }
    }
    const update = async (name, command, reply, material) => {
      const before = await sample()
      const started = performance.now()
      const entry = { name, command, before }
      evidence.updates.push(entry)
      const commandReply = await context.command(command, reply, PROMPT_UPDATE_MS)
      entry.commandReply = commandReply
      const applied = await context.waitUntil(async () => {
        const current = await sample(before.version)
        return current.destination === material.toUpperCase() && current.targetDirty === 'true' ? current : false
      }, { label: `${name} committed world data and destination tracker stamp`, timeoutMs: Math.max(1, PROMPT_UPDATE_MS - (performance.now() - started)) })
      await waitBlocks([[CLAIM, material], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'iron_block']],
        Math.max(1, PROMPT_UPDATE_MS - (performance.now() - started)))
      const elapsedMs = Math.round(performance.now() - started)
      context.expect(elapsedMs < PROMPT_UPDATE_MS, 'Destination projection must update before the 60 second backstop', { elapsedMs })
      context.expect(applied.observer === 'true', 'FAWE update must keep the existing portal observer active', applied)
      context.expect(applied.local === 'STONE' && applied.neighbor === 'STONE', 'Destination projection must not mutate source terrain', applied)
      Object.assign(entry, { elapsedMs, applied })
      await holdBlocks([[CLAIM, material], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'iron_block']])
    }
    const nativeBatch = async noops => {
      const before = await sample()
      await fixture(noops ? 'native-start noops' : 'native-start', /WHFAWE native-start async=true writes=1024/)
      await context.waitUntil(async () => /admitted=true/.test(await fixture('native-status')),
        { label: 'native FAWE batch admitted', timeoutMs: 10000 })
      await fixture('native-prepare', /WHFAWE native-prepare waiting=true/)
      const released = await fixture('native-release', /WHFAWE native-release ready=true applied=1024 cancelled=true/, 15000)
      const applied = await context.waitUntil(async () => {
        const state = await sample(before.version)
        return state.destination === 'GOLD_BLOCK' && state.targetDirty === 'true' ? state : false
      }, { label: 'cancelled native batch stamps current destination', timeoutMs: 10000 })
      await waitBlocks([[CLAIM, 'gold_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'iron_block']])
      await holdBlocks([[CLAIM, 'gold_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'iron_block']])
      evidence.nativeBatches.push({ noops, before, released, applied })
    }
    const terrain = async (name, packetKind, command, unrelated) => {
      const before = counts[packetKind]
      const started = performance.now()
      await fixture(command)
      await context.waitUntil(() => counts[packetKind] > before,
        { label: `${name} actual outbound ${packetKind} packet`, timeoutMs: PROMPT_UPDATE_MS })
      await waitBlocks([[CLAIM, 'lapis_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, unrelated]],
        Math.max(1, PROMPT_UPDATE_MS - (performance.now() - started)))
      const state = await sample()
      context.expect(state.local === (name === 'full chunk' ? 'REDSTONE_BLOCK' : 'GOLD_BLOCK')
        && state.neighbor === 'STONE' && state.observer === 'true',
        'Terrain resend retains current real terrain and the active observer', state)
      await holdBlocks([[CLAIM, 'lapis_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, unrelated]])
      evidence.terrain.push({ name, elapsedMs: Math.round(performance.now() - started), packets: { ...counts }, state })
    }
    const verifyDisk = async phase => {
      const state = await sample()
      await context.command('/save-all flush', /Saved the game|Saved the world/i, 20000)
      const instance = await realpath(context.server.directory)
      const folder = await realpath(Buffer.from(state.folder, 'base64url').toString('utf8'))
      const relative = path.relative(instance, folder)
      context.expect(relative && !relative.startsWith('..') && !path.isAbsolute(relative),
        'Only read saved worlds inside this isolated instance', { folder })
      const region = context.report.bot.serverBrand === 'Spigot'
        ? path.join(folder, 'dimensions', 'minecraft', 'overworld', 'region')
        : path.join(folder, 'region')
      const filename = path.join(region, 'r.0.0.mca')
      const saved = await readSourceChunk(filename, nbt)
      const observed = [CLAIM, NEIGHBOR, UNRELATED].map(position => ({ position, material: savedBlock(saved, position) }))
      context.expect(observed[0].material === 'minecraft:redstone_block' && observed[1].material === 'minecraft:stone'
        && observed[2].material === 'minecraft:copper_block', 'Saved source terrain must match the current real world', observed)
      const spoofedPalette = saved.sections.flatMap(entry => entry.block_states?.palette ?? [])
        .filter(entry => SPOOFED.has(entry.Name))
      context.expect(spoofedPalette.length === 0, 'Saved source chunk contains projected spoof blocks', spoofedPalette)
      evidence.disk.push({ phase, filename, observed, spoofedPalette })
    }

    try {
      await context.step('create real paired portals and hold a stationary observer', async () => {
        bot.physicsEnabled = false
        await bot.look(Math.PI, 0, true)
        if (bot.game.gameMode !== 'creative') {
          await context.command('/gamemode creative @s', /creative/i)
        }
        context.expect(bot.game.gameMode === 'creative', 'The projection observer must be in creative mode')
        setupAttempted = true
        evidence.setup = await fixture('setup', /WHFAWE ready open=true linked=true/, 30000)
        await fixture('stage', /WHFAWE staged=true/)
        await context.waitUntil(() => bot.entity.position.distanceTo(point({ x: 8.5, y: 191, z: 13.5 })) < 0.2,
          { label: 'observer at source portal', timeoutMs: 10000 })
        await bot.look(Math.PI, 0, true)
        await waitBlocks([[CLAIM, 'diamond_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'iron_block']], 20000)
        const state = await sample()
        initialFailures = state.failures
        context.expect(state.local === 'STONE' && state.neighbor === 'STONE' && state.observer === 'true',
          'Real linked portal projects destination blocks over unchanged local stone', state)
        evidence.initial = state
        await holdBlocks([[CLAIM, 'diamond_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'iron_block']])
      })
      await context.step('capture the native destination environment through the packed runtime', async () => {
        const result = await fixture('environment', /WHFAWE environment provider=/);
        context.expect(/provider=art\.arcane\.volmlib\.nativelib\.v26_[23]_R1\.environment\.NativeWorldEnvironmentAccess/.test(result),
          'The loaded game version must provide native destination environment capture', { result });
        context.expect(/height=384\b/.test(result) && /sky=OVERWORLD\b/.test(result) && /medium=NONE\b/.test(result),
          'Overworld destination environment must retain its native dimension and eye medium', { result });
        evidence.environment = result;
      })
      await context.step('select a destination cuboid with actual FAWE commands', async () => {
        await context.command('//pos1 102,192,4', /First position|Position 1|pos1/i, 10000)
        await context.command('//pos2 104,194,4', /Second position|Position 2|pos2/i, 10000)
      })
      await context.step('queued FAWE set updates projected destination before the backstop', () => update('set', '//set lapis_block',
        /blocks? (?:have been )?changed|operation.*completed|completed.*operation/i, 'lapis_block'))
      await context.step('FAWE undo promptly restores the projected destination', () => update('undo', '//undo',
        /undo.*successful|undid|operation.*completed/i, 'diamond_block'))
      await context.step('FAWE redo promptly reapplies the projected destination', () => update('redo', '//redo',
        /redo.*successful|redid|operation.*completed/i, 'lapis_block'))
      await context.step('cancelled detached native batch survives and reload preparation waits for its session', () => nativeBatch(false))
      await context.step('reload cancellation reopens FAWE edit admission', () => update('after reload cancellation', '//set lapis_block',
        /blocks? (?:have been )?changed|operation.*completed|completed.*operation/i, 'lapis_block'))
      await context.step('repeated cancelled native batch with unchanged neighbors still refreshes the marker', () => nativeBatch(true))
      await context.step('repeated reload cancellation reopens FAWE edit admission', () => update('after repeated reload cancellation', '//set lapis_block',
        /blocks? (?:have been )?changed|operation.*completed|completed.*operation/i, 'lapis_block'))
      await context.step('single real terrain update cannot erase unchanged claims', () => terrain('single block', 'single', 'single', 'iron_block'))
      await context.step('section real terrain update cannot erase unchanged claims or spoof unrelated blocks',
        () => terrain('section blocks', 'section', 'section', 'copper_block'))
      await context.step('actual FAWE edit behind active projection updates only the real source terrain', async () => {
        await context.command('//pos1 8,193,4', /First position|Position 1|pos1/i, 10000)
        await context.command('//pos2 8,193,4', /Second position|Position 2|pos2/i, 10000)
        const before = await sample()
        const beforeChunks = counts.chunk
        const started = performance.now()
        await context.command('//set redstone_block', /blocks? (?:have been )?changed|operation.*completed|completed.*operation/i,
          PROMPT_UPDATE_MS)
        const applied = await context.waitUntil(async () => {
          const state = await sample()
          return state.local === 'REDSTONE_BLOCK' ? state : false
        }, { label: 'queued FAWE source terrain commit', timeoutMs: Math.max(1, PROMPT_UPDATE_MS - (performance.now() - started)) })
        await context.waitUntil(() => counts.chunk > beforeChunks,
          { label: 'queued FAWE source edit actual outbound chunk packet', timeoutMs: Math.max(1, PROMPT_UPDATE_MS - (performance.now() - started)) })
        await waitBlocks([[CLAIM, 'lapis_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'copper_block']],
          Math.max(1, PROMPT_UPDATE_MS - (performance.now() - started)))
        await holdBlocks([[CLAIM, 'lapis_block'], [NEIGHBOR, 'emerald_block'], [UNRELATED, 'copper_block']])
        context.expect(applied.observer === 'true', 'FAWE source edit retains the active projection observer', applied)
        evidence.sourceEdit = { before, applied, elapsedMs: Math.round(performance.now() - started) }
      })
      await context.step('full chunk real terrain refresh cannot erase unchanged claims',
        () => terrain('full chunk', 'chunk', 'chunk', 'copper_block'))
      await context.step('saving while projection is active writes only current real terrain', () => verifyDisk('active projection'))
      await context.step('releasing projection restores current real terrain rather than captured terrain', async () => {
        await fixture('release', /WHFAWE released=true/)
        await waitBlocks([[CLAIM, 'redstone_block'], [NEIGHBOR, 'stone'], [UNRELATED, 'copper_block']])
        await holdBlocks([[CLAIM, 'redstone_block'], [NEIGHBOR, 'stone'], [UNRELATED, 'copper_block']])
        evidence.released = await sample()
      })
      await context.step('saving after release still excludes projection blocks', () => verifyDisk('released projection'))
    } finally {
      bot._client.write = originalWrite
      bot.clearControlStates()
      bot._client.removeListener('block_change', single)
      bot._client.removeListener('multi_block_change', section)
      bot._client.removeListener('map_chunk', chunk)
      bot.removeListener('messagestr', message)
      if (setupAttempted && !context.signal.aborted && bot._client.state === 'play') {
        await fixture('cleanup', /WHFAWE cleaned=true/)
      }
      evidence.packets = { ...counts }
    }
  }
}

async function readSourceChunk(filename, nbt) {
  const region = await readFile(filename)
  if (region.length < 8192) throw new Error('Saved region has no complete header')
  const sector = region.readUIntBE(0, 3)
  const sectors = region[3]
  if (sector < 2 || sectors === 0) throw new Error('Saved source chunk is missing')
  const offset = sector * 4096
  const length = region.readUInt32BE(offset)
  if (length < 2 || length > sectors * 4096 - 4 || offset + length + 4 > region.length) {
    throw new Error('Saved source chunk has an invalid record length')
  }
  const compression = region[offset + 4]
  const payload = region.subarray(offset + 5, offset + 4 + length)
  const bytes = compression === 1 ? gunzipSync(payload) : compression === 2 ? inflateSync(payload)
    : compression === 3 ? payload : undefined
  if (!bytes) throw new Error(`Unsupported region compression ${compression}`)
  const { parsed } = await nbt.parse(bytes)
  const saved = nbt.simplify(parsed)
  if (saved.xPos !== 0 || saved.zPos !== 0 || !Array.isArray(saved.sections)) {
    throw new Error('Saved region record is not source chunk 0,0')
  }
  return saved
}

function savedBlock(chunk, position) {
  const section = chunk.sections.find(entry => entry.Y === Math.floor(position.y / 16))
  const states = section?.block_states
  if (!states?.palette?.length) throw new Error('Saved source section has no block palette')
  if (states.palette.length === 1) return states.palette[0].Name
  const bits = Math.max(4, Math.ceil(Math.log2(states.palette.length)))
  const perLong = Math.floor(64 / bits)
  const cell = ((position.y & 15) << 8) | ((position.z & 15) << 4) | (position.x & 15)
  const raw = states.data?.[Math.floor(cell / perLong)]
  if (raw === undefined) throw new Error('Saved source section has no block-state data')
  const word = typeof raw === 'bigint' ? BigInt.asUintN(64, raw)
    : Array.isArray(raw) ? (BigInt(raw[0] >>> 0) << 32n) | BigInt(raw[1] >>> 0) : undefined
  if (word === undefined) throw new Error('Saved block-state long has an unsupported encoding')
  const index = Number((word >> BigInt((cell % perLong) * bits)) & ((1n << BigInt(bits)) - 1n))
  if (!states.palette[index]) throw new Error('Saved block-state palette index is invalid')
  return states.palette[index].Name
}
