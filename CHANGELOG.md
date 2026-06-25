# Changelog

All notable changes to ArchContext are documented in this file.

## 0.2.0 - 2026-06-25

### Added

- MCP `get_server_info` exposes build version, Git commit, build timestamp, Java version, and workspace root.
- Structured lifecycle support for requirements, acceptance criteria, and structured constraints:
  - `status`
  - `obsoleteReason`
  - `supersededBy`
  - `relatedAdr`
- Incremental MCP tools to deprecate spec items:
  - `deprecate_spec_requirement`
  - `deprecate_spec_acceptance_criterion`
  - `deprecate_spec_constraint`
- Incremental ADR lifecycle tool:
  - `update_adr_status`
- Structured change logs for specs and ADRs:
  - `append_spec_change`
  - `append_adr_change`
- Semantic superseding and relation tools:
  - `supersede_spec`
  - `supersede_adr`
  - `upsert_spec_related_adr`
  - `deprecate_spec_related_adr`
  - `upsert_spec_related_spec`
  - `deprecate_spec_related_spec`
- Context controls for implementation and briefing tools:
  - `includeSuperseded`
  - `includeChangeLog`
  - `maxHistoricalItems`
- Deterministic consistency validation:
  - `validate_spec_consistency`
- ID suggestion tools:
  - `suggest_next_requirement_id`
  - `suggest_next_acceptance_criterion_id`
  - `suggest_next_constraint_id`
- Repository and solution enrichment tools for agent-ready architecture context:
  - solution principles and glossary upserts
  - repository components and responsibilities
  - guidelines CRUD and filtering
  - repository-scoped implementation context
  - agent briefing payloads

### Changed

- Implementation-oriented context tools now filter non-implementable requirements, acceptance criteria, and constraints by default.
- Implementation-oriented context tools include only recent changelog entries by default to control token usage.
- `get_solution_context` returns compact spec summaries for active specs instead of full spec payloads.
- Tool responses avoid duplicating large payloads into structured content.
- Spec write tools persist YAML first and refresh the SQLite index asynchronously.
- MCP server warm-up runs asynchronously to avoid blocking initialization.
- README now documents MCP configuration, version checks, timeout troubleshooting, context controls, and the full tool surface.

### Fixed

- Reduced timeout risk for large workspace contexts and repeated spec write calls.
- Preserved existing YAML source files while updating the SQLite index incrementally.
- Avoided implementation agents receiving superseded items in default briefings.

### Compatibility Notes

- Java 21 or newer is required.
- YAML schema `1.1` remains backward-compatible with earlier workspaces that do not define lifecycle fields, relations, or changelogs.
- The SQLite database remains a generated local index; YAML files are the source of truth.
- MCP clients should be configured with a timeout appropriate for local Java process startup, commonly `30000` ms.

## 0.1.0-SNAPSHOT

- Initial local architecture-context MCP server.
- YAML workspace initialization.
- SQLite import/export.
- Repository, spec, and ADR context resources.
- Basic write tools for specs, ADRs, repositories, guidelines, and solution context.
