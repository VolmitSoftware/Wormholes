import scaledTravel from './scaled-travel.mjs'
import { publicPlayer } from './support/public-player.mjs'

export default {
  name: 'wormholes-scale-persistence',
  description: 'Save a portal-scaled player, verify its size after restart, and verify vanilla size after removing Wormholes.',
  async run(context) {
    const phase = process.env.WORMHOLES_SCALE_PHASE ?? 'seed'
    context.expect(['seed', 'restored', 'removed'].includes(phase), 'A valid persistence phase is selected', { phase })
    const p = publicPlayer(context)
    if (phase === 'seed') {
      await scaledTravel.run(context)
      await context.step('leave the player enlarged for the next server start', async () => {
        await p.stage(-2.5, 200, 6.5)
        await context.sleep(1500)
        await p.stage(3.5, 200, 3.5)
        await p.walk(0, () => p.bot.entity.position.x > 19, 'player grows through the small portal')
      })
      await context.step('retain Bukkit traveller size through death and respawn', async () => {
        await Promise.all([
          context.waitForEvent('spawn', () => true, 15000),
          context.command(`/kill ${p.bot.username}`, /Killed/i),
        ])
        await p.wait(() => p.bot.health > 0, 'player respawns alive', 15000)
      })
    }
    await context.step(`verify ${phase} scale and saved attributes`, async () => {
      const reply = await context.command(`/attribute ${p.bot.username} minecraft:scale get`, /attribute Scale for entity/i)
      const actual = Number(/is (-?[0-9.]+)/.exec(String(reply))?.[1])
      const expected = phase === 'removed' ? 1 : 3
      context.expect(actual === expected, `Player scale is ${expected} during ${phase}`, { reply, actual })
      const serialized = await context.command(`/data get entity ${p.bot.username} attributes`, /entity data|Found no elements/i)
      context.expect(!String(serialized).includes('wormholes:portal_scale'), 'Vanilla saved attributes contain no portal size modifier', { serialized })
      if (phase !== 'removed') {
        const saved = await context.command(`/data get entity ${p.bot.username} BukkitValues."wormholes:portal_scale"`, /entity data/i)
        context.expect(/3\.0d/.test(String(saved)), 'Wormholes data retains the size for reinstall or restart', { saved })
      }
      context.report.scalePersistence = { phase, actual, expected, serialized }
    })
  }
}
