import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { publicPlayer } from './support/public-player.mjs'
import { storedPortals } from './support/portal-storage.mjs'


export default {
  name: 'wormholes-shaped-portals-integration',
  description: 'Build and ignite an irregular obsidian frame, verify exact Wormholes ownership, traverse to the Nether, and retire the source by breaking its frame.',
  async run(context) {
    const p = publicPlayer(context)
    const portalDirectory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const logPath = path.join(context.server.directory, 'logs/latest.log')
    const logBefore = await readFile(logPath, 'utf8')
    const before = new Set((await storedPortals(portalDirectory)).map(portal => portal.id))
    const evidence = { frame: [], interior: [{ x: 1, y: 202, z: 0 }, { x: 2, y: 201, z: 0 }, { x: 2, y: 202, z: 0 }] }
    context.report.shapedPortals = evidence
    let source

    async function place(x, y, reference, face) {
      const position = p.point(x, y, 0)
      await p.equip(/^Obsidian$/)
      await p.bounded(() => p.bot.placeBlock(p.bot.blockAt(reference), face), 'place obsidian frame block')
      await p.serverBlock(position, 'minecraft:obsidian')
      evidence.frame.push({ x, y, z: 0 })
    }

    try {
      await context.step('build a three-cell L-shaped aperture with player block placements', async () => {
        await p.arena()
        await context.command('/shapedportals debug version', /ShapedPortals.*v|Shaped Portals.*v/i)
        await context.command('/give @s minecraft:obsidian 32', /Gave|Given/i)
        await context.command('/give @s minecraft:flint_and_steel', /Gave|Given/i)
        for (let x = 0; x <= 3; x++) await place(x, 200, p.point(x, 199, 0), p.point(0, 1, 0))
        for (const x of [0, 3]) {
          await p.stage(x + 0.5, 200, 3.5)
          for (let y = 201; y <= 203; y++) await place(x, y, p.point(x, y - 1, 0), p.point(0, 1, 0))
        }
        await p.stage(1.5, 200, 3.5)
        await place(1, 203, p.point(0, 203, 0), p.point(1, 0, 0))
        await place(2, 203, p.point(1, 203, 0), p.point(1, 0, 0))
        await place(1, 201, p.point(1, 200, 0), p.point(0, 1, 0))
        await context.command('/fill -4 200 1 7 200 8 minecraft:stone', /filled|changed|blocks/i)
      })
      await context.step('ignite the frame and verify Wormholes owns exactly the irregular aperture', async () => {
        await p.stage(2.5, 201, 3.5)
        await p.equip(/^Flint and Steel$/)
        const created = context.waitForMessage(/Created.*shaped Nether portal.*3.*interior/i, 30000)
        await p.bounded(() => p.bot.activateBlock(p.bot.blockAt(p.point(2, 200, 0)), p.point(0, 1, 0)), 'ignite shaped portal')
        await created
        const expected = evidence.interior.map(block => `${block.x},${block.y},${block.z}`).sort()
        await context.waitUntil(async () => {
          source = (await storedPortals(portalDirectory)).find(portal => !before.has(portal.id)
            && portal.data.dimensionalPortalKind === 'SHAPED_NETHER'
            && portal.data.dimensionalCounterpartId
            && JSON.stringify((portal.data.structure?.blocks ?? []).map(block => `${block.x},${block.y},${block.z}`).sort()) === JSON.stringify(expected))
          return source
        }, { label: 'saved shaped Wormholes portal', timeoutMs: 30000 })
        evidence.source = source
        context.expect(source.data.dimensionalCounterpartId, 'Shaped portal has a linked counterpart')
        await context.waitUntil(async () => {
          evidence.counterpart = (await storedPortals(portalDirectory)).find(portal => portal.id === source.data.dimensionalCounterpartId)
          return evidence.counterpart
        }, { label: 'saved Nether counterpart', timeoutMs: 30000 })
        context.expect(String(evidence.counterpart.data.structure.worldKey).includes('the_nether'), 'Counterpart belongs to the Nether', evidence.counterpart)
        for (const block of evidence.interior) {
          const marker = `QA_NON_NATIVE_${block.x}_${block.y}`
          await context.command(`/execute unless block ${block.x} ${block.y} ${block.z} minecraft:nether_portal run tellraw @s ${JSON.stringify({ text: marker })}`, new RegExp(marker))
        }
        await p.serverBlock(p.point(1, 201, 0), 'minecraft:obsidian')
      })
      await context.step('physically walk through the shaped aperture into the Nether', async () => {
        await p.emptyHand()
        let arrived = false
        const marker = `QA_SHAPED_NETHER_${Date.now()}`
        const listener = message => { if (message.includes(marker)) arrived = true }
        const probe = () => p.bot.chat(`/execute if dimension minecraft:the_nether run tellraw @s ${JSON.stringify({ text: marker })}`)
        p.bot.on('messagestr', listener)
        const timer = setInterval(probe, 500)
        try {
          await p.walk(0, () => arrived, 'server confirms Nether arrival', 30000)
          evidence.arrival = { ...p.bot.entity.position }
        } finally {
          clearInterval(timer)
          p.bot.removeListener('messagestr', listener)
        }
      })
      await context.step('break the original frame and require source portal retirement', async () => {
        await context.command('/execute in minecraft:overworld run tp @s 2.5 201.0 3.5', /Teleported/i)
        await p.wait(() => p.bot.blockAt(p.point(3, 201, 0))?.name === 'obsidian', 'source frame chunks return')
        await p.emptyHand()
        await p.bounded(() => p.bot.dig(p.bot.blockAt(p.point(3, 201, 0))), 'break shaped frame')
        await p.serverBlock(p.point(3, 201, 0), 'minecraft:air')
        await context.waitUntil(async () => !(await storedPortals(portalDirectory)).some(portal => portal.id === source.id),
          { label: 'broken shaped source removed from Wormholes', timeoutMs: 15000 })
        evidence.sourceRetired = true
      })
      await context.step('require a clean integration server log', async () => {
        const after = await readFile(logPath, 'utf8')
        context.expect(after.startsWith(logBefore), 'Server log did not rotate during integration')
        evidence.serverErrors = after.slice(logBefore.length).split('\n').filter(line => /\bERROR\b|Exception:|Error:/.test(line))
        context.expect(evidence.serverErrors.length === 0, 'No ShapedPortals integration runtime errors', evidence.serverErrors)
      })
    } finally {
      p.bot.clearControlStates()
      if (p.bot.currentWindow) p.bot.closeWindow(p.bot.currentWindow)
    }
  }
}
