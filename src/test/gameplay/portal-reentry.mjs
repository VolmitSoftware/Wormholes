export default {
  name: 'portal-reentry',
  description: 'Traverse a portal and reverse after clearing only the destination pane block.',
  async run(context) {
    const bot = context.bot
    const evidence = []
    context.report.reentry = evidence
    const sample = label => evidence.push({ label, position: { ...bot.entity.position } })
    async function waitFor(predicate, message, timeout = 10000) {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        if (predicate()) return
        await context.sleep(20)
      }
      context.expect(false, message, { position: bot.entity.position, evidence })
    }
    async function walkUntil(yaw, predicate, message) {
      await bot.look(yaw, 0, true)
      bot.setControlState('forward', true)
      try { await waitFor(predicate, message) }
      finally { bot.clearControlStates() }
    }
    try {
      await context.step('create linked portals and stage player', async () => {
        await context.command('/whtest reentry', /FIXTURE ready/, 30000)
        if (bot.game.gameMode !== 'creative') {
          await context.command('/gamemode creative', /creative/i)
        }
        await context.command('/whtest stage', /FIXTURE staged true/)
        await waitFor(() => Math.abs(bot.entity.position.x - 8.5) < 0.1 && bot.entity.position.y > 100, 'Player did not stage')
        sample('staged')
      })
      await context.step('walk through source portal', async () => {
        await walkUntil(0, () => bot.entity.position.x > 11, 'First portal traversal failed')
        sample('arrived')
      })
      await context.step('clear only the destination pane block and reverse', async () => {
        const south = bot.entity.position.z >= 8.5
        const outwardYaw = south ? Math.PI : 0
        const outward = () => south ? bot.entity.position.z > 9.36 : bot.entity.position.z < 7.64
        if (!outward()) await walkUntil(outwardYaw, outward, 'Player failed to clear destination block')
        sample('clear of pane')
        context.expect(bot.entity.position.z > 7 && bot.entity.position.z < 10,
          'Player walked beyond the immediately adjacent block', evidence)
        await walkUntil(south ? 0 : Math.PI, () => bot.entity.position.x < 10, 'Reentry failed after clearing destination pane')
        sample('returned')
      })
      await context.step('arrival remains stable without a bounce', async () => {
        const deadline = Date.now() + 1200
        while (Date.now() < deadline) {
          context.expect(bot.entity.position.x < 10, 'Player bounced to destination without reentry', evidence)
          await context.sleep(50)
        }
        sample('stable')
      })
    } finally {
      bot.clearControlStates()
    }
  }
}
