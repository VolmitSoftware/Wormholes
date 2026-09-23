import { publicPlayer } from './public-player.mjs'

export function portalWorkflow(context) {
  const p = publicPlayer(context)
  const created = []
  const evidence = { portals: [], crossings: [] }
  context.report.publicPortals = evidence
  async function open(x) {
    await p.stage(x + 0.5, 200, 3.5)
    await p.equip(/^Portal Wand$/)
    await context.sleep(250)
    await p.bot.lookAt(p.point(x + 0.5, 201, 0.5), true)
    p.bot.activateItem()
    await p.wait(() => p.items().some(item => /^Rename Portal$/.test(item.name)), 'portal management menu')
  }

  async function build(x, name, { removeSelectionBlocks = true } = {}) {
    await p.stage(x + 0.5, 200, 3.5)
    await p.place(p.point(x, 200, 0), /^Glass$/, 'glass')
    await p.place(p.point(x, 201, 0), /^Glass$/, 'glass')
    await p.place(p.point(x, 202, 0), /^Glass$/, 'glass')
    await p.equip(/^Portal Wand$/)
    await p.leftClick(p.point(x, 200, 0))
    await p.rightClick(p.point(x, 202, 0))
    await p.leftClick(p.point(x, 200, 0))
    created.push(x)
    await open(x)
    await p.click(/^Rename Portal$/)
    await p.wait(() => !p.bot.currentWindow, 'rename chat prompt')
    p.bot.chat(name)
    await p.wait(() => p.items().some(item => item.lore.some(line => line.includes(name))), 'renamed portal menu')
    evidence.portals.push({ x, name, menu: p.items() })
    p.bot.closeWindow(p.bot.currentWindow)
    if (!removeSelectionBlocks) return
    await p.emptyHand()
    for (const y of [202, 201, 200]) {
      const position = p.point(x, y, 0)
      await p.bounded(() => p.bot.dig(p.bot.blockAt(position)), 'remove temporary selection block')
      await p.serverBlock(position, 'minecraft:air')
    }
  }

  async function link(x, destination) {
    await open(x)
    await p.click(/^Destination$/)
    await p.click(new RegExp(destination))
    await p.wait(() => !p.bot.currentWindow, 'destination selection closes')
    await open(x)
    context.expect(p.items().some(item => item.name === 'Destination'
      && item.lore.some(line => line.includes(destination))), 'Portal menu confirms destination', p.items())
    p.bot.closeWindow(p.bot.currentWindow)
  }

  async function wormhole(x) {
    await open(x)
    await p.click(/^Mode$/)
    await p.click(/^Wormhole$/)
    await p.wait(() => !p.bot.currentWindow, 'Wormhole mode selection closes')
    await open(x)
    context.expect(p.items().some(item => item.lore.includes('Type: Wormhole')),
      'Portal menu confirms Wormhole type', p.items())
    p.bot.closeWindow(p.bot.currentWindow)
  }
  async function cleanup() {
    p.bot.clearControlStates()
    const failures = []
    for (const x of created.reverse()) {
      try {
        await open(x)
        await p.click(/^Delete Portal$/, { shift: true })
        await p.wait(() => !p.bot.currentWindow, 'portal deletion closes menu')
      } catch (error) { failures.push(error) }
    }
    if (p.bot.currentWindow) p.bot.closeWindow(p.bot.currentWindow)
    if (failures.length) throw new AggregateError(failures, 'Portal cleanup failed; dispose of this isolated instance')
  }
  return { p, evidence, created, open, build, link, wormhole, cleanup }
}
