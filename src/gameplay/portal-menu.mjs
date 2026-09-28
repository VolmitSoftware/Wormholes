export default {
  name: 'wormholes-portal-menu',
  description: 'Create a portal with the wand and verify its inventory controls.',
  async run(context) {
    const bot = context.bot
    const Vec3 = bot.entity.position.constructor
    const cornerA = new Vec3(0, 80, 0)
    const cornerB = new Vec3(1, 82, 0)

    await context.step('prepare portal workspace', async () => {
      context.expect(context.server.instance.startsWith('wormholes-port-'), 'This scenario requires a disposable Wormholes port test instance')
      await context.command('/wormholes admin deleteallportals', /Deleted/i)
      await context.command('/gamemode creative')
      await context.waitUntil(() => bot.game.gameMode === 'creative',
        { timeoutMs: 5000, label: 'creative mode' })
      await context.command('/fill -4 79 -4 4 79 4 stone', /filled|changed|blocks|No blocks/i)
      await context.command('/fill 0 80 0 1 82 0 stone', /filled|changed|blocks|No blocks/i)
      await context.command('/tp @s 0.5 80 -2.5', /teleported/i)
      await context.waitUntil(() => bot.entity.position.distanceTo(new Vec3(0.5, 80, -2.5)) < 0.5,
        { timeoutMs: 5000, label: 'portal work area arrival' })
      await context.waitUntil(() => bot.blockAt(cornerA)?.name === 'stone' && bot.blockAt(cornerB)?.name === 'stone',
        { timeoutMs: 10000, label: 'portal corner blocks received' })
      await context.command('/wormholes wand', /Wand.*granted/i)
      const wand = await context.waitUntil(() => bot.inventory.items().find(item => item.name === 'blaze_rod'),
        { timeoutMs: 5000, label: 'portal wand inventory delivery' })
      await bot.equip(wand, 'hand')
      context.expect(bot.heldItem?.name === 'blaze_rod', 'Portal wand was not equipped')
    })

    await context.step('select and construct portal', async () => {
      await bot.lookAt(cornerA.offset(0.5, 0.5, 0.5), true)
      const selectedA = context.waitForMessage(/Corner A set/i, 5000)
      bot._client.write('block_dig', { status: 0, location: cornerA, face: 2, sequence: 1 })
      await selectedA
      const selectedB = context.waitForMessage(/6 cells|6 blocks|Selected/i, 5000)
      selectedB.catch(() => {})
      await bot.activateBlock(bot.blockAt(cornerB))
      await selectedB
      await bot.lookAt(cornerA.offset(0.5, 0.5, 0.5), true)
      const opened = context.waitForMessage(/Portal opened/i, 10000)
      bot._client.write('block_dig', { status: 0, location: cornerA, face: 2, sequence: 2 })
      await opened
    })

    await context.step('open portal controls', async () => {
      await bot.lookAt(cornerA.offset(0.5, 1.0, 0.5), true)
      const opened = context.waitForEvent('windowOpen', window => window.slots.length > 36, 10000)
      bot._client.write('block_dig', { status: 0, location: cornerA, face: 2, sequence: 3 })
      await opened
      const window = bot.currentWindow
      context.expect(window && window.slots.filter(Boolean).length >= 3, 'Portal menu controls are missing')
      await bot.closeWindow(window)
      context.expect(bot.inventory.items().some(item => item.name === 'blaze_rod'), 'Opening the portal consumed the wand')
    })

    await context.step('open language controls', async () => {
      await context.command('/wormholes language', /Your language.*Current/i, 5000)
      await context.command('/wormholes stats now=true', /Snapshot refreshed/i, 5000)
    })
  }
}
