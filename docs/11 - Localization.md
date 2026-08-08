# Localization

Canonical English is the typed Java catalog in `WormholesMessages` (and related Director keys). Wormholes does **not** ship an `en_US.toml` bundle. Non-English locales load from jar resources and optional data-folder overlays; missing keys fall through to code-owned English.

## Config

```toml
# plugins/Wormholes/config/wormholes.toml
[main]
language = "en_US"
language-fallbacks = ""
```

| Key | Default | Rules |
|-----|---------|--------|
| `language` | `en_US` | Locale id matching `[A-Za-z0-9][A-Za-z0-9_-]*` |
| `language-fallbacks` | `""` | Comma-separated locales tried after the primary, in order |

English is always the final fallback when a key is absent from overlays. Setting `language = "en_US"` uses the catalog only; `en_US` data-folder and bundled overlays are skipped.

Invalid locale strings throw on load. A missing bundled locale with no data-folder file fails the language load for that name.

## Resolution order

For each requested locale (primary, then each fallback), overlays are applied in this order before the catalog:

1. `plugins/Wormholes/languages/<locale>.toml` if the file exists (operator override; may be partial)
2. Bundled `/languages/<locale>.toml` from the jar if present
3. Next fallback locale (same two steps)
4. Code-owned English catalog (`WormholesMessages`)

`en_US` in the request list is skipped for overlay loading and uses the catalog.

## Bundled locales

Seventeen non-English complete bundles ship under `src/main/resources/languages/` (jar `/languages/`):

| Locale id | File |
|-----------|------|
| `de_DE` | `de_DE.toml` |
| `es_ES` | `es_ES.toml` |
| `fi_FI` | `fi_FI.toml` |
| `fr_FR` | `fr_FR.toml` |
| `he_IL` | `he_IL.toml` |
| `it_IT` | `it_IT.toml` |
| `ja-JP` | `ja-JP.toml` |
| `ko_KR` | `ko_KR.toml` |
| `lt_LT` | `lt_LT.toml` |
| `nl_NL` | `nl_NL.toml` |
| `pl_PL` | `pl_PL.toml` |
| `pt_PT` | `pt_PT.toml` |
| `ru_RU` | `ru_RU.toml` |
| `tr_TR` | `tr_TR.toml` |
| `vi_VI` | `vi_VI.toml` |
| `zh_CN` | `zh_CN.toml` |
| `zh_TW` | `zh_TW.toml` |

There is no `en_US.toml` in the jar. The bundled set matches `VolmitLocales.nonEnglish()`.

### Japanese filename quirk

`ja-JP` uses a **hyphen**, not `ja_JP`. Config `language` and the override filename must match exactly (`ja-JP.toml`). All other bundled ids use an underscore between language and region.

## Operator overrides

Path: `plugins/Wormholes/languages/<locale>.toml`

- Sparse: include only keys you change.
- Filename must equal the configured locale string + `.toml`.
- Must stay inside the languages directory (path traversal rejected).

### File schema (`WormholesLocaleLoader.SCHEMA = 1`)

```toml
schema = 1
locale = "de_DE"

[text]
"command.error.no_permission" = "<red>…"

[lines]
"command.public_help" = ["line one", "line two"]

[plural."command.admin.deleted_portals"]
one = "…"
other = "…"
```

| Root key | Required | Content |
|----------|----------|---------|
| `schema` | yes | Must be `1` |
| `locale` | yes | Must equal the requested locale (case-insensitive check) |
| `text` | optional | String message templates (MiniMessage-style as used in catalog) |
| `lines` | optional | Arrays of strings for multi-line messages |
| `plural` | optional | Nested tables of plural category → template |

Unknown root keys fail validation. Values must match the expected types (string / string array / plural form table).

Validation rejects unknown message keys, text/lines/plural shape mismatches, wrong line counts, per-line placeholder mismatches, and plural-category mismatches. Missing keys warn and fall through to the next locale or English catalog.

## Reload

| Trigger | Behavior |
|---------|----------|
| `/wormholes reload` | Reloads config and language (`wormholes.admin.reload` + root gate; see `09 - Commands & Permissions.md`) |
| `config/wormholes.toml` hotload | Reloads the selected language after the config load succeeds |
| Direct `languages/*.toml` edit | Not watched; use `/wormholes reload` or touch the config file |
| Language rejected | Last valid language remains; config may still apply; console reports the cause |

## Related docs

- `01 - Installation & Configuration.md` — `language` / `language-fallbacks` defaults
- `09 - Commands & Permissions.md` — reload command
