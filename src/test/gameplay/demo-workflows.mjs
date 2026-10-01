import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { publicPlayer } from './support/public-player.mjs'

export default {
  name: 'wormholes-demo-workflows',
  description: 'Build two framed 3x3 portals with real runes and wand gestures, link using menus, and travel both ways.',
  async run(context) {
    const p = publicPlayer(context)
    const evidence = { placements: [], frames: [], menus: [], crossings: [] }
    context.report.demoWorkflows = evidence
    const names = ['Garden', 'Courtyard']

    async function open(z) {
      await p.stage(0.5, 70, z + 3.5)
      await p.equip(/^Portal Wand$/)
      await p.bot.lookAt(p.point(0.5, 71.5, z + 0.5), true)
      p.bot.activateItem()
      await p.wait(() => p.items().some(item => /^Rename Portal$/.test(item.name)), 'portal management menu')
    }

    async function rename(z, name) {
      await open(z)
      await p.click(/^Rename Portal$/)
      await p.wait(() => !p.bot.currentWindow, 'rename chat prompt')
      p.bot.chat(name)
      await p.wait(() => p.items().some(item => item.lore.some(line => line.includes(name))), 'renamed portal menu')
      evidence.menus.push({ z, name, items: p.items() })
      p.bot.closeWindow(p.bot.currentWindow)
    }

    async function assertFrame(z, material) {
      for (let x = -2; x <= 2; x++) {
        await p.serverBlock(p.point(x, 69, z), material)
        await p.serverBlock(p.point(x, 73, z), material)
      }
      for (let y = 70; y <= 72; y++) {
        await p.serverBlock(p.point(-2, y, z), material)
        await p.serverBlock(p.point(2, y, z), material)
      }
      evidence.frames.push({ z, material, opening: '3x3', perimeterBlocks: 16 })
    }

    try {
      await context.step('prepare isolated framed scenes and genuine rune supplies', async () => {
        context.expect(context.server.host === '127.0.0.1' && context.server.directory, 'Scenario requires a local isolated instance')
        const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
        context.expect(/^isolated=true\s*$/m.test(source), 'Scenario requires isolated=true')
        await context.command('/gamemode creative @s', /creative/i)
        await context.command('/whdemo scene rune', /Scene ready: rune/, 30000)
        await p.stage(0.5, 70, 4.0)
        await context.command(`/whdemo equip-runes ${p.bot.username}`, /Equipped .*runes=9/)
        await assertFrame(0, 'minecraft:deepslate_tiles')
        await assertFrame(200, 'minecraft:cut_sandstone')
      })

      await context.step('place nine matching runes and activate the framed 3x3 portal', async () => {
        for (let y = 70; y <= 72; y++) {
          for (let x = -1; x <= 1; x++) {
            await p.equip(/^Wormhole Rune$/)
            const position = p.point(x, y, 0)
            await p.wait(() => p.bot.blockAt(position.offset(0, 0, -1))?.name === 'glass', 'temporary placement support')
            await p.bounded(() => p.bot.placeBlock(p.bot.blockAt(position.offset(0, 0, -1)), p.point(0, 0, 1)), 'place genuine Wormhole Rune')
            await p.serverBlock(position, 'minecraft:dark_prismarine')
            evidence.placements.push({ x, y, z: 0 })
          }
        }
        context.expect(evidence.placements.length === 9, 'All nine runes occupy the flat 3x3 aperture')
        await p.equip(/^Portal Wand$/)
        await p.leftClick(p.point(0, 71, 0))
        await context.command('/whdemo report', /width=3, height=3, cells=9/)
        await context.command('/whdemo clear-markers source', /Cleared glass corner markers/)
        for (const position of evidence.placements) {
          await p.serverBlock(p.point(position.x, position.y, position.z), 'minecraft:air')
        }
        await rename(0, names[0])
      })

      await context.step('create the distant framed 3x3 portal using left-right-left wand gestures', async () => {
        await p.stage(0.5, 70, 204)
        await context.command('/give @s minecraft:glass 2', /Gave|Given/i)
        await p.place(p.point(-1, 70, 200), /^Glass$/, 'glass')
        await context.command('/setblock 1 71 200 minecraft:glass', /changed|placed|block/i)
        await p.place(p.point(1, 72, 200), /^Glass$/, 'glass')
        await p.equip(/^Portal Wand$/)
        await p.leftClick(p.point(-1, 70, 200))
        await p.rightClick(p.point(1, 72, 200))
        await p.leftClick(p.point(-1, 70, 200))
        await context.command('/whdemo report', /z=200, width=3, height=3, cells=9/)
        await context.command('/whdemo clear-markers destination', /Cleared glass corner markers/)
        await context.command('/setblock 1 71 200 minecraft:air', /changed|placed|block/i)
        await rename(200, names[1])
        await context.command('/whdemo report', /portals=2, sourceFrame=true, destinationFrame=true/)
      })

      for (const [z, destination] of [[0, names[1]], [200, names[0]]]) {
        await context.step(`link portal at ${z} to ${destination} using its destination menu`, async () => {
          await open(z)
          await p.click(/^Destination$/)
          await p.click(new RegExp(destination))
          await p.wait(() => !p.bot.currentWindow, 'destination selection closes')
          await open(z)
          context.expect(p.items().some(item => item.name === 'Destination' && item.lore.some(line => line.includes(destination))), 'Portal menu confirms the destination', p.items())
          evidence.menus.push({ z, destination, items: p.items() })
          p.bot.closeWindow(p.bot.currentWindow)
        })
      }

      await context.step('select Wormhole mode for the wand-created portal', async () => {
        await open(200)
        await p.click(/^Mode$/)
        await p.click(/^Wormhole$/)
        await p.wait(() => !p.bot.currentWindow, 'Wormhole mode selection closes')
        await open(200)
        context.expect(p.items().some(item => item.lore.includes('Type: Wormhole')), 'Portal menu confirms Wormhole mode', p.items())
        p.bot.closeWindow(p.bot.currentWindow)
      })

      for (const [source, destination] of [[0, 200], [200, 0]]) {
        await context.step(`travel from framed portal at ${source} to ${destination}`, async () => {
          await p.stage(0.5, 70, source + 3.5)
          await p.emptyHand()
          await p.walk(0, () => Math.abs(p.bot.entity.position.z - destination) < 4, 'arrival at linked 3x3 portal')
          context.expect(Math.abs(p.bot.entity.position.x - 0.5) < 2 && Math.abs(p.bot.entity.position.y - 70) < 2, 'Traveler arrives inside the intended framed aperture', p.bot.entity.position)
          evidence.crossings.push({ source, destination, position: { ...p.bot.entity.position } })
          await context.sleep(800)
          context.expect(Math.abs(p.bot.entity.position.z - destination) < 5, 'Traveler stays at the destination without bouncing back', p.bot.entity.position)
        })
      }
      await assertFrame(0, 'minecraft:deepslate_tiles')
      await assertFrame(200, 'minecraft:cut_sandstone')
    } finally {
      p.bot.clearControlStates()
      if (p.bot.currentWindow) p.bot.closeWindow(p.bot.currentWindow)
    }
  }
}
