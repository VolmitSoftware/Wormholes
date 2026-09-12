import fs from 'node:fs/promises'
import path from 'node:path'

export default {
  name: 'wormholes-hot-reload',
  description: 'Checks command feedback, translated debug toggles, live language edits and the bStats lifecycle.',
  async run(context) {
    const instance = process.env.WORMHOLES_QA_INSTANCE
    context.expect(Boolean(instance), 'WORMHOLES_QA_INSTANCE must identify the isolated test instance')
    const data = path.join(instance, 'plugins', 'Wormholes')
    const config = path.join(data, 'wormholes.toml')
    const english = path.join(data, 'languages', 'en_US.toml')
    const german = path.join(data, 'languages', 'de_DE.toml')
    const metrics = path.join(instance, 'plugins', 'bStats', 'config.yml')
    const log = path.join(instance, 'logs', 'latest.log')
    const originals = new Map()
    const evidence = { locales: [], checks: [] }
    context.report.hotReload = evidence

    function escaped(value) {
      return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    }

    async function save(file, text) {
      if (!originals.has(file)) originals.set(file, await fs.readFile(file, 'utf8').catch(() => null))
      await fs.mkdir(path.dirname(file), { recursive: true })
      await fs.writeFile(file, text, 'utf8')
    }

    async function setting(key, value) {
      const text = await fs.readFile(config, 'utf8')
      const expression = new RegExp(`^${escaped(key)} = .*`, 'm')
      context.expect(expression.test(text), `Configuration contains ${key}`)
      await save(config, text.replace(expression, `${key} = ${JSON.stringify(value)}`))
    }

    async function reply(command, expected, timeout = 15000) {
      const deadline = Date.now() + timeout
      while (Date.now() < deadline) {
        try {
          return await context.command(command, expected, 1200)
        } catch (failure) {
          if (Date.now() >= deadline) throw failure
          await context.sleep(200)
        }
      }
    }

    async function single(command, expected) {
      const lines = []
      const listener = text => lines.push(text)
      context.bot.on('messagestr', listener)
      try {
        await context.command(command, expected, 10000)
        await context.sleep(350)
        context.expect(lines.length === 1, `${command} produces exactly one feedback message`, { lines })
        context.expect(!lines.some(line => /Usage:|Available Commands/i.test(line)), 'No usage or help is appended', { lines })
        evidence.checks.push({ command, lines })
      } finally {
        context.bot.removeListener('messagestr', listener)
      }
    }

    function translated(source, section, key) {
      const body = source.split(`[${section}]`)[1]?.split(/\r?\n\[/)[0]
      const line = body?.match(new RegExp(`^${escaped(key)} = (".*")\\r?$`, 'm'))
      context.expect(Boolean(line), `${section}.${key} has a translated value`)
      return JSON.parse(line[1]).replace(/&[0-9a-fk-or]/gi, '')
    }

    try {
      await context.command('/wh language self en_US', /^Wormholes[:：].*en_US/, 10000)
      await context.step('Version and unknown command use a single branded response', async () => {
        await single('/wh version', /Wormholes.*2\.0\.5/)
        await single('/wh testing', /^Wormholes > Unknown command, please use \/wormholes for help\.$/)
        await single('/wh debug testing', /^Wormholes > Unknown command/)
        await single('/wh reload', /^Wormholes > Unknown command/)
        await single('/wh wand rune=banana', /boolean|true|false|invalid/i)
        await single('/wh stats testing=true', /Unknown parameter/i)
      })

      await context.step('Debug toggle confirms both states in all shipped translations', async () => {
        const locales = (await fs.readdir(path.join(data, 'languages')))
          .filter(file => file.endsWith('.toml') && file !== 'en_US.toml').sort()
        context.expect(locales.length === 17, 'All 17 translated locales are installed')
        for (const file of locales) {
          const locale = file.slice(0, -5)
          const source = await fs.readFile(path.join(data, 'languages', file), 'utf8')
          await context.command(`/wh language self ${locale}`, new RegExp(`^Wormholes[:：].*${escaped(locale)}`), 10000)
          await single('/wh debug toggle', new RegExp(escaped(translated(source, 'command.debug', 'enabled'))))
          await single('/wh debug toggle', new RegExp(escaped(translated(source, 'command.debug', 'disabled'))))
          evidence.locales.push(locale)
        }
        await context.command('/wh language self en_US', /^Wormholes[:：].*en_US/, 10000)
      })

      await context.step('Debug dump includes the shared report menu and copy action', async () => {
        const components = []
        const listener = message => components.push(JSON.stringify(message.json ?? message))
        context.bot.on('message', listener)
        try {
          await context.command('/wh debug dump upload=false', /Saved Wormholes debug dump to/, 15000)
          await context.sleep(500)
          context.expect(components.some(message => message.includes('Copy local path')), 'Debug dump has a copy-path menu action')
          context.expect(components.some(message => message.includes('copy_to_clipboard')), 'Copy-path action is clickable in protocol components')
          evidence.debugDumpComponents = components
        } finally {
          context.bot.removeListener('message', listener)
        }
      })

      await context.step('Personal language caches refresh after direct, same-metadata and atomic edits', async () => {
        await context.command('/wh language self de_DE', /^Wormholes[:：].*de_DE/, 10000)
        await save(german, '[command]\nunknown = "Live German one."\n')
        await reply('/wh testing', /Live German one\./)
        const before = await fs.stat(german)
        await save(german, '[command]\nunknown = "Live German two."\n')
        await fs.utimes(german, before.atime, before.mtime)
        await reply('/wh testing', /Live German two\./)
        const replacement = `${german}.new`
        await fs.writeFile(replacement, '[command]\nunknown = "Atomic German edit."\n')
        await fs.rename(replacement, german)
        await reply('/wh testing', /Atomic German edit\./)
      })

      await context.step('Missing, malformed and blank translations fall back to editable and built-in English', async () => {
        await save(english, '[command]\nunknown = "English fallback reached."\n')
        await save(german, '[command]\nunknown = [\n')
        await reply('/wh testing', /English fallback reached\./)
        await save(german, '[command]\nunknown = ""\n')
        await reply('/wh testing', /English fallback reached\./)
        await fs.unlink(german)
        await reply('/wh testing', /English fallback reached\./)
        await save(english, '[command\n')
        await reply('/wh testing', /^Wormholes > Unknown command, please use \/wormholes for help\.$/)
        await save(english, originals.get(english))
        await context.command('/wh language self reset', /Wormholes/, 10000)
      })

      await context.step('Invalid language setting uses English while other settings continue applying', async () => {
        await setting('language', '../invalid')
        await setting('metrics', false)
        await reply('/whhotloadtest', /configured=false runtime=false/)
        await single('/wh testing', /^Wormholes > Unknown command/)
        await setting('language', 'en_US')
      })

      await context.step('Metrics starts, stops and follows the shared bStats configuration', async () => {
        await save(metrics, 'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000001\n')
        await setting('metrics', true)
        await reply('/whhotloadtest', /configured=true runtime=true enabled=false/)
        await setting('metrics', false)
        await reply('/whhotloadtest', /configured=false runtime=false/)
        await setting('metrics', true)
        await reply('/whhotloadtest', /configured=true runtime=true enabled=false/)
        await save(metrics, 'enabled: true\nserverUuid: 00000000-0000-0000-0000-000000000001\n')
        await reply('/whhotloadtest', /configured=true runtime=true enabled=true stopped=false/)
      })

      await context.step('Malformed bStats config stays intact and does not block language reload', async () => {
        const malformed = 'enabled: [\n'
        await save(metrics, malformed)
        await save(english, '[command]\nunknown = "Language remains live."\n')
        await reply('/wh testing', /Language remains live\./)
        await context.sleep(1500)
        context.expect(await fs.readFile(metrics, 'utf8') === malformed, 'Malformed bStats bytes are retained')
        await save(metrics, 'enabled: false\nserverUuid: 00000000-0000-0000-0000-000000000001\n')
        await reply('/whhotloadtest', /configured=true runtime=true enabled=false/)
        const contents = await fs.readFile(log, 'utf8')
        context.expect(contents.includes('Language files hot-reloaded.'), 'Server log confirms language application')
        context.expect(contents.includes('bStats configuration hot-reloaded.'), 'Server log confirms bStats application')
        evidence.serverLog = log
      })
    } finally {
      for (const [file, content] of originals) {
        if (content === null) await fs.rm(file, { force: true })
        else await fs.writeFile(file, content, 'utf8')
      }
    }
  }
}
