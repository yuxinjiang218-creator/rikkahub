---
name: "skill-creator"
description: "Create or update reusable local skills. Use this when the user wants the assistant to package repeatable instructions, scripts, references, or assets into a reusable skill directory."
---

# Skill Creator

Create skills as directory packages under `/skills/<directory>/`.

## Required structure

Every skill must include:

- `SKILL.md`
- YAML frontmatter with `name` and `description`

Optional directories:

- `scripts/`
- `references/`
- `assets/`
- `agents/openai.yaml`

## Workflow

1. Identify the reusable task and choose a stable directory name.
2. Write a concise `description` that explains when the skill should be used.
3. Keep `SKILL.md` focused on trigger guidance and workflow steps.
4. Move detailed docs into `references/` when they are not always needed.
5. Put deterministic code into `scripts/` when the same logic would otherwise be rewritten repeatedly.
6. Put templates or static resources into `assets/`.

## Authoring rules

- Default to concise instructions. Do not explain basics the model already knows.
- Treat the context window as scarce. Keep `SKILL.md` short and use progressive disclosure.
- `SKILL.md` should tell the model when to read `references/` or run `scripts/`.
- Avoid extra files like `README.md`, `CHANGELOG.md`, or setup notes unless they are directly needed by the model.

## Recommended SKILL.md template

```markdown
---
name: "my-skill"
description: "When to use this skill and what it helps accomplish."
---

# Overview

Explain the goal in a few lines.

## When to use

- Trigger condition 1
- Trigger condition 2

## Workflow

1. Step one
2. Step two
3. Step three

## Additional resources

- Read `references/...` when detailed reference material is needed.
- Run `scripts/...` when deterministic execution is better than rewriting logic.
```

## In this app

- The real writable skill library is `/skills`.
- Enabled skills are mirrored read-only at `/opt/rikkahub/skills`.
- Create or edit reusable skills in `/skills`, not in `/opt/rikkahub/skills`.
