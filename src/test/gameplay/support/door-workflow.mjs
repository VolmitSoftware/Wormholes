import { publicPlayer } from './public-player.mjs'

export function doorWorkflow(context, { controller = context } = {}) {
  const p = publicPlayer(context, { controller })
  const placed = []
  async function placeDoor(x, item, material) {
    await p.stage(x + 0.5, 200, 3.5, 180)
    await p.place(p.point(x, 200, 0), item, material)
    placed.push({ x, material })
    await p.serverBlock(p.point(x, 201, 0), `minecraft:${material}[half=upper]`)
  }

  async function openDoor(position) {
    await p.emptyHand()
    const blockName = p.bot.blockAt(position)?.name
    context.expect(blockName?.endsWith('_door'), 'Expected physical door before opening', { position, blockName })
    const marker = `QA_DOOR_STATE_${p.bot.username}_${Date.now()}`
    const response = controller.waitForMessage(new RegExp(`${marker}:(open|closed)`), 5000)
    for (const [state, value] of [['open', true], ['closed', false]]) {
      await p.command(`/execute if block ${position.x} ${position.y} ${position.z} minecraft:${blockName}[open=${value}] run tellraw ${controller.bot.username} ${JSON.stringify({ text: `${marker}:${state}` })}`)
    }
    const isOpen = (await response).endsWith(':open')
    await p.wait(() => p.bot.blockAt(position)?.getProperties().open === isOpen, 'client receives physical door state')
    if (isOpen) {
      await p.rightClick(position)
      await p.wait(() => p.bot.blockAt(position)?.getProperties().open === false, 'door closes before a new open cycle')
      await p.serverBlock(position, `minecraft:${blockName}[open=false]`)
      await p.bounded(() => p.bot.waitForTicks(3), 'door closed cycle settles')
    }
    await p.rightClick(position)
    await p.wait(() => p.bot.blockAt(position)?.getProperties().open === true, 'door is physically open')
  }

  async function dimensionCrossing(position, entering) {
    const facing = p.bot.blockAt(position)?.getProperties().facing
    const offsets = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
    const normal = entering ? [0, 1] : offsets[facing]?.map(value => -value)
    context.expect(normal, 'Door facing is available', { position, facing })
    const [dx, dz] = normal
    const start = position.offset(0.5 + dx * 3, 0, 0.5 + dz * 3)
    await p.stage(start.x, start.y, start.z)
    await openDoor(position)
    const marker = `QA_DIMENSION_${p.bot.username}_${entering ? 'POCKET' : 'RETURN'}_${Date.now()}`
    let arrived = false
    let probeFailure
    const received = message => { if (message.includes(marker)) arrived = true }
    const probe = () => p.command(`/execute ${entering ? 'if' : 'unless'} dimension wormholes:pockets run tellraw ${controller.bot.username} ${JSON.stringify({ text: marker })}`)
    controller.bot.on('messagestr', received)
    const poll = setInterval(() => { void probe().catch(error => { probeFailure = error }) }, 500)
    try {
      await probe()
      await p.walk(Math.atan2(dx, dz), () => {
        if (probeFailure) throw probeFailure
        return arrived
      }, 'server confirms pocket dimension transition', 60000)
      await p.wait(() => p.bot.blockAt(p.bot.entity.position.floored()), 'destination chunk is loaded')
      return { position: { ...p.bot.entity.position }, dimensionProof: marker }
    } finally {
      clearInterval(poll)
      controller.bot.removeListener('messagestr', received)
    }
  }

  async function returnFromPocket() {
    const locations = p.bot.findBlocks({ matching: block => block.name.endsWith('_door')
      && block.getProperties().half === 'lower', maxDistance: 48, count: 16 })
    context.expect(locations.length === 1, 'Pocket exposes exactly one return door', { locations })
    return dimensionCrossing(locations[0], false)
  }
  return { p, placed, placeDoor, openDoor, dimensionCrossing, returnFromPocket }
}
