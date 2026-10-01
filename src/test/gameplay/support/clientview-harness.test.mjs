import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { after, before, describe, it } from 'node:test'

import { loadHarnessModule } from './clientview-client.mjs'
import { startFakeClientViewServer } from './clientview-fake-server.mjs'

const HARNESS = process.env.WORMHOLES_MINEFLAYER_HARNESS
const SCENARIO = fileURLToPath(new URL('../client-view-protocol.mjs', import.meta.url))
const SKIP = HARNESS && existsSync(`${HARNESS}/src/cli.mjs`) ? false : 'set WORMHOLES_MINEFLAYER_HARNESS to the Multiplexor mineflayer tool directory'
const BASE_ENVIRONMENT = { WORMHOLES_CV_SECONDS: '1.5', WORMHOLES_CV_NEGOTIATE_SECONDS: '5' }

let modules

async function run(serverOptions, environment = {}) {
  const server = await startFakeClientViewServer(modules, serverOptions)
  try {
    const child = spawn(process.execPath, [`${HARNESS}/src/cli.mjs`, 'run', SCENARIO, '--host', '127.0.0.1', '--port', String(server.port),
      '--username', 'WhVanillaB', '--version', '26.1', '--no-viewer', '--json', '--timeout', '60', '--instance', 'clientview-fake'], {
      env: { ...process.env, ...BASE_ENVIRONMENT, ...environment }
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (chunk) => { stdout += chunk })
    child.stderr.on('data', (chunk) => { stderr += chunk })
    const code = await new Promise((resolve) => child.on('close', resolve))
    const line = stdout.trim().split('\n').at(-1)
    assert.ok(line?.startsWith('{'), `harness printed no report (exit ${code}): ${stderr.slice(-2000)}`)
    const report = JSON.parse(line)
    return { report, code, sessions: server.sessions, clientA: report.clientView?.clientA?.session, vanillaB: report.clientView?.vanillaB }
  } finally {
    await server.close()
  }
}

function assertPassed(result) {
  assert.equal(result.report.status, 'passed', JSON.stringify({ errors: result.report.errors, steps: result.report.steps, violations: result.clientA?.violations }))
  assert.equal(result.code, 0)
}

function assertCountsMatchServer(result, username = 'WhClientA') {
  const session = result.sessions.get(username)
  assert.deepEqual(result.clientA.s2c, session.sent, 'every S2C frame and byte the server wrote was recorded')
  assert.deepEqual(result.clientA.c2s, session.received, 'every C2S frame and byte the bot wrote reached the server')
  assert.deepEqual(session.errors, [])
}

describe('client-view-protocol scenario against a fake ClientView server', { skip: SKIP }, () => {
  before(() => {
    modules = { minecraftProtocol: loadHarnessModule('minecraft-protocol'), minecraftData: loadHarnessModule('minecraft-data') }
  })

  after(() => {
    modules = undefined
  })

  it('negotiates in the configuration phase, records the stream byte for byte and keeps the vanilla bot vanilla', async () => {
    const result = await run({ handshake: 'config' }, { WORMHOLES_CV_HANDSHAKE: 'config' })
    assertPassed(result)
    assertCountsMatchServer(result)
    const negotiation = result.clientA.negotiations[0]
    assert.equal(negotiation.phase, 'configuration')
    assert.equal(negotiation.outcome, 'ACCEPT')
    assert.deepEqual(result.clientA.plates.map((plate) => [plate.portalKey, plate.plateRevision, plate.requested, plate.received]), [[7, 3, 4, 4], [8, 1, 0, 0]])
    assert.deepEqual(result.sessions.get('WhClientA').brickMisses.map((miss) => miss.missed), [[0, 1, 2, 3], []])
    assert.ok(result.clientA.frames.some((frame) => frame.dir === 'C2S' && frame.type === 'HELLO'))
    assert.ok(result.clientA.frames.every((frame) => Number.isInteger(frame.bytes) && frame.bytes > 0))
    assert.equal(result.vanillaB.window.playWormholes.frames, 0)
    const vanilla = result.sessions.get('WhVanillaB')
    assert.deepEqual(Object.keys(vanilla.sent.byType), ['OFFER'])
    assert.equal(vanilla.outcome.outcome, 'VANILLA')
    assert.ok(result.clientA.groups.count >= 7)
  })

  it('negotiates in the play phase after the channel registration', async () => {
    const result = await run({ handshake: 'play' }, { WORMHOLES_CV_HANDSHAKE: 'play' })
    assertPassed(result)
    assertCountsMatchServer(result)
    assert.equal(result.clientA.negotiations[0].phase, 'play')
    assert.equal(result.report.clientView.clientA.registration.play, 1)
    assert.equal(result.sessions.get('WhVanillaB').sent.frames, 0)
  })

  it('moves both bots to the pose before the server starts streaming', async () => {
    const result = await run({ handshake: 'config', streamWhenNear: { x: 10.5, y: 70, z: 10.5, radius: 2 } }, { WORMHOLES_CV_POSE: '10.5 70 10.5 90 0', WORMHOLES_CV_SECONDS: '2' })
    assertPassed(result)
    assertCountsMatchServer(result)
    assert.equal(result.clientA.plates.length, 2)
    assert.deepEqual(result.sessions.get('WhClientA').position, { x: 10.5, y: 70, z: 10.5 })
    assert.deepEqual(result.sessions.get('WhVanillaB').position, { x: 10.5, y: 70, z: 10.5 })
    assert.equal(result.vanillaB.window.playWormholes.frames, 0)
  })

  it('asks for every brick again with a cold brick cache', async () => {
    const result = await run({ handshake: 'config' }, { WORMHOLES_CV_CACHE: 'cold', WORMHOLES_CV_BOTS: 'a' })
    assertPassed(result)
    assertCountsMatchServer(result)
    assert.deepEqual(result.sessions.get('WhClientA').brickMisses.map((miss) => miss.missed), [[0, 1, 2, 3], [0, 1, 2, 3]])
  })

  it('streams whole plates without the brick cache and without destination light', async () => {
    const result = await run({ handshake: 'config' }, { WORMHOLES_CV_CAPS: 'PLATES,ENTITY_FRAMES' })
    assertPassed(result)
    assertCountsMatchServer(result)
    assert.deepEqual(result.clientA.caps, ['PLATES', 'ENTITY_FRAMES'])
    assert.equal(result.sessions.get('WhClientA').brickMisses.length, 0)
    assert.deepEqual(result.clientA.plates.map((plate) => [plate.requested, plate.received]), [[null, 4], [null, 4]])
    assert.equal(result.clientA.s2c.byType.FX, undefined)
  })

  it('reports a DECLINE when the bot sends its own data version', async () => {
    const result = await run({ handshake: 'config' }, { WORMHOLES_CV_EXPECT: 'decline', WORMHOLES_CV_DECLINE_REASON: 'DATA_VERSION_MISMATCH', WORMHOLES_CV_DATA_VERSION: 'bot' })
    assertPassed(result)
    assertCountsMatchServer(result)
    assert.equal(result.clientA.negotiations[0].reason, 'DATA_VERSION_MISMATCH')
    assert.notEqual(result.clientA.negotiations[0].hello.mcDataVersion, 4325)
  })

  it('stays vanilla when the server never offers', async () => {
    const result = await run({ handshake: 'off' }, { WORMHOLES_CV_EXPECT: 'none', WORMHOLES_CV_NEGOTIATE_SECONDS: '1' })
    assertPassed(result)
    assert.equal(result.clientA.state, 'IDLE')
    assert.equal(result.clientA.s2c.frames, 0)
  })

  it('fails when the server streams bricks for a portal it never announced', async () => {
    const result = await run({ handshake: 'config', faults: ['bricksBeforePortal'] })
    assert.equal(result.report.status, 'failed')
    assert.match(result.report.errors[0].message, /protocol violations/)
    assert.ok(result.clientA.violations.some((violation) => /before PORTAL/.test(violation.message)), JSON.stringify(result.clientA.violations))
  })

  it('fails when the vanilla bot receives ClientView frames in play', async () => {
    const result = await run({ handshake: 'config', faults: ['leakToVanilla'] })
    assert.equal(result.report.status, 'failed')
    assert.match(result.report.errors[0].message, /vanilla bot received 1 wormholes:v1 frames/)
  })
})
