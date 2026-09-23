import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
import path from 'node:path'

export function publicPlayer(context, { controller = context } = {}) {
  const bot = context.bot
  const require = createRequire(process.argv[1])
  const nbt = require('prismarine-nbt')
  const ChatMessage = require('prismarine-chat')(bot.version)
  const point = (x, y, z) => new bot.entity.position.constructor(x, y, z)
  const wait = (predicate, label, timeoutMs = 10000) => context.waitUntil(predicate, { label, timeoutMs })
  const command = (value, expected, timeoutMs) => controller.command(controller === context ? value
    : `/execute as ${bot.username} at @s run ${value.replace(/^\//, '')}`, expected, timeoutMs)
  const text = value => {
    if (value === undefined || value === null) return ''
    return new ChatMessage(value.type ? nbt.simplify(value) : value).toString().replace(/§./g, '')
  }
  const itemName = item => text(item?.customName) || item?.displayName || ''
  const items = () => (bot.currentWindow?.slots ?? []).slice(0, bot.currentWindow?.inventoryStart ?? 0)
    .flatMap((item, slot) => item ? [{ slot, name: itemName(item), material: item.name,
      lore: (item.componentMap?.get('lore')?.data ?? []).map(text) }] : [])

  async function bounded(action, label, timeoutMs = 10000) {
    let timer
    try {
      return await Promise.race([action(), new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} exceeded ${timeoutMs} ms`)), timeoutMs)
      })])
    } finally {
      clearTimeout(timer)
    }
  }

  async function equip(pattern) {
    await wait(() => bot.inventory.items().some(item => pattern.test(itemName(item))), `inventory contains ${pattern}`)
    const item = bot.inventory.items().find(candidate => pattern.test(itemName(candidate)))
    await bounded(() => bot.equip(item, 'hand'), `equip ${pattern}`)
    return item
  }

  async function emptyHand() {
    await context.sleep(150)
    await bounded(() => bot.unequip('hand'), 'empty main hand')
    const slot = bot.quickBarSlot
    bot.setQuickBarSlot((slot + 1) % 9)
    bot.setQuickBarSlot(slot)
    const marker = `QA_EMPTY_HAND_${bot.username}`
    await command(`/execute unless items entity @s weapon.mainhand * run tellraw ${controller.bot.username} ${JSON.stringify({ text: marker })}`, new RegExp(marker))
  }

  async function stage(x, y, z, yaw = 0) {
    bot.clearControlStates()
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow)
    await command(`/tp @s ${x.toFixed(5)} ${y.toFixed(5)} ${z.toFixed(5)} ${yaw} 0`, new RegExp(`Teleported ${bot.username} `))
    await wait(() => bot.entity.position.distanceTo(point(x, y, z)) < 0.3 && bot.blockAt(point(x, y - 1, z)), 'staging position and chunk')
  }

  async function serverBlock(position, block) {
    const marker = `QA_BLOCK_${bot.username}_${position.x}_${position.y}_${position.z}`
    await command(`/execute if block ${position.x} ${position.y} ${position.z} ${block} run tellraw ${controller.bot.username} ${JSON.stringify({ text: marker })}`,
      new RegExp(marker))
  }

  async function place(position, namePattern, material) {
    await equip(namePattern)
    await wait(() => {
      const block = bot.blockAt(position.offset(0, -1, 0))
      return block && block.name !== 'air'
    }, 'placement supporting block arrives')
    const reference = bot.blockAt(position.offset(0, -1, 0))
    await bounded(() => bot.placeBlock(reference, point(0, 1, 0)), 'player block placement')
    await wait(() => bot.blockAt(position)?.name === material, `client sees ${material}`)
    await serverBlock(position, `minecraft:${material}`)
  }

  async function leftClick(position) {
    await bot.lookAt(position.offset(0.5, 0.5, 0.5), true)
    bot._client.write('block_dig', { status: 0, location: position, face: 1, sequence: 0 })
    bot.swingArm('right')
    await context.sleep(100)
    bot._client.write('block_dig', { status: 1, location: position, face: 1, sequence: 0 })
  }

  async function rightClick(position) {
    const block = bot.blockAt(position)
    context.expect(block, 'Interaction block is loaded', { position })
    await bounded(() => bot.activateBlock(block), 'player right-click')
  }

  async function click(pattern, { shift = false } = {}) {
    await wait(() => items().some(item => pattern.test(item.name)), `menu contains ${pattern}`)
    const before = bot.currentWindow
    const item = items().find(candidate => pattern.test(candidate.name))
    context.expect(bot.currentWindow === before, 'Menu is still current before clicking')
    context.report.publicPlayerMenus ??= []
    context.report.publicPlayerMenus.push({ title: text(before.title), selection: item, items: items() })
    await bounded(() => bot.clickWindow(item.slot, 0, shift ? 1 : 0), `click ${pattern}`)
  }

  async function walk(yaw, predicate, label, timeoutMs = 15000) {
    await bot.look(yaw, 0, true)
    bot.setControlState('forward', true)
    try {
      await wait(predicate, label, timeoutMs)
      context.expect(bot.health > 0, 'Traveler remains alive')
    } finally {
      bot.clearControlStates()
      context.report.publicPlayerLastPosition = { ...bot.entity.position }
    }
  }

  async function arena() {
    context.expect(context.server.host === '127.0.0.1' && context.server.directory,
      'Player construction scenarios require a local isolated instance')
    const source = await readFile(path.join(context.server.directory, '.server-source'), 'utf8')
    context.expect(/^isolated=true\s*$/m.test(source), 'Player construction scenarios require isolated=true')
    if (bot.game.gameMode !== 'creative') await context.command('/gamemode creative @s', /creative/i)
    await stage(0.5, 202, 0.5)
    await context.command('/fill -8 199 -8 40 199 12 minecraft:stone', /filled|changed|blocks/i)
    await context.command('/fill -8 200 -8 40 207 12 minecraft:air', /filled|changed|blocks/i)
    await stage(0.5, 200, 2.5)
  }

  return { bot, point, wait, command, text, itemName, items, bounded, equip, emptyHand, stage, serverBlock,
    place, leftClick, rightClick, click, walk, arena }
}
