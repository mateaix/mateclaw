---
name: skill-authoring
description: 'Author SKILL.md skills: frontmatter, validator limits, structure.'
version: 1.0.0
tags:
- skills
- authoring
- skill-md
- conventions
- meta
author: ported
---
# Authoring MateClaw Skills

## Overview

A skill is a `SKILL.md` file — YAML frontmatter plus a markdown body of reusable instructions. There are two places a SKILL.md can live, and they have different creation paths:

1. **Builtin (in-repo):** `mateclaw-server/src/main/resources/skills/<name>/SKILL.md` — committed, shipped inside the server JAR. On every startup `BuiltinSkillSeedService` scans `classpath*:skills/*/SKILL.md`, parses each frontmatter, and upserts a row into `mate_skill` keyed by `name`. The SKILL.md is the single source of truth — no SQL seed entry is required.
2. **Custom (runtime):** created by an agent or user through the `skill_manage` tool. Stored as a `mate_skill` row with `skill_type=custom` and exported to the workspace at `~/.mateclaw/skills/<name>/`. Not committed; lives per-installation.

This skill covers both. Note that `skill_manage` does NOT write into the in-repo `skills/` tree — builtin skills are authored by writing the file directly and restarting.

## When to Use

- You're adding a reusable workflow that should ship with MateClaw → builtin.
- You're editing an existing builtin skill under `mateclaw-server/src/main/resources/skills/`.
- An agent finished a complex task and wants to persist the approach → custom, via `skill_manage`.
- You're reviewing a SKILL.md for correct frontmatter and structure.

**Don't use for:** recording a one-off tip discovered while *using* a skill (that belongs in `record_lesson` / a per-skill LESSONS.md) or cross-skill memory notes (`remember`). This skill is about writing the skill document itself.

## Required Frontmatter

The frontmatter is parsed by `SkillFrontmatterParser`: a regex (`^---\s*\n(.*?)\n---\s*\n(.*)$`) splits the fenced block, then SnakeYAML loads it as a mapping. Hard requirements:

- Starts with `---` as the **first bytes** — no leading blank line, no BOM.
- A closing `---` line follows, then the body. The body must be non-empty.
- The block between the fences parses as a YAML mapping.
- `name` is present — it is the upsert key. `BuiltinSkillSeedService` skips any SKILL.md with no `name`.
- `description` is present — a single line.

If the frontmatter regex fails to match, the parser treats the whole file as body with an empty `name`, and a builtin skill is silently skipped at seed time. A loadable skill ALWAYS has well-formed frontmatter.

## Size & Naming Limits

- **Skill content:** ≤ 100,000 chars (`MAX_CONTENT_CHARS`, ~25k tokens) — enforced by `skill_manage` for custom skills. Builtin skills aren't hard-checked but should obey the same ceiling.
- **Name:** must match `^[a-z0-9][a-z0-9._-]{0,63}$` — lowercase letters and digits plus `-` `_` `.`, starting with a letter or digit, ≤ 64 chars. `skill_manage` lowercases the name before validating.
- **Description:** keep it to one line. Peer skills run 40-70 chars — a tight trigger phrase, not a paragraph.
- **Peer skills** in `resources/skills/` sit at 6-15k chars. Aim for that range; past ~20k, split detail into `references/*.md`.

## Peer-Matched Frontmatter

Every shipped skill follows this shape:

```yaml
---
name: my-skill-name
description: 'One line: what it does and when it fires.'
version: 1.0.0
tags:
- short
- descriptive
- tags
author: ported
---
```

Fields `BuiltinSkillSeedService` projects onto the `mate_skill` row:

| Field | Effect | Default if absent |
|---|---|---|
| `name` | upsert key, skill identity | — (required) |
| `description` | shown in skill lists | empty |
| `version` | `mate_skill.version` | `1.0.0` |
| `icon` | emoji, or a `/skill-assets/...` path | `🛠️` |
| `author` | attribution | `MateClaw` |
| `tags` | YAML list or CSV string | skill name |
| `nameZh` / `nameEn` | bilingual display names | none |
| `optional: true` | seeds the skill **disabled** — user opts in from the Skills page | `false` (enabled) |
| `dependencies.tools` | required tool ids → `config_json.requiredTools` | none |
| `platforms` | e.g. `[linux, macos, windows]` | none |

`version` / `author` / `tags` are not validator-enforced, but every peer carries them — omitting makes the skill look half-finished. Use `optional: true` for heavyweight skills (paid CLI dependencies, external OAuth, niche integrations) so they ship dark and the user activates them deliberately.

## Skill Structure

Shipped skills follow roughly:

```
# <Title>

## Overview          — one or two paragraphs: what and why.
## When to Use       — bulleted triggers, plus a "Don't use for:" counter-trigger.
## <Topic sections>  — quick-reference tables, exact commands, concrete recipes
                       (mvn test, paths under mateclaw-server/, etc.).
## Common Pitfalls   — numbered mistakes paired with their fixes.
## Verification Checklist — checkbox list of post-action checks.
```

Not every section is mandatory, but `Overview` + `When to Use` + an actionable body + `Common Pitfalls` is the minimum for the skill to read like a peer.

## Directory Placement

```
mateclaw-server/src/main/resources/skills/<skill-name>/SKILL.md
```

The `skills/` tree is **flat** — no category subdirectories. The seed glob `classpath*:skills/*/SKILL.md` matches exactly one level deep, so a skill nested under a category directory would never be scanned. The directory name SHOULD equal the frontmatter `name`. Supporting files go in `references/` and `scripts/` subdirectories (see below).

## Builtin Workflow (in-repo)

1. **Survey peers:** `ls mateclaw-server/src/main/resources/skills/` and read 2-3 SKILL.md files close to your topic — match tone and structure.
2. **Create** `skills/<name>/SKILL.md` with the file tools.
3. **Validate** that the frontmatter parses — see the checklist below.
4. **Restart the server.** `BuiltinSkillSeedService` seeds the new row only at startup; a running server will not see it. The service also skips re-seeding when no SKILL.md's size/mtime changed, so rebuilding the JAR is what makes a change land.
5. **Commit** the new `skills/<name>/` directory. No SQL seed change is needed — the SKILL.md is the source of truth and obsoletes per-skill `INSERT INTO mate_skill`.

## Custom Workflow (skill_manage)

Agents and users create runtime skills with the `skill_manage` tool — actions `create | edit | patch | delete`:

- `create` — a new skill from full SKILL.md content. Rejects a duplicate name.
- `edit` — a full-content rewrite of a custom skill.
- `patch` — find-and-replace one section (`oldText` → `newText`).
- `delete` — uninstall (logical delete plus workspace archive).

Notes:

- Every write is **security-scanned** (`SkillSecurityService`) before saving — dangerous patterns are rejected with the reason. Builtin SKILL.md files are NOT scanned; they are trusted committed source.
- `edit` / `patch` / `delete` **refuse builtin skills** ("cannot edit builtin skill"). To change a builtin skill, edit the resource file and restart.
- A custom skill is live immediately — the tool re-runs the resolver pipeline — so no restart is needed.

## Supporting Files

Beyond `SKILL.md`, a skill directory may carry:

- `references/*.md` — long-form material the body links to. Use this to keep SKILL.md under ~20k chars.
- `scripts/*` — executable helpers a skill invokes.
- `templates/`, `assets/` — used by some bundled skills (HTML templates, images, etc.).

`SkillFileAccessPolicy` only resolves runtime paths under `references/` and `scripts/`, and rejects `..` traversal or absolute paths — keep runtime-read files in those two directories.

## Common Pitfalls

1. **Leading whitespace before `---`.** The frontmatter regex anchors on `^---`; a blank line or BOM makes the whole file parse as body with an empty `name`, and a builtin skill is silently skipped.
2. **Expecting a running server to see a new builtin skill.** `BuiltinSkillSeedService` seeds only at startup. Restart — or, for a quick iteration, create a custom skill via `skill_manage`, which is live immediately.
3. **Trying to `skill_manage edit` a builtin skill.** It is refused. Builtin skills are committed source — edit the file and restart.
4. **Adding an `INSERT INTO mate_skill` for a new builtin skill.** Unnecessary and discouraged — the SKILL.md is the source of truth and the seed service upserts by `name`.
5. **Generic description.** "Debug things" is weak. A peer description names the *trigger* — "4-phase root cause debugging: understand bugs before fixing." beats "Debug things."
6. **Naming an external project or internal RFC in the skill body.** Describe the function objectively. Shipped content states *what* it does, not where the idea came from — `author: ported` is the neutral attribution for an adapted skill.
7. **Skill content over 100k chars.** `skill_manage` rejects it outright; split detail into `references/`.
8. **Mismatched directory and `name`.** The upsert keys on the frontmatter `name`, but a directory that disagrees confuses everyone reading the tree. Keep them equal.

## Verification Checklist

- [ ] File at `mateclaw-server/src/main/resources/skills/<name>/SKILL.md` (builtin); the directory name equals the frontmatter `name`
- [ ] Frontmatter starts at byte 0 with `---`, closes with a `---` line, and the body is non-empty
- [ ] `name` matches `^[a-z0-9][a-z0-9._-]{0,63}$`; `description` is a single line
- [ ] `version`, `tags`, `author` present (peer-matched shape)
- [ ] Total file ≤ 100,000 chars (aim 6-15k; split into `references/` past ~20k)
- [ ] Structure: `# Title` → `## Overview` → `## When to Use` → actionable body → `## Common Pitfalls` → `## Verification Checklist`
- [ ] No external project names or RFC numbers in the body
- [ ] Builtin: server restarted so `BuiltinSkillSeedService` seeds the row; the new `skills/<name>/` directory is committed
- [ ] Custom: created via `skill_manage`, security scan reported PASSED
