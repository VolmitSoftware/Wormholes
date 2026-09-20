export default {
  name: 'nested-entity-projection',
  description: 'Verify projected entities across two portal pairs, per-viewer hiding, and inner projection disable.',
  async run(context) {
    const bot = context.bot
    const evidence = { phases: [] }
    context.report.nestedEntities = evidence
    const items = () => Object.values(bot.entities).filter(entity => entity.name === 'item'
      && Math.abs(entity.position.x - 8.5) < 1 && Math.abs(entity.position.y - 191.25) < 1)
    const at = z => items().filter(entity => Math.abs(entity.position.z - z) < 0.2)
    const sample = label => evidence.phases.push({ label, items: items().map(entity => ({
      id: entity.id, uuid: entity.uuid, position: { ...entity.position }
    })) })
    async function waitFor(predicate, message, timeout = 20000) {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        if (predicate()) return
        await context.sleep(100)
      }
      sample(message)
      context.expect(false, message, evidence)
    }
    await context.step('create two linked portal pairs and stage observer', async () => {
      if (context.bot.game.gameMode !== 'creative') {
        await context.command('/gamemode creative', /creative/i)
      }
      await context.command('/whnested setup', /NESTED ready=true/, 60000)
      await context.command('/whnested stage', /NESTED staged=true/)
      await waitFor(() => Math.abs(bot.entity.position.x - 8.5) < 0.1
        && Math.abs(bot.entity.position.y - 191) < 0.1, 'Observer staging failed')
      await bot.look(0, 0, true)
      evidence.source = await context.command('/whnested sample', /NESTED direct=/)
    })
    await context.step('direct and nested item appear at their composed coordinates', async () => {
      await waitFor(() => at(6.5).length === 1, 'Direct item projection missing')
      sample('direct item visible')
      await waitFor(() => at(-1.5).length === 1, 'Nested item projection missing')
      sample('both items visible')
      context.expect(items().length === 2, 'Duplicate projected item', evidence)
    })
    await context.step('nested item respects observer visibility', async () => {
      await context.command('/whnested hide', /NESTED hidden=true/)
      await waitFor(() => at(-1.5).length === 0 && at(6.5).length === 1,
        'Hidden nested item remained visible or direct item disappeared')
      await context.command('/whnested show', /NESTED hidden=false/)
      await waitFor(() => at(-1.5).length === 1 && at(6.5).length === 1,
        'Showing nested item did not restore its projection')
      sample('visibility restored')
    })
    await context.step('inner projection off removes nested item only', async () => {
      await context.command('/whnested mode false', /NESTED mode=OFF/)
      await waitFor(() => at(-1.5).length === 0 && at(6.5).length === 1,
        'Disabling inner projection did not remove nested item only')
      sample('inner disabled')
      await context.command('/whnested mode true', /NESTED mode=ON/)
      await waitFor(() => at(-1.5).length === 1 && at(6.5).length === 1,
        'Reenabling inner projection did not restore nested item')
      sample('inner restored')
    })
    await context.step('opaque intermediate wall occludes nested item', async () => {
      await context.command('/whnested wall true', /NESTED wall=true/)
      await waitFor(() => at(-1.5).length === 0 && at(6.5).length === 1,
        'Opaque intermediate wall did not occlude nested item only')
      sample('nested wall blocks item')
      await context.command('/whnested wall false', /NESTED wall=false/)
      await waitFor(() => at(-1.5).length === 1 && at(6.5).length === 1,
        'Removing nested wall did not restore item')
      sample('nested wall removed')
    })
  }
}
