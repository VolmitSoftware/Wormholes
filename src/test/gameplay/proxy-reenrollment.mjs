import { createHmac, timingSafeEqual } from 'node:crypto'

const CHANNEL = 'wormholes:proxy'
const SECRET = Buffer.from((process.env.WORMHOLES_PROXY_QA_SECRET ?? 'proxy-thread-qa-secret').trim(), 'utf8')

function enrollment(payload) {
  if (!Buffer.isBuffer(payload) || payload.length < 37) {
    throw new Error('Proxy enrollment payload is missing or truncated')
  }
  const body = payload.subarray(0, -32)
  const signature = payload.subarray(-32)
  const expected = createHmac('sha256', SECRET).update(body).digest()
  if (!timingSafeEqual(signature, expected)) {
    throw new Error('Proxy enrollment signature does not match the configured test secret')
  }
  if (body.readInt32BE(0) !== 2 || body.readUInt8(4) !== 1) {
    throw new Error('Expected a protocol-2 ENROLL frame')
  }
  let offset = 5
  const requireBytes = count => {
    if (offset + count > body.length) throw new Error('Proxy enrollment body is truncated')
  }
  const text = () => {
    requireBytes(2)
    const length = body.readUInt16BE(offset)
    offset += 2
    requireBytes(length)
    const value = body.toString('utf8', offset, offset + length)
    offset += length
    return value
  }
  const serverName = text()
  const serverCode = text()
  requireBytes(12)
  offset += 8
  const portalCount = body.readInt32BE(offset)
  offset += 4
  if (portalCount < 0 || portalCount > 256) throw new Error('Proxy enrollment portal count is invalid')
  for (let index = 0; index < portalCount; index++) text()
  requireBytes(16)
  const nonce = body.subarray(offset, offset + 16).toString('hex')
  offset += 16
  if (offset !== body.length || !serverName || !serverCode) {
    throw new Error('Proxy enrollment identity or frame length is invalid')
  }
  return { serverName, nonce, portalCount, bytes: payload.length, receivedAt: Date.now() }
}

export default {
  name: 'proxy-reenrollment',
  description: 'Verify signed proxy enrollment on channel registration and periodic refresh while a player remains connected.',
  async run(context) {
    const client = context.bot._client
    const frames = []
    const evidence = { channel: CHANNEL, frames }
    context.report.proxyReenrollment = evidence
    let failure = null
    let registered = false
    const receive = packet => {
      if (packet.channel !== CHANNEL) return
      try {
        frames.push(enrollment(packet.data))
      } catch (error) {
        failure = error
      }
    }
    const registration = enabled => {
      client.write('custom_payload', {
        channel: enabled ? 'minecraft:register' : 'minecraft:unregister',
        data: Buffer.from(CHANNEL, 'utf8')
      })
      registered = enabled
    }
    const next = async (predicate, timeoutMs, label) => {
      await context.waitUntil(() => {
        if (failure) throw failure
        return frames.some(predicate)
      }, { timeoutMs, label })
      return frames.find(predicate)
    }
    let initial
    let periodic
    client.on('custom_payload', receive)
    try {
      await context.step('channel registration receives a signed enrollment', async () => {
        registration(true)
        initial = await next(() => true, 15000, 'initial proxy enrollment')
        context.expect(initial.nonce.length === 32, 'Initial enrollment has no complete UUID nonce', initial)
      })

      await context.step('the connected player receives a fresh periodic enrollment', async () => {
        periodic = await next(frame => frame.receivedAt > initial.receivedAt + 5000,
          90000, 'periodic proxy enrollment')
        context.expect(periodic.nonce !== initial.nonce, 'Periodic enrollment reused its nonce', { initial, periodic })
        context.expect(periodic.serverName === initial.serverName,
          'Periodic enrollment changed backend identity', { initial, periodic })
        evidence.periodicDelayMs = periodic.receivedAt - initial.receivedAt
      })

      await context.step('registering the channel again still enrolls the player', async () => {
        const before = frames.length
        registration(false)
        registration(true)
        const refreshed = await next(frame => frames.indexOf(frame) >= before,
          15000, 'enrollment after channel registration')
        context.expect(refreshed.nonce !== periodic.nonce && refreshed.nonce !== initial.nonce,
          'Channel registration reused an enrollment nonce', refreshed)
        context.expect(refreshed.serverName === initial.serverName,
          'Channel registration changed backend identity', refreshed)
      })
      context.expect(failure === null, 'An invalid enrollment frame was received')
    } finally {
      client.removeListener('custom_payload', receive)
      if (registered && client.state === 'play') registration(false)
    }
  }
}
