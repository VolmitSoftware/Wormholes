import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { doorWorkflow } from './support/door-workflow.mjs'

export default {
  name: 'wormholes-public-pocket-rescue',
  description: 'Enter a placed Personal door, move outside its pocket shell, and require rescue without particle errors.',
  async run(context) {
    const { p, placeDoor, dimensionCrossing } = doorWorkflow(context)
    const logPath = path.join(context.server.directory, 'logs', 'latest.log')
    const before = await readFile(logPath, 'utf8')
    let placed = false
    try {
      await context.step('place and physically enter a Personal pocket', async () => {
        await p.arena()
        await context.command('/wh door type=personal', /door/i)
        await placeDoor(8, /^Personal Dimension Door$/, 'dark_oak_door')
        placed = true
        context.report.pocketEntry = await dimensionCrossing(p.point(8, 200, 0), true)
      })
      await context.step('outside-shell movement completes rescue', async () => {
        const doors = p.bot.findBlocks({ matching: block => block.name.endsWith('_door') &&
          block.getProperties().half === 'lower', maxDistance: 48, count: 16 })
        context.expect(doors.length === 1, 'Expected one physical pocket return door')
        const door = p.bot.blockAt(doors[0])
        const offsets = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }
        const [dx, dz] = offsets[door.getProperties().facing]
        const outside = doors[0].offset(0.5 + 3 * dx, 0, 0.5 + 3 * dz)
        const marker = `QA_RESCUED_${Date.now()}`
        let arrived = false
        let probeFailure
        const received = message => { if (message.includes(marker)) arrived = true }
        p.bot.on('messagestr', received)
        const probe = () => context.command(`/execute unless dimension wormholes:pockets run tellraw @s ${JSON.stringify({ text: marker })}`)
        const poll = setInterval(() => { void probe().catch(error => { probeFailure = error }) }, 500)
        try {
          await context.command(`/tp @s ${outside.x} ${outside.y} ${outside.z}`, /Teleported/)
          await p.walk(0, () => {
            if (probeFailure) throw probeFailure
            return arrived
          }, 'outside-shell rescue returns to source dimension', 15000)
          context.expect(Math.abs(p.bot.entity.position.x - 8.5) < 6, 'Rescue uses source return ticket')
          context.report.pocketRescue = { outside, returned: { ...p.bot.entity.position }, dimensionProof: marker }
        } finally {
          clearInterval(poll)
          p.bot.removeListener('messagestr', received)
        }
      })
      await context.step('rescue produces no server exceptions', async () => {
        const after = await readFile(logPath, 'utf8')
        context.expect(after.startsWith(before), 'Runtime log rotated during rescue')
        const errors = after.slice(before.length).split('\n').filter(line => /\bERROR\b|Exception:/.test(line))
        context.expect(errors.length === 0, 'Pocket rescue logged an error', { errors })
      })
    } finally {
      p.bot.clearControlStates()
      if (placed) {
        await context.command('/execute in minecraft:overworld run tp @s 8.5 200 3.5', /Teleported/)
        await p.wait(() => p.bot.blockAt(p.point(8, 200, 0))?.name === 'dark_oak_door', 'source door cleanup chunk')
        await p.emptyHand()
        await p.bounded(() => p.bot.dig(p.bot.blockAt(p.point(8, 200, 0))), 'remove rescue fixture door')
        await p.serverBlock(p.point(8, 200, 0), 'minecraft:air')
      }
    }
  }
}
