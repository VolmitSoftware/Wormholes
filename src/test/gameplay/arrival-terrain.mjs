import { publicPlayer } from './support/public-player.mjs'
export default {
  name: 'wormholes-arrival-packet-timing',
  description: 'Measure ordinary teleport barriers and complete destination landing-ring packets with bounded arrival delivery enabled and disabled.',
  async run(context) {
    const p = publicPlayer(context)
    const bot = context.bot
    const results = context.report.arrivalTimings = []
    const dimension = () => String(bot.game.dimension).replace(/^minecraft:/, '')
    if (bot.game.gameMode !== 'creative') await context.command('/gamemode creative @s', /creative/i)
    await context.command('/whnested arrival setup', /ARRIVAL setup=true/, 30000)
    for (const enabled of [false, true]) {
      await context.command(`/whnested arrival enabled ${enabled}`, new RegExp(`ARRIVAL enabled=${enabled}`))
      for (const mode of ['same', 'end']) {
        for (let repetition = 0; repetition < 3; repetition++) {
          await context.step(`${mode} arrival enabled=${enabled} repetition=${repetition}`, async () => {
            await context.command('/whnested arrival stage', /ARRIVAL crossed=true/)
            await p.wait(() => dimension() === 'overworld'
              && bot.entity.position.distanceTo(p.point(8.5, 200, 8.5)) < .2
              && ['red_concrete','sea_lantern'].includes(bot.blockAt(p.point(8,199,8))?.name), 'source staging terrain', 15000)
            await context.command(`/whnested arrival prepare ${mode}`, new RegExp(`ARRIVAL prepared=${mode}`))
            await context.sleep(200)
            const start = performance.now()
            const received = new Map()
            const barriers = []
            const observe = (data, meta) => {
              const time = performance.now() - start
              if (meta.name === 'respawn' || meta.name === 'position') {
                barriers.push({ packet: meta.name, time, x:data.x, y:data.y, z:data.z })
              }
              if (meta.name === 'map_chunk' && Math.abs(data.x-32)<=1 && Math.abs(data.z)<=1) {
                const key = `${data.x},${data.z}`
                if (!received.has(key)) received.set(key,time)
              }
            }
            bot._client.on('packet',observe)
            try {
              await context.command('/whnested arrival cross', /ARRIVAL crossed=true/)
              await p.wait(() => dimension() === (mode === 'end' ? 'the_end' : 'overworld')
                && bot.entity.position.distanceTo(p.point(520.5,200,8.5)) < .2
                && received.size === 9, 'teleport and nine destination chunk packets', 15000)
              const ground = bot.blockAt(p.point(520,199,8))?.name
              context.expect(['red_concrete','sea_lantern'].includes(ground), 'Destination ground is received', {ground})
              context.expect(barriers.length>0, 'Observed ordinary position or respawn barrier', {barriers})
              const barrier = barriers[0].time
              const first = Math.min(...received.values())
              const complete = Math.max(...received.values())
              context.expect(first >= barrier, 'Destination chunks follow authoritative teleport packets', {first,barrier})
              results.push({ enabled, mode, repetition, barriers, firstChunkMs:first-barrier,
                completeRingMs:complete-barrier, chunks:[...received], ground, position:{...bot.entity.position} })
            } finally { bot._client.off('packet',observe) }
          })
        }
      }
    }
    await context.command('/whnested arrival enabled true', /ARRIVAL enabled=true/)
    await context.command('/whnested arrival stage', /ARRIVAL crossed=true/)
  }
}
