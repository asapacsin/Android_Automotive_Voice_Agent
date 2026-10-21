# Source

Installed 2026-10-19 on the owner's instruction ("install the community top option for
architecture selection skill and every time you are going to do this use that skill").

- Upstream: https://github.com/jeffallan/claude-skills, `skills/architecture-designer/` (v1.1.1), MIT
  (LICENSE beside this file). Copied verbatim from the `main` branch raw files; not modified.
- Why this one: the most-installed architecture-selection skill found with a measured count
  (5,495 installs on vibeindex.ai). Alternatives seen: hungv47/meta-skills `architect-system`,
  claudskills.com `solution-architect` / `architect` (no install counts); community ADR-only skills
  (record decisions, do not pick). Anthropic's own anthropics/skills has no architecture skill.
- Reviewed before install: Markdown only, no scripts, no network calls, no instructions beyond
  architecture method and templates.

How it binds in this repository: AGENTS.md hard rules ("Architecture selection").
