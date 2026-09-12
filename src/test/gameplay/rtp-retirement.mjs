import fs from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'wormholes-rtp-retirement',
  description: 'Removes a real mob after RTP teleport, verifies retired scheduler cleanup and then completes another traversal.',
  async run(context) {
    const instance = process.env.WORMHOLES_QA_INSTANCE
    context.expect(Boolean(instance), 'WORMHOLES_QA_INSTANCE must identify the isolated test instance')
    const log = path.join(instance, 'logs', 'latest.log')
    const evidence = { states: [] }
    context.report.rtpRetirement = evidence
    let seeded = false
    let logOffset = 0

    async function state(command = '/whpapitest retirement-status') {
      const line = await context.command(command, /^(?:RETIREMENT STATE |FIXTURE failed)/, 10000)
      context.expect(line.startsWith('RETIREMENT STATE '), 'The retirement fixture returned its state', { line })
      const result = JSON.parse(line.slice('RETIREMENT STATE '.length))
      for (const trial of [result.retired, result.followup]) {
        if (trial) context.expect(trial.error === '', 'Mob teleport and scheduler observation succeeded', trial)
      }
      context.expect(result.failuresNow === result.failuresBefore, 'RTP did not add a false traversal failure', result)
      evidence.states.push(result)
      return result
    }

    async function until(predicate) {
      const deadline = Date.now() + 15000
      let current
      do {
        current = await state()
        if (predicate(current)) return current
        await context.sleep(150)
      } while (Date.now() < deadline)
      context.expect(false, 'RTP retirement reached the expected state within 15 seconds', current)
    }

    function claimsReleased(current) {
      return current.sharedClaims === 0 && current.anonymousClaims === 0 && current.playerClaims === 0
    }

    async function readyRtp() {
      const deadline = Date.now() + 60000
      do {
        const line = await context.command('/whpapitest rtp', /^FIXTURE rtp /, 5000)
        if (line.startsWith('FIXTURE rtp ready=true ')) {
          evidence.destination = line
          return
        }
        await context.sleep(300)
      } while (Date.now() < deadline)
      context.expect(false, 'The real RTP search produced a ready destination within 60 seconds')
    }

    try {
      await context.step('Create a real RTP portal and safe destination', async () => {
        seeded = true
        const line = await context.command('/whpapitest setup', /^FIXTURE (?:ready|failed)/, 30000)
        context.expect(line.startsWith('FIXTURE ready '), 'Portal fixture initialized successfully', { line })
        await readyRtp()
        logOffset = (await fs.readFile(log, 'utf8')).length
      })

      await context.step('Remove the first mob after teleport while arrival work is still pending', async () => {
        const line = await context.command('/whpapitest retire-mob', /^(?:RETIREMENT START |FIXTURE failed)/, 10000)
        context.expect(line.startsWith('RETIREMENT START retire=true '), 'The real RTP runtime accepted the first mob', { line })
        const result = await until(current => current.retired?.removed && current.retired.retirementCallbacks === 1)
        context.expect(result.retired.admission === 'requested',
          'The fixture admitted the first mob through the public RTP runtime', result.retired)
        context.expect(result.retired.teleportObserved && result.retired.arrived,
          'The first mob reached its real teleport destination before removal', result.retired)
        context.expect(result.retired.pendingArrivalAtObservation,
          'Mob removal happened before production arrival bookkeeping completed', result.retired)
        context.expect(!result.retired.valid && !result.retired.sentinelExecuted,
          'Removing the real mob retired its queued entity task', result.retired)
      })

      await context.step('Retired arrival releases traversal claims and in-flight bookkeeping', async () => {
        await until(claimsReleased)
        await context.sleep(500)
        const result = await state('/whpapitest retirement-check')
        context.expect(result.retiredVerified, 'The removed mob has no leftover teleport in-flight entry', result)
      })

      await context.step('Another real mob completes RTP after the retired arrival', async () => {
        const line = await context.command('/whpapitest followup-mob', /^(?:RETIREMENT START |FIXTURE failed)/, 10000)
        context.expect(line.startsWith('RETIREMENT START retire=false '), 'The real RTP runtime accepted a subsequent mob', { line })
        const result = await until(current => current.followup?.arrived && current.followup.valid
          && current.followup.sentinelExecuted && !current.followup.arrivalPendingNow && claimsReleased(current))
        context.expect(result.followup.teleportObserved && result.followup.retirementCallbacks === 0,
          'The subsequent mob arrived and remained active on its entity scheduler', result.followup)
        context.expect(result.followup.admission === 'requested',
          'The fixture admitted the subsequent mob through the public RTP runtime', result.followup)
        const verified = await state('/whpapitest retirement-check')
        context.expect(verified.retiredVerified && verified.followupVerified,
          'Both traversals released all in-flight bookkeeping', verified)
      })

      await context.step('Successful mob retirement does not emit an RTP failure warning', async () => {
        await context.sleep(500)
        const lines = (await fs.readFile(log, 'utf8')).slice(logOffset).split(/\r?\n/)
        const failures = lines.filter(line => /RTP runtime failure|arrival-success-retired|Deferred RTP arrival effects|RTP retirement fixture failed/i.test(line))
        evidence.failureLogLines = failures
        context.expect(failures.length === 0, 'No false RTP warning or fixture error was logged', { failures })
      })
    } finally {
      if (seeded) {
        await context.step('Remove all mobs and portals owned by the fixture', async () => {
          await context.command('/whpapitest cleanup', /^FIXTURE cleaned$/, 10000)
        })
      }
    }
  }
}
