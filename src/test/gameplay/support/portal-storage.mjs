import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'

export async function storedPortals(directory) {
  let entries
  try { entries = await readdir(directory, { withFileTypes: true }) }
  catch (error) { if (error.code === 'ENOENT') return []; throw error }
  const portals = []
  for (const entry of entries) {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) portals.push(...await storedPortals(file))
    else if (entry.name.endsWith('.json')) portals.push({ file, id: entry.name.slice(0, -5), data: JSON.parse(await readFile(file, 'utf8')) })
  }
  return portals
}

