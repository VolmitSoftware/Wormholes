export default {
  name: 'wormholes-placeholder-api',
  description: 'Checks named portal destinations, signed block coordinates, RTP countdowns and PlaceholderAPI reloads.',
  async run(context) {
    const source = '90000000-0000-0000-0000-000000000001'
    const rtp = '90000000-0000-0000-0000-000000000003'
    const firstTwin = '90000000-0000-0000-0000-000000000005'
    const secondTwin = '90000000-0000-0000-0000-000000000006'
    const fields = ['x', 'y', 'z', 'time-remaining']
    const durationPattern = /^(?:([1-9]\d*)d )?(?:([1-9]\d*)h )?(?:([1-9]\d*)m )?(\d+)s$/
    const evidence = { queries: [], rtp: [], reloadMessages: [] }
    context.report.placeholderApi = evidence
    let sequence = 0
    let seeded = false

    async function parse(selector, field, accept, timeout = 10000) {
      const placeholder = `%wormholes_portal.${selector}.destination.${field}%`
      const deadline = Date.now() + timeout
      let actual
      do {
        const marker = `WH_PAPI_${++sequence}=`
        const line = await context.command(`/papi parse me ${marker}${placeholder}`, new RegExp(`^${marker}`), 5000)
        actual = line.slice(marker.length)
        const matches = accept instanceof RegExp ? accept.test(actual) : actual === String(accept)
        if (matches) {
          evidence.queries.push({ placeholder, actual, at: Date.now() })
          return actual
        }
        await context.sleep(250)
      } while (Date.now() < deadline)
      context.expect(false, `${placeholder} resolves to the expected value`, { actual, expected: String(accept) })
    }

    async function coordinates(selector, x, y, z) {
      await parse(selector, 'x', x)
      await parse(selector, 'y', y)
      await parse(selector, 'z', z)
    }

    async function unavailable(selector) {
      for (const field of fields) {
        await parse(selector, field, '---')
      }
    }

    async function readyRtp() {
      const deadline = Date.now() + 60000
      do {
        const line = await context.command('/whpapitest rtp', /^FIXTURE rtp /, 5000)
        const match = line.match(/^FIXTURE rtp ready=true x=(-?\d+) y=(-?\d+) z=(-?\d+) next=(\d+) now=(\d+)$/)
        if (match) {
          const result = { x: Number(match[1]), y: Number(match[2]), z: Number(match[3]), next: Number(match[4]), now: Number(match[5]) }
          context.expect(result.next > result.now, 'The real RTP runtime has a future rotation deadline', result)
          context.expect(result.x < 0 && result.z < 0 && result.y === 101, 'The real RTP search chose the safe test platform', result)
          evidence.rtp.push(result)
          return result
        }
        await context.sleep(500)
      } while (Date.now() < deadline)
      context.expect(false, 'The real RTP search produced a ready destination within 60 seconds')
    }

    function seconds(value) {
      const match = value.match(durationPattern)
      context.expect(Boolean(match), 'Countdown omits zero days, hours and minutes and keeps seconds', { value })
      context.expect(Number(match[2] ?? 0) < 24 && Number(match[3] ?? 0) < 60 && Number(match[4]) < 60,
        'Countdown units are normalized', { value })
      return Number(match[1] ?? 0) * 86400 + Number(match[2] ?? 0) * 3600 + Number(match[3] ?? 0) * 60 + Number(match[4])
    }

    async function timer(selector, runtime) {
      const value = await parse(selector, 'time-remaining', durationPattern)
      const actual = seconds(value)
      const expected = Math.max(0, (runtime.next - Date.now()) / 1000)
      context.expect(actual > 0 && Math.abs(actual - expected) <= 3,
        'Countdown matches the real RTP rotation deadline', { selector, value, actual, expected, runtime })
      return actual
    }

    try {
      await context.step('Create linked, ambiguous, unlinked and timed RTP portals', async () => {
        seeded = true
        const line = await context.command('/whpapitest setup', /^FIXTURE (?:ready|failed)/, 30000)
        context.expect(line.startsWith('FIXTURE ready '), 'The isolated portal fixture initialized', { line })
        evidence.fixture = line
      })

      await context.step('Named and UUID destinations return signed integer block coordinates', async () => {
        await coordinates('north-gate', -5, 82, -6)
        await coordinates(source, -5, 82, -6)
        await parse('north-gate', 'time-remaining', '---')
      })

      await context.step('Duplicate normalized names remain unambiguous through UUID selectors', async () => {
        await unavailable('twin-gate')
        await coordinates(firstTwin, -5, 82, -6)
        await coordinates(secondTwin, -13, 102, -14)
      })

      await context.step('Missing and unlinked portal destinations return the unavailable marker', async () => {
        await unavailable('unlinked-gate')
        await unavailable('missing-gate')
        await unavailable('90000000-0000-0000-0000-000000000099')
      })

      await context.step('Named RTP coordinates and countdown match the active runtime destination', async () => {
        const runtime = await readyRtp()
        await coordinates('timed-gate', runtime.x, runtime.y, runtime.z)
        await coordinates(rtp, runtime.x, runtime.y, runtime.z)
        const before = await timer('timed-gate', runtime)
        await context.sleep(2100)
        const after = await timer('timed-gate', runtime)
        context.expect(before - after >= 1 && before - after <= 5, 'RTP countdown decreases with elapsed time', { before, after })
        evidence.countdown = { before, after }
      })

      await context.step('Renaming a portal updates its normalized name while preserving UUID access', async () => {
        const line = await context.command('/whpapitest rename', /^FIXTURE (?:renamed|failed)/, 10000)
        context.expect(line === 'FIXTURE renamed', 'The source portal was renamed', { line })
        await unavailable('north-gate')
        await coordinates('changed-gate', -5, 82, -6)
        await coordinates(source, -5, 82, -6)
      })

      await context.step('Registered placeholders survive the real PlaceholderAPI reload command', async () => {
        const listener = line => evidence.reloadMessages.push(line)
        context.bot.on('messagestr', listener)
        try {
          await context.command('/papi reload', /placeholder hook\(s\) registered!/i, 15000)
        } finally {
          context.bot.removeListener('messagestr', listener)
        }
        await coordinates('changed-gate', -5, 82, -6)
        await coordinates(source, -5, 82, -6)
        await unavailable('twin-gate')
        const runtime = await readyRtp()
        await coordinates('timed-gate', runtime.x, runtime.y, runtime.z)
        await timer('timed-gate', runtime)
      })
    } finally {
      if (seeded) {
        await context.step('Remove every portal owned by the fixture', async () => {
          await context.command('/whpapitest cleanup', /^FIXTURE cleaned$/, 10000)
        })
      }
    }
  }
}
