import path from 'node:path'
import { closeBot, createClientViewBot, loadHarnessModule } from './support/clientview-client.mjs'
import { countBlocks, crossWorldPortals, dimensionName, END, OVERWORLD } from './support/cross-world-portals.mjs'
import { storedPortals } from './support/portal-storage.mjs'

const REMOVED_MARK = 'diamond_block'
const PARTNER_MARK = 'gold_block'
const REMOVAL_DEADLINE_MS = 5000
const PRESENCE_DEADLINE_MS = 30000
const STORAGE_DEADLINE_MS = 15000
const NOTICE_DEADLINE_MS = 5000
const CASES = {
  'cross-world-outbound': { z: 400, partner: END, link: 'outbound' },
  'cross-world-pair': { z: 600, partner: END, link: 'pair' },
  'cross-world-inbound': { z: 800, partner: END, link: 'inbound' },
  'same-world-pair': { z: 1000, partner: OVERWORLD, link: 'pair' },
  'cross-world-mutual': { z: 1200, partner: END, link: 'mutual' }
}

function expectedPartner(link, partner, removedId) {
  const sameWorld = partner === OVERWORLD
  const returnLink = { type: sameWorld ? 'LOCAL' : 'DIMENSIONAL', destination: removedId }
  if (link === 'pair') return sameWorld ? { kept: true, tunnel: null } : { kept: false }
  if (link === 'mutual') return sameWorld ? { kept: true, tunnel: returnLink } : { kept: false }
  if (link === 'inbound') return { kept: true, tunnel: returnLink }
  return { kept: true, tunnel: null }
}

const sameTunnel = (actual, expected) => expected === null
  ? actual === null
  : actual !== null && actual.type === expected.type && actual.destination === expected.destination

export default {
  name: 'wormholes-portal-removal-projection',
  description: 'Delete a projecting 3x3 portal from its menu and verify the deletion contract, notices, no refund, and that its projection, the partner view, relog, and chunk reload show nothing.',
  async run(context) {
    const portals = crossWorldPortals(context)
    const { p, bot } = portals
    const selected = (context.options.command ?? Object.keys(CASES).join(',')).split(',').map(entry => entry.trim()).filter(Boolean)
    for (const name of selected) {
      context.expect(Object.hasOwn(CASES, name), 'Known removal case', { name, known: Object.keys(CASES) })
    }
    const directory = path.join(context.server.directory, 'plugins/Wormholes/portals')
    const evidence = { cases: [] }
    context.report.portalRemovalProjection = evidence
    const viewers = {}
    const streams = {}

    const regionOf = target => ({
      min: { x: target.x - 8, y: 196, z: target.z - 8 },
      max: { x: target.x + 12, y: 207, z: target.z + 12 }
    })

    async function placeViewer(key, target) {
      const viewer = viewers[key]
      const name = viewer.bot.username
      await context.command(`/execute in ${target.dimension} run tp ${name} ${target.stand.x} ${target.stand.y} ${target.stand.z} 180 0`,
        new RegExp(`Teleported ${name} `))
      await context.waitUntil(() => dimensionName(viewer.bot.game.dimension) === dimensionName(target.dimension)
        && viewer.bot.entity.position.distanceTo(p.point(target.stand.x, target.stand.y, target.stand.z)) < 0.3
        && viewer.bot.blockAt(p.point(Math.floor(target.stand.x), 199, Math.floor(target.stand.z))),
        { label: `${name} reaches ${target.name}`, timeoutMs: 30000 })
      await viewer.bot.look(0, 0, true)
    }

    async function connectViewer(key, username) {
      viewers[key] = await context.connectActor(username)
      await context.command(`/gamemode creative ${username}`)
      await context.waitUntil(() => viewers[key].bot.game.gameMode === 'creative', { label: `${username} is in creative mode`, timeoutMs: 10000 })
    }

    async function relog(key) {
      const viewer = viewers[key]
      viewers[key] = await viewer.reconnectAfter(() => viewer.bot.quit('relog after portal removal'), { timeoutMs: 30000 })
    }

    async function reloadChunks(key, target) {
      const name = viewers[key].bot.username
      await context.command(`/execute in ${target.dimension} run tp ${name} ${target.x + 4000.5} 220 ${target.z + 0.5}`,
        new RegExp(`Teleported ${name} `))
      await context.waitUntil(() => viewers[key].bot.entity.position.x > target.x + 3990,
        { label: `${name} leaves the portal chunks`, timeoutMs: 30000 })
      await context.sleep(3000)
      await placeViewer(key, target)
    }

    const visible = (key, material, target) => countBlocks(viewers[key].bot, material, regionOf(target))

    function streamAlive() {
      for (const entry of Object.values(streams)) {
        if (entry.failure) throw entry.failure
      }
      return true
    }

    async function connectStream(key, username) {
      const client = createClientViewBot(loadHarnessModule('mineflayer'), { host: context.server.host, port: context.server.port,
        username, version: bot.version, brand: 'fabric' })
      const entry = { client, failure: null, closing: false, spawned: false }
      streams[key] = entry
      client.spawned.then(() => { entry.spawned = true }, error => { entry.failure ??= error })
      client.bot.on('kicked', reason => { entry.failure ??= new Error(`${username} kicked: ${JSON.stringify(reason)}`) })
      client.bot.on('end', reason => { if (!entry.closing) entry.failure ??= new Error(`${username} disconnected: ${reason}`) })
      client.bot.on('error', error => { entry.failure ??= error })
      await context.waitUntil(() => streamAlive() && entry.spawned, { label: `${username} spawn`, timeoutMs: 30000 })
      await context.waitUntil(() => streamAlive() && client.peer.settled(), { label: `${username} ClientView negotiation`, timeoutMs: 15000 })
      context.expect(client.peer.state === 'CLIENT_VIEW', `${username} ClientView negotiation ended in ${client.peer.state}`, client.peer.negotiations)
    }

    async function closeStream(key) {
      const entry = streams[key]
      if (!entry) return
      entry.closing = true
      entry.client.peer.close()
      entry.client.counters.detach()
      await closeBot(entry.client.bot)
      delete streams[key]
    }

    async function placeStream(key, target) {
      const streamBot = streams[key].client.bot
      const name = streamBot.username
      await context.command(`/execute in ${target.dimension} run tp ${name} ${target.stand.x} ${target.stand.y} ${target.stand.z} 180 0`,
        new RegExp(`Teleported ${name} `))
      await context.waitUntil(() => streamAlive() && dimensionName(streamBot.game.dimension) === dimensionName(target.dimension)
        && streamBot.entity.position.distanceTo(p.point(target.stand.x, target.stand.y, target.stand.z)) < 0.3,
      { label: `${name} reaches ${target.name}`, timeoutMs: 30000 })
    }

    const streamedViews = key => streamAlive() && [...streams[key].client.peer.portals.values()].filter(entry => entry.plate)
    const measure = check => check.stream ? streamedViews(check.stream).length : visible(check.key, check.material, check.target)
    const describe = check => ({
      viewer: check.stream ? streams[check.stream].client.bot.username : viewers[check.key].bot.username,
      observed: check.stream ? 'clientview streamed views' : check.material,
      portal: check.target.name,
      count: measure(check),
      views: check.stream ? streamedViews(check.stream).map(entry => ({ key: entry.portalKey, origin: entry.geometry.origin })) : undefined
    })

    async function expectPresent(check, label) {
      return context.waitUntil(() => {
        const count = measure(check)
        return count > 0 ? count : false
      }, { label, timeoutMs: PRESENCE_DEADLINE_MS })
    }

    function note(record, label, residual) {
      record.residual ??= {}
      record.residual[label] = residual
    }

    async function expectGone(checks, label, record) {
      const started = performance.now()
      try {
        await context.waitUntil(() => checks.every(check => measure(check) === 0), { label, timeoutMs: REMOVAL_DEADLINE_MS })
      } catch (error) {
        note(record, label, checks.map(describe))
        throw error
      }
      const elapsedMs = Math.round(performance.now() - started)
      const until = performance.now() + 2000
      while (performance.now() < until) {
        const residual = checks.map(describe)
        note(record, label, residual)
        context.expect(residual.every(entry => entry.count === 0), `${label} stays gone`, residual)
        await context.sleep(100)
      }
      return elapsedMs
    }

    async function settleAndExpectGone(checks, label, record) {
      await context.sleep(4000)
      await expectGone(checks, label, record)
    }

    async function decorate(target, material) {
      const { x, z } = target
      await portals.enter(target.dimension, target.stand.x, target.stand.y, target.stand.z)
      for (const wall of [z - 6, z + 6]) {
        await context.command(`/fill ${x - 3} 200 ${wall} ${x + 5} 204 ${wall} minecraft:${material}`, /filled|changed|blocks/i)
      }
    }

    async function storedNames() {
      return (await storedPortals(directory)).map(entry => ({ id: entry.id, name: entry.data.name, tunnel: entry.data.tunnel ?? null,
        reciprocal: entry.data['nexus.reciprocal'] === true }))
    }

    function collectNotices(target) {
      const notices = []
      const listener = message => notices.push(message.toString())
      target.on('actionBar', listener)
      return { notices, stop: () => target.removeListener('actionBar', listener) }
    }

    async function expectNotice(collector, portalName, label) {
      await context.waitUntil(() => collector.notices.some(notice => notice.includes(portalName) && notice.includes('Deleted')),
        { label, timeoutMs: NOTICE_DEADLINE_MS })
    }

    function contractHolds(record, removedId, keptId, expected) {
      const after = record.storedAfter
      if (after.some(entry => entry.id === removedId)) return false
      const survivor = after.find(entry => entry.id === keptId)
      if (!expected.kept) return !survivor
      return Boolean(survivor) && sameTunnel(survivor.tunnel, expected.tunnel) && !survivor.reciprocal
    }

    async function soft(record, label, action) {
      try {
        await context.step(label, action)
      } catch (error) {
        record.failures ??= []
        record.failures.push({ step: label, error: error.message, details: error.details })
      }
    }

    async function runCase(name) {
      const { z, partner, link } = CASES[name]
      const prefix = `rm${Date.now().toString(36)}`
      const removed = portals.aperture({ dimension: OVERWORLD, x: 0, z, name: `${prefix}_a` })
      const kept = portals.aperture({ dimension: partner, x: 24, z, name: `${prefix}_b` })
      const record = { name, link, removed: removed.name, partner: kept.name, partnerDimension: partner }
      evidence.cases.push(record)
      const partnerViews = link !== 'outbound'
      const removedProjects = link !== 'inbound'
      const checks = [
        { key: 'removed', material: PARTNER_MARK, target: removed },
        { key: 'partner', material: REMOVED_MARK, target: kept },
        { stream: 'removed', target: removed },
        { stream: 'partner', target: kept }
      ]

      await context.step(`${name}: prepare arenas and markers`, async () => {
        await portals.arena(OVERWORLD, z)
        await portals.supply()
        if (partner !== OVERWORLD) await portals.arena(partner, z)
        await decorate(removed, REMOVED_MARK)
        await decorate(kept, PARTNER_MARK)
      })
      await context.step(`${name}: wand-build both 3x3 portals`, async () => {
        await portals.build(removed)
        await portals.build(kept)
      })
      await context.step(`${name}: link (${link})`, async () => {
        if (link === 'pair') record.pairing = await portals.linkAndReturn(removed, kept)
        if (link === 'outbound') await portals.link(removed, kept)
        if (link === 'inbound') await portals.link(kept, removed)
        if (link === 'mutual') {
          await portals.link(removed, kept)
          await portals.link(kept, removed)
        }
        record.storedBefore = await storedNames()
      })
      await context.step(`${name}: viewers observe the projections`, async () => {
        await placeViewer('removed', removed)
        await placeViewer('partner', kept)
        await placeStream('removed', removed)
        await placeStream('partner', kept)
        await portals.standAt(removed)
        await bot.look(0, 0, true)
        if (removedProjects) {
          record.removedViewBefore = await expectPresent(checks[0], 'removed-portal viewer sees the partner projection')
          record.removedStreamBefore = await expectPresent(checks[2], 'removed-portal ClientView viewer receives the portal stream')
        }
        if (partnerViews) {
          record.partnerViewBefore = await expectPresent(checks[1], 'partner viewer sees the removed-portal side')
          record.partnerStreamBefore = await expectPresent(checks[3], 'partner ClientView viewer receives the partner stream')
        }
      })
      const removedEntry = record.storedBefore.find(entry => entry.name === removed.name)
      const keptEntry = record.storedBefore.find(entry => entry.name === kept.name)
      context.expect(removedEntry && keptEntry, 'Both portals are saved before deletion', record.storedBefore)
      const expected = expectedPartner(link, partner, removedEntry.id)
      record.expectedPartner = expected
      const removerNotices = collectNotices(bot)
      const partnerNotices = collectNotices(viewers.partner.bot)
      try {
        await context.step(`${name}: delete the projecting portal from its menu`, async () => {
          const before = bot.inventory.items().map(item => `${item.name}x${item.count}`)
          await portals.remove(removed)
          await bot.look(0, 0, true)
          record.inventory = { before, after: bot.inventory.items().map(item => `${item.name}x${item.count}`) }
          record.storedAfter = await storedNames()
        })
        await soft(record, `${name}: deletion follows the Bukkit pair contract`, async () => {
          try {
            await context.waitUntil(async () => {
              record.storedAfter = await storedNames()
              return contractHolds(record, removedEntry.id, keptEntry.id, expected)
            }, { label: `${name}: saved portals match the deletion contract`, timeoutMs: STORAGE_DEADLINE_MS, intervalMs: 250 })
          } catch (error) {
            error.details = { storedAfter: record.storedAfter, expected }
            throw error
          }
          context.expect(record.inventory.before.join(',') === record.inventory.after.join(','), 'Deleting a portal refunds nothing', record.inventory)
        })
        await soft(record, `${name}: deletion notices reach nearby players`, async () => {
          await expectNotice(removerNotices, removed.name, `${removed.name} Deleted notice for the remover`)
          if (!expected.kept) await expectNotice(partnerNotices, kept.name, `${kept.name} Deleted notice for the partner viewer`)
        })
      } finally {
        record.notices = { remover: removerNotices.notices, partner: partnerNotices.notices }
        removerNotices.stop()
        partnerNotices.stop()
      }
      await soft(record, `${name}: every viewer loses the removed portal projection`, async () => {
        record.cleanupMs = await expectGone(checks, `${name}: every viewer loses the removed portal projection`, record)
      })
      await soft(record, `${name}: relog every viewer`, async () => {
        await relog('removed')
        await placeViewer('removed', removed)
        await relog('partner')
        await placeViewer('partner', kept)
        for (const [key, target] of [['removed', removed], ['partner', kept]]) {
          const username = streams[key].client.bot.username
          await closeStream(key)
          await connectStream(key, username)
          await placeStream(key, target)
        }
        await settleAndExpectGone(checks, `${name}: nothing projected after relog`, record)
      })
      await soft(record, `${name}: unload and reload the portal chunks`, async () => {
        await reloadChunks('removed', removed)
        await reloadChunks('partner', kept)
        await settleAndExpectGone(checks.slice(0, 2), `${name}: nothing projected after chunk reload`, record)
      })
      if (partnerViews) {
        await soft(record, `${name}: the partner portal no longer leads to the removed portal`, async () => {
          const partnerExists = await portals.exists(kept)
          record.partnerExists = partnerExists
          if (!partnerExists) return
          await portals.standAt(kept)
          await portals.emptyHand()
          await bot.look(0, 0, true)
          bot.setControlState('forward', true)
          try {
            await context.waitUntil(() => bot.entity.position.z < kept.z - 2
              || dimensionName(bot.game.dimension) !== dimensionName(kept.dimension)
              || Math.abs(bot.entity.position.x - (kept.x + 1.5)) > 6,
            { label: 'walk through the partner aperture', timeoutMs: 10000 })
          } finally {
            bot.clearControlStates()
          }
          await context.sleep(1000)
          record.partnerWalk = { dimension: bot.game.dimension, position: { ...bot.entity.position } }
          context.expect(dimensionName(bot.game.dimension) === dimensionName(kept.dimension)
            && Math.abs(bot.entity.position.x - (kept.x + 1.5)) < 6,
          'The partner portal does not send travellers to the removed portal', record.partnerWalk)
        })
      }
    }

    const failures = []
    try {
      await context.step('reset portals, enable console teardown logs, and connect viewers', async () => {
        await portals.reset()
        if (/disabled/i.test(await context.command('/wh debug toggle', /Debug logging (enabled|disabled)/i))) {
          await context.command('/wh debug toggle', /Debug logging enabled/i)
        }
        await connectViewer('removed', 'RmViewA')
        await connectViewer('partner', 'RmViewB')
        await connectStream('removed', 'RmStreamA')
        await connectStream('partner', 'RmStreamB')
      })
      for (const name of selected) {
        try {
          await runCase(name)
          const record = evidence.cases.at(-1)
          for (const failure of record.failures ?? []) failures.push({ name, ...failure })
        } catch (error) {
          failures.push({ name, error: error.message, details: error.details })
        } finally {
          bot.clearControlStates()
          try {
            await portals.cleanup()
          } catch (error) {
            failures.push({ name: `${name} cleanup`, error: error.message })
          }
        }
      }
      evidence.failures = failures
      context.expect(failures.length === 0, 'Every removal case cleans up every projection', failures)
    } finally {
      bot.clearControlStates()
      for (const key of Object.keys(streams)) await closeStream(key)
    }
  }
}
