import nestedProjection from './nested-entity-projection.mjs'

export default {
  name: 'native-capabilities',
  description: 'Verify shared native data access, scoreboard packets, and projected entity visibility.',
  async run(context) {
    const packets = []
    const boardId = `native_${Date.now().toString(36)}`
    const onPacket = (data, metadata) => {
      if (metadata.name.startsWith('scoreboard_') || metadata.name === 'teams') packets.push({ name: metadata.name, data })
    }
    context.bot._client.on('packet', onPacket)
    async function until(predicate, message) {
      const deadline = Date.now() + 10000
      while (Date.now() < deadline) {
        if (predicate()) return
        await context.sleep(50)
      }
      context.expect(false, message, { packets })
    }
    try {
      await context.step('native block data, locks, maps, visibility, and chunk delivery', async () => {
        await context.command('/whnested native', /NATIVE blocks=true locks=true maps=true visibility=true chunks=true/, 20000)
      })
      await context.step('scoreboard title and line packets reach the client', async () => {
        await context.command(`/gloss board create id=${boardId}`, /created/i)
        await context.command(`/gloss board title id=${boardId} text=NATIVE_SCOREBOARD`, /title/i)
        await context.command(`/gloss board addline id=${boardId} text=NATIVE_LINE`, /line/i)
        await context.command(`/gloss board show id=${boardId}`, /show|display/i)
        await until(() => packets.some(packet => JSON.stringify(packet.data).includes('NATIVE_SCOREBOARD')),
          'Native scoreboard title did not reach the client')
        await until(() => packets.some(packet => JSON.stringify(packet.data).includes('NATIVE_LINE')),
          'Native scoreboard line did not reach the client')
        context.report.nativeScoreboard = packets
        await context.command('/gloss board hide', /hid/i)
        await context.command(`/gloss board delete id=${boardId}`, /deleted/i)
      })
      await nestedProjection.run(context)
    } finally {
      context.bot._client.removeListener('packet', onPacket)
    }
  }
}
