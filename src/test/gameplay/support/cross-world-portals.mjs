import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { publicPlayer } from './public-player.mjs'

export const OVERWORLD = 'minecraft:overworld'
export const END = 'minecraft:the_end'

export const dimensionName = value => String(value ?? '').replace(/^minecraft:/, '')

export function crossWorldPortals(context) {
  const p = publicPlayer(context)
  const bot = p.bot
  const created = []

  function aperture({ dimension, x, z = 0, name, horizontal = false }) {
    const look = horizontal ? p.point(x + 1.5, 199.5, z + 0.5) : p.point(x + 1.5, 201.5, z + 0.5)
    const first = horizontal ? p.point(x, 199, z - 1) : p.point(x, 200, z)
    const second = horizontal ? p.point(x + 2, 199, z + 1) : p.point(x + 2, 202, z)
    return { dimension, x, z, name, horizontal, first, second, look, stand: { x: x + 1.5, y: 200, z: z + 3.5 } }
  }

  async function enter(dimension, x, y, z, { settle = true } = {}) {
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    bot.clearControlStates()
    await context.command(`/execute in ${dimension} run tp @s ${x.toFixed(5)} ${y.toFixed(5)} ${z.toFixed(5)} 180 0`,
      new RegExp(`Teleported ${bot.username} `))
    await p.wait(() => dimensionName(bot.game.dimension) === dimensionName(dimension)
      && (!settle || bot.entity.position.distanceTo(p.point(x, y, z)) < 0.3)
      && bot.blockAt(p.point(Math.floor(x), Math.floor(y) - 1, Math.floor(z))), `arrival in ${dimension}`, 30000)
  }

  async function arena(dimension, z = 0) {
    context.expect(context.server.host === '127.0.0.1' && context.server.directory,
      'Portal scenarios require a local isolated instance')
    const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
    context.expect(/^isolated=true\s*$/m.test(source), 'Portal scenarios require isolated=true')
    await enter(dimension, 0.5, 202, z + 0.5, { settle: false })
    if (bot.game.gameMode !== 'creative') await context.command('/gamemode creative @s', /creative/i)
    if (dimensionName(dimension) === 'the_end') {
      await context.command('/execute in minecraft:the_end run kill @e[type=minecraft:ender_dragon]', /Killed|No entity/i)
    }
    await p.wait(() => [-8, 40].every(x => [z - 8, z + 12].every(cz => bot.blockAt(p.point(x, 199, cz)))),
      'arena chunks loaded', 30000)
    await context.command(`/fill -8 199 ${z - 8} 40 199 ${z + 12} minecraft:stone`, /filled|changed|blocks/i)
    await context.command(`/fill -8 200 ${z - 8} 40 207 ${z + 12} minecraft:air`, /filled|changed|blocks/i)
    await p.stage(0.5, 200, z + 2.5)
  }

  async function reset() {
    await context.command('/wh admin deleteallportals', /Deleted \d+ portals? and cleared/i, 15000)
  }

  async function supply() {
    await context.command('/clear @s', /Removed|No items/i)
    await context.command('/wh wand rune=false', /wand/i)
  }

  async function setBlock(position, block) {
    await context.command(`/setblock ${position.x} ${position.y} ${position.z} ${block}`, /Changed the block|Could not set/i)
    await p.serverBlock(position, block)
  }

  async function closeMenus() {
    await context.sleep(500)
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    await p.wait(() => !bot.currentWindow, 'portal menus closed')
  }

  async function emptyHand() {
    await closeMenus()
    try {
      await p.emptyHand()
    } catch {
      await closeMenus()
      await p.emptyHand()
    }
  }

  async function standAt(target) {
    await enter(target.dimension, target.stand.x, target.stand.y, target.stand.z)
  }

  async function open(target) {
    await standAt(target)
    await p.equip(/^Portal Wand$/)
    await context.sleep(250)
    await bot.lookAt(target.look, true)
    bot.activateItem()
    await p.wait(() => p.items().some(item => /^Rename Portal$/.test(item.name)), `portal menu for ${target.name}`)
  }

  async function build(target) {
    await standAt(target)
    await setBlock(target.first, 'minecraft:glass')
    await setBlock(target.second, 'minecraft:glass')
    await p.wait(() => bot.blockAt(target.first)?.name === 'glass' && bot.blockAt(target.second)?.name === 'glass',
      'client sees selection markers')
    await p.equip(/^Portal Wand$/)
    await p.leftClick(target.first)
    await p.rightClick(target.second)
    await p.leftClick(target.first)
    created.push(target)
    await open(target)
    await p.click(/^Rename Portal$/)
    await p.wait(() => !bot.currentWindow, 'rename chat prompt')
    bot.chat(target.name)
    await p.wait(() => p.items().some(item => item.lore.some(line => line.includes(target.name))), 'renamed portal menu')
    await emptyHand()
    if (target.horizontal) {
      const { x, z } = target
      await context.command(`/fill ${x} 199 ${z - 1} ${x + 2} 199 ${z + 1} minecraft:air`, /filled|changed|blocks/i)
      await context.command(`/fill ${x} 190 ${z - 1} ${x + 2} 190 ${z + 1} minecraft:glass`, /filled|changed|blocks/i)
      for (const position of [target.first, target.second]) await p.serverBlock(position, 'minecraft:air')
      return
    }
    for (const position of [target.second, target.first]) {
      await p.bounded(() => bot.dig(bot.blockAt(position)), 'remove temporary selection marker')
      await p.serverBlock(position, 'minecraft:air')
    }
  }

  async function link(target, destination) {
    await open(target)
    await p.click(/^Destination$/)
    await p.click(new RegExp(`^${destination.name}$`))
    await p.wait(() => !bot.currentWindow, 'destination selection closes')
    await open(target)
    context.expect(p.items().some(item => item.name === 'Destination'
      && item.lore.some(line => line.includes(destination.name))), 'Portal menu confirms destination', p.items())
    bot.closeWindow(bot.currentWindow)
  }

  async function linkAndReturn(target, destination) {
    await link(target, destination)
    await open(target)
    await p.click(/^Destination$/)
    const reply = context.waitForMessage(/now link both ways/i, 10000)
    reply.catch(() => {})
    await p.click(/^Link and return$/)
    const message = await reply
    await p.wait(() => !bot.currentWindow, 'pairing closes the menu')
    await closeMenus()
    return message
  }

  async function remove(target) {
    await open(target)
    await p.click(/^Delete Portal$/, { shift: true })
    await p.wait(() => !bot.currentWindow, 'portal deletion closes menu')
    await closeMenus()
    const index = created.indexOf(target)
    if (index >= 0) created.splice(index, 1)
  }

  async function exists(target) {
    await standAt(target)
    await p.equip(/^Portal Wand$/)
    await context.sleep(250)
    await bot.lookAt(target.look, true)
    bot.activateItem()
    try {
      await p.wait(() => p.items().some(item => /^Rename Portal$/.test(item.name)), `portal menu probe for ${target.name}`, 2500)
      await closeMenus()
      return true
    } catch {
      await closeMenus()
      return false
    }
  }

  async function cleanup() {
    bot.clearControlStates()
    const failures = []
    for (const target of [...created].reverse()) {
      try {
        if (await exists(target)) await remove(target)
      } catch (error) { failures.push(error) }
    }
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    if (failures.length) throw new AggregateError(failures, 'Portal cleanup failed; dispose of this isolated instance')
  }

  return { p, bot, created, aperture, enter, arena, reset, supply, setBlock, closeMenus, emptyHand, standAt, open, build, link, linkAndReturn, remove, exists, cleanup }
}

export function countBlocks(bot, material, box) {
  const Vec3 = bot.entity.position.constructor
  let count = 0
  for (let x = box.min.x; x <= box.max.x; x++) {
    for (let y = box.min.y; y <= box.max.y; y++) {
      for (let z = box.min.z; z <= box.max.z; z++) {
        if (bot.blockAt(new Vec3(x, y, z))?.name === material) count++
      }
    }
  }
  return count
}
