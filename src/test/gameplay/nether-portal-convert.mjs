import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { publicPlayer } from './support/public-player.mjs'
import { storedPortals } from './support/portal-storage.mjs'

const SITE_X = 300
const SITE_Z = 300
const SITE_STEP = 160
const SITE_CLEARANCE = 128
const ENTER_TIMEOUT_MS = 10000

function siteAt(x, z) {
  return {
    platform: { minX: x - 8, maxX: x + 9, y: 199, minZ: z - 8, maxZ: z + 9 },
    frame: { minX: x - 1, maxX: x + 2, y: 200, z },
    stand: { x: x + 0.5, y: 201, z: z + 0.7 },
    probe: { x: x + 1.5, y: 201, z: z + 0.7 }
  }
}

export default {
  name: 'wormholes-nether-portal-convert',
  description: 'Light a Nether portal while standing in it beside an armor stand, require that neither takes vanilla travel before Wormholes converts the portal, then walk through it to the converted Nether counterpart.',
  async run(context) {
    const p = publicPlayer(context)
    const bot = p.bot
    const portalDirectory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const evidence = { site: null, dimensions: [], interior: [], source: null, counterpart: null, conversion: null, walk: null }
    context.report.netherPortalConvert = evidence
    const position = () => ({ x: bot.entity.position.x, y: bot.entity.position.y, z: bot.entity.position.z })
    const centre = cells => ({
      x: cells.reduce((sum, cell) => sum + cell.x, 0) / cells.length + 0.5,
      y: Math.min(...cells.map(cell => cell.y)),
      z: cells.reduce((sum, cell) => sum + cell.z, 0) / cells.length + 0.5
    })
    const overworldCells = (await storedPortals(portalDirectory)).filter(portal => String(portal.data.structure?.worldKey).endsWith(':overworld'))
      .flatMap(portal => portal.data.structure?.blocks ?? [])
    let siteOffset = 0
    while (overworldCells.some(cell => Math.hypot(cell.x - (SITE_X + siteOffset), cell.z - (SITE_Z + siteOffset)) < SITE_CLEARANCE)) siteOffset += SITE_STEP
    const { platform: PLATFORM, frame: FRAME, stand: STAND, probe: PROBE } = siteAt(SITE_X + siteOffset, SITE_Z + siteOffset)
    evidence.site = { x: SITE_X + siteOffset, z: SITE_Z + siteOffset }
    for (let x = FRAME.minX + 1; x < FRAME.maxX; x++) {
      for (let y = FRAME.y + 1; y <= FRAME.y + 3; y++) evidence.interior.push({ x, y, z: FRAME.z })
    }
    const interiorKey = JSON.stringify(evidence.interior.map(cell => `${cell.x},${cell.y},${cell.z}`).sort())
    const convertsInterior = portal => JSON.stringify((portal.data.structure?.blocks ?? []).map(cell => `${cell.x},${cell.y},${cell.z}`).sort()) === interiorKey

    bot.on('game', () => {
      const last = evidence.dimensions.at(-1)
      if (last?.dimension === bot.game.dimension) return
      evidence.dimensions.push({ at: new Date().toISOString(), dimension: bot.game.dimension, from: position() })
    })

    async function stand(command, x, y, z, yaw) {
      bot.clearControlStates()
      await context.command(`/${command}tp @s ${x.toFixed(5)} ${y.toFixed(5)} ${z.toFixed(5)} ${yaw} 0`, new RegExp(`Teleported ${bot.username} `))
      await p.wait(() => bot.game.dimension === 'overworld' && bot.entity.position.distanceTo(p.point(x, y, z)) < 0.3
        && bot.blockAt(p.point(x, y - 1, z)), `standing in the overworld at ${x},${y},${z}`, 20000)
      await context.sleep(400)
    }

    async function armorStandNear(dimension, x, y, z, radius, timeoutMs) {
      if (bot.game.dimension !== dimension) return false
      const marker = `QA_STAND_${bot.username}_${Math.round(x)}_${Math.round(z)}`
      const selector = `@e[type=minecraft:armor_stand,x=${x.toFixed(2)},y=${y.toFixed(2)},z=${z.toFixed(2)},distance=..${radius}]`
      const deadline = Date.now() + timeoutMs
      for (;;) {
        try {
          await context.command(`/execute if entity ${selector} run tellraw ${bot.username} ${JSON.stringify({ text: marker })}`, new RegExp(marker), 1000)
          return true
        } catch {
          if (Date.now() >= deadline || bot.game.dimension !== dimension) return false
        }
      }
    }

    try {
      await context.step('prepare an isolated platform away from the shared arena with Nether portal replacement enabled', async () => {
        const toml = await readFile(path.join(context.server.directory, 'plugins/Wormholes/wormholes.toml'), 'utf8')
        context.expect(/^replace-nether-and-end-portals\s*=\s*true\s*$/m.test(toml), 'wormholes.toml must set replace-nether-and-end-portals = true')
        await context.command('/execute in minecraft:overworld run tp @s 0.5 202 0.5', new RegExp(`Teleported ${bot.username} `))
        await p.wait(() => bot.game.dimension === 'overworld', 'player starts in the overworld', 20000)
        await p.arena()
        const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
        evidence.serverType = /^type=(\S+)\s*$/m.exec(source)?.[1] ?? 'unknown'
        evidence.gameMode = bot.game.gameMode
        const { minX, maxX, y, minZ, maxZ } = PLATFORM
        await p.stage(STAND.x, y + 3, STAND.z + 4)
        await p.wait(() => [minX, maxX].every(x => [minZ, maxZ].every(z => bot.blockAt(p.point(x, y, z)))), 'platform chunks loaded', 30000)
        await context.command(`/fill ${minX} ${y} ${minZ} ${maxX} ${y} ${maxZ} minecraft:stone`, /filled|changed|blocks/i)
        await context.command(`/fill ${minX} ${y + 1} ${minZ} ${maxX} ${y + 6} ${maxZ} minecraft:air`, /filled|changed|blocks/i)
        await p.stage(STAND.x, y + 1, STAND.z + 4)
      })

      let before
      await context.step('build an obsidian frame and stand inside it beside an armor stand', async () => {
        const { minX, maxX, y, z } = FRAME
        await context.command(`/fill ${minX} ${y} ${z} ${maxX} ${y} ${z} minecraft:obsidian`, /filled|changed|blocks/i)
        await context.command(`/fill ${minX} ${y + 4} ${z} ${maxX} ${y + 4} ${z} minecraft:obsidian`, /filled|changed|blocks/i)
        await context.command(`/fill ${minX} ${y + 1} ${z} ${minX} ${y + 3} ${z} minecraft:obsidian`, /filled|changed|blocks/i)
        await context.command(`/fill ${maxX} ${y + 1} ${z} ${maxX} ${y + 3} ${z} minecraft:obsidian`, /filled|changed|blocks/i)
        for (const cell of evidence.interior) await p.serverBlock(p.point(cell.x, cell.y, cell.z), 'minecraft:air')
        before = new Set((await storedPortals(portalDirectory)).map(portal => portal.id))
        await context.command(`/summon minecraft:armor_stand ${PROBE.x} ${PROBE.y} ${PROBE.z}`, /Summoned/i)
        await context.command('/give @s minecraft:flint_and_steel', /Gave|Given/i)
        await stand('', STAND.x, STAND.y, STAND.z, 0)
        await p.equip(/^Flint and Steel$/)
      })

      await context.step('light the frame from inside and wait for Wormholes to convert it', async () => {
        const lit = async () => bot.game.dimension !== 'overworld'
          || (await storedPortals(portalDirectory)).some(portal => !before.has(portal.id) && convertsInterior(portal))
          || await p.serverBlock(p.point(Math.floor(PROBE.x), FRAME.y + 1, FRAME.z), 'minecraft:nether_portal', 1000).then(() => true, () => false)
        await p.bounded(() => bot.activateBlock(bot.blockAt(p.point(Math.floor(STAND.x), FRAME.y, FRAME.z)), p.point(0, 1, 0)), 'ignite the frame from inside')
        evidence.litAt = new Date().toISOString()
        await context.waitUntil(lit, { label: 'the frame lights into a Nether portal', timeoutMs: 5000 })
        await context.waitUntil(async () => {
          evidence.source = (await storedPortals(portalDirectory)).find(portal => !before.has(portal.id)
            && portal.data.dimensionalPortalKind === 'NETHER' && portal.data.dimensionalCounterpartId && convertsInterior(portal))
          return evidence.source
        }, { label: 'lit frame converts into a linked NETHER portal', timeoutMs: 30000 })
        await context.waitUntil(async () => {
          evidence.counterpart = (await storedPortals(portalDirectory)).find(portal => portal.id === evidence.source.data.dimensionalCounterpartId)
          return evidence.counterpart
        }, { label: 'converted portal gains a Nether counterpart', timeoutMs: 30000 })
        context.expect(String(evidence.counterpart.data.structure.worldKey).includes('the_nether'), 'Counterpart belongs to the Nether', evidence.counterpart.data.structure)
      })

      await context.step('neither the player nor the armor stand took vanilla travel before the conversion', async () => {
        const conversion = { dimension: bot.game.dimension, position: position(), dimensions: [...evidence.dimensions] }
        evidence.conversion = conversion
        const problems = []
        if (bot.game.dimension !== 'overworld' || evidence.dimensions.some(entry => entry.dimension !== 'overworld')) {
          const at = position()
          conversion.armorStandAlongside = await armorStandNear(bot.game.dimension, at.x, at.y, at.z, 8, 3000)
          problems.push(`lighting the Nether portal while standing inside it sent the player to ${bot.game.dimension} at ${at.x},${at.y},${at.z} through vanilla travel before Wormholes converted it`
            + (conversion.armorStandAlongside ? ', and the armor stand standing in the frame arrived there with it' : ''))
          await stand('execute in minecraft:overworld run ', STAND.x, STAND.y, STAND.z + 3, 180)
        }
        conversion.armorStandInFrame = await armorStandNear('overworld', PROBE.x, PROBE.y, PROBE.z, 1.5, 3000)
        if (!conversion.armorStandInFrame) problems.push('the armor stand standing in the frame left the overworld before the player crossed')
        for (const cell of evidence.interior) await p.serverBlock(p.point(cell.x, cell.y, cell.z), 'minecraft:air')
        context.expect(problems.length === 0, problems.join('; '), conversion)
      })

      await context.step('walk through the converted portal to the Nether counterpart', async () => {
        const target = centre(evidence.counterpart.data.structure.blocks)
        const walk = { start: position(), target, samples: [] }
        evidence.walk = walk
        const started = Date.now()
        const sampler = setInterval(() => {
          if (walk.samples.length < 200) walk.samples.push({ t: Date.now() - started, dimension: bot.game.dimension, ...position() })
        }, 50)
        try {
          await p.walk(0, () => bot.game.dimension === 'the_nether', 'walking north through the converted portal reaches the Nether', ENTER_TIMEOUT_MS)
          await p.wait(() => bot.game.dimension === 'the_nether' && Math.abs(bot.entity.position.x - target.x) <= 3 && Math.abs(bot.entity.position.z - target.z) <= 3,
            `arrival beside the Nether counterpart at ${target.x},${target.y},${target.z}`, 10000)
        } finally {
          clearInterval(sampler)
          walk.elapsedMs = Date.now() - started
          walk.arrived = { dimension: bot.game.dimension, ...position() }
        }
      })
    } finally {
      bot.clearControlStates()
      if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    }
  }
}
