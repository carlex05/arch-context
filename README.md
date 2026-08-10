# ArchContext

ArchContext is a lightweight local MCP server for architecture context. It gives LLM coding agents structured access to solution descriptions, repository metadata, specs, ADRs, and engineering guidelines without requiring a remote service or heavyweight framework.

## Why ArchContext exists

Modern software work often spans multiple repositories. Agents need more than the current file: they need bounded contexts, active specs, accepted decisions, constraints, and repository-specific guidelines. ArchContext supports Spec-Driven Development by making that context explicit, versionable, and queryable.

## Main concepts

- **Solution**: the product/platform/workspace being described.
- **Repository**: one source repository in a multi-repository solution, including its architectural role, language, type, and bounded context.
- **Spec**: a structured feature/change specification with requirements, acceptance criteria, constraints, affected repositories, and related ADRs.
- **ADR**: an architecture decision record with context, decision, consequences, and impact.
- **Guideline**: rules that apply by language and repository type.
- **YAML source of truth**: `.archcontext/*.yaml` files are human-editable and versionable.
- **SQLite local index**: `.archcontext/archcontext.db` is a local cache/search index rebuilt by `archcontext import`.
- **MCP stdio server**: `archcontext mcp` exposes resources, tools, and prompts through the official Java MCP SDK stdio transport. stdout is reserved for MCP protocol messages in MCP mode.

ArchContext explicitly supports one solution/workspace composed of multiple source code repositories.

Current release notes are maintained in [CHANGELOG.md](CHANGELOG.md).

## 5-minute quickstart

Requirements:

- JDK 21 or newer.
- Maven 3.9 or newer.

ArchContext currently compiles with `maven.compiler.release=21`. The code does not require Java 25 APIs, and Java 21 keeps the MVP easier to build across local machines and CI while still supporting the official Java MCP SDK.

Build the executable JAR:

```bash
mvn -q clean package -Dgit.commit=$(git rev-parse --short HEAD)
```

The `git.commit` property is optional, but recommended. It is embedded in the
JAR and exposed through the CLI, MCP `serverInfo`, and the `get_server_info`
tool so agents can prove which build they are using.

Import the sample architecture context:

```bash
java -jar target/archcontext.jar import --root examples/sample-workspace
```

Validate the sample workspace:

```bash
java -jar target/archcontext.jar doctor --root examples/sample-workspace
```

The sample repository paths are illustrative. `doctor` may warn that `booking-api`, `payment-service`, or `traveler-web` paths are missing unless those repositories exist locally.

Configure your MCP client using absolute paths:

```json
{
  "mcpServers": {
    "archcontext": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/target/archcontext.jar",
        "mcp",
        "--root",
        "/absolute/path/to/examples/sample-workspace"
      ]
    }
  }
}
```

For clients with aggressive startup/tool timeouts, set them explicitly. A Java
stdio MCP server can take close to one second to start even when healthy.

Paste-ready agent prompts:

- "Use ArchContext to get the solution context."
- "Use ArchContext to list the repositories in this solution."
- "Use ArchContext to get the implementation context for SPEC-001."
- "Use ArchContext to explain which ADRs and guidelines apply before implementing SPEC-001."
- "Use ArchContext to get repository context for booking-api."

You can also run the local smoke test:

```bash
scripts/smoke-test-mcp.sh
```

The smoke test validates packaging, sample import, `doctor`, and a minimal stdio initialize handshake. It does not replace testing with real clients such as Claude Desktop, Cursor, Claude Code, or another MCP-compatible agent.

## Installation from source

Requires Maven and JDK 21 or newer.

```bash
mvn package -Dgit.commit=$(git rev-parse --short HEAD)
java -jar target/archcontext.jar --help
java -jar target/archcontext.jar --version
```

## Easier local installation

The recommended distribution path for `0.2.0` is a GitHub Release JAR plus the
local installer script. The installer copies the JAR to
`~/.local/share/archcontext/archcontext.jar` and creates an `archcontext`
wrapper in `~/.local/bin`.

Install from a local build:

```bash
mvn -q package -Dgit.commit=$(git rev-parse --short HEAD)
scripts/install.sh --jar target/archcontext.jar
archcontext --version
```

Install from a release URL:

```bash
scripts/install.sh --url https://github.com/<owner>/<repo>/releases/download/v0.2.0/archcontext-0.2.0.jar
archcontext --version
```

After installation, MCP clients can use the wrapper instead of an absolute JAR
path:

```json
{
  "mcpServers": {
    "archcontext": {
      "command": "archcontext",
      "args": [
        "mcp",
        "--root",
        "/absolute/path/to/workspace"
      ]
    }
  }
}
```

For opencode:

```json
{
  "mcp": {
    "arch-context-front9": {
      "type": "local",
      "command": [
        "archcontext",
        "mcp",
        "--root",
        "/absolute/path/to/workspace"
      ],
      "timeout": 30000,
      "enabled": true
    }
  },
  "experimental": {
    "mcp_timeout": 30000
  }
}
```

## Initialize a new ArchContext workspace

After installing the `archcontext` wrapper, initialize a new folder with its own
versioned architecture context:

```bash
mkdir my-workspace
cd my-workspace
git init
archcontext init --root .
```

This creates:

```text
.archcontext/
  .gitignore
  local.yaml
  repositories.yaml
  solution.yaml
```

Edit or create the shared context files:

```text
.archcontext/solution.yaml
.archcontext/repositories.yaml
.archcontext/specs/*.yaml
.archcontext/adrs/*.yaml
.archcontext/guidelines/*.yaml
```

Refresh the local SQLite index and validate the workspace:

```bash
archcontext import --root .
archcontext doctor --root .
```

Commit only the shared, versionable context:

```bash
git add .archcontext/.gitignore \
  .archcontext/solution.yaml \
  .archcontext/repositories.yaml \
  .archcontext/specs \
  .archcontext/adrs \
  .archcontext/guidelines

git commit -m "Initialize ArchContext workspace"
```

Do not commit `.archcontext/local.yaml` or `.archcontext/archcontext.db`; they
are developer-local or generated files and are ignored by `.archcontext/.gitignore`.

## Release publishing checklist

1. Build and test:

   ```bash
   mvn -q test
   mvn -q package -DskipTests -Dgit.commit=$(git rev-parse --short HEAD)
   java -jar target/archcontext.jar --version
   ```

2. Create the release artifact:

   ```bash
   cp target/archcontext.jar target/archcontext-0.2.0.jar
   sha256sum target/archcontext-0.2.0.jar > target/archcontext-0.2.0.jar.sha256
   ```

3. Push the release commit and tag:

   ```bash
   git push origin main
   git push origin v0.2.0
   ```

4. Create a GitHub Release for `v0.2.0` and upload:

   - `target/archcontext-0.2.0.jar`
   - `target/archcontext-0.2.0.jar.sha256`

5. Smoke test the downloaded artifact with `scripts/install.sh --url <release-jar-url>`.

## CLI usage

```bash
java -jar target/archcontext.jar init
java -jar target/archcontext.jar repo add ../booking-api --id booking-api --name "Booking API" --type backend --language java --bounded-context booking
java -jar target/archcontext.jar repo list
java -jar target/archcontext.jar import
java -jar target/archcontext.jar doctor
java -jar target/archcontext.jar mcp --root /path/to/workspace
```

`repositories.yaml` is shared and versionable. `local.yaml` is developer-specific and ignored by `.archcontext/.gitignore`; it can override repository paths for each developer.

## Shared vs local files

Shared and versioned:

- `.archcontext/solution.yaml`
- `.archcontext/repositories.yaml`
- `.archcontext/specs/*.yaml`
- `.archcontext/adrs/*.yaml`
- `.archcontext/guidelines/*.yaml`

Local or generated, not committed:

- `.archcontext/local.yaml`
- `.archcontext/archcontext.db`

Example `.archcontext/local.yaml`:

```yaml
schemaVersion: "1.0"
localRepositories:
  booking-api:
    path: /Users/me/work/booking-api
  payment-service:
    path: /Users/me/work/payment-service
  booking-web:
    path: /Users/me/work/booking-web
```

Run `archcontext import` after editing shared YAML or local overrides.

## Architect and developer lifecycle

1. Architect updates `repositories.yaml`, specs, ADRs, and guidelines.
2. Architect commits shared `.archcontext` files.
3. Developer pulls the changes.
4. Developer creates or updates `local.yaml` with their local repository paths.
5. Developer runs `archcontext import`.
6. Developer connects their MCP-compatible agent.
7. Agent queries ArchContext tools for solution, repository, and spec context.

## Relationships

- Specs relate to repositories through `affectedRepositories`.
- ADRs relate to repositories through `affectedRepositories`.
- Specs and ADRs relate through `relatedAdrs` and `relatedSpecs`.
- Guidelines apply through `appliesTo.languages` and `appliesTo.repositoryTypes`.

## MCP client configuration example

```json
{
  "mcpServers": {
    "archcontext": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/archcontext.jar",
        "mcp",
        "--root",
        "/path/to/workspace"
      ]
    }
  }
}
```

Run `archcontext import` in that workspace before starting the MCP server so
`.archcontext/archcontext.db` exists. Most context tools read YAML directly, but
`search_context` uses the SQLite index.

Example opencode configuration:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "arch-context-front9": {
      "type": "local",
      "command": [
        "java",
        "-jar",
        "/home/csze/sources/arch-context/target/archcontext.jar",
        "mcp",
        "--root",
        "/home/csze/sources/arch-context-front9"
      ],
      "timeout": 30000,
      "enabled": true
    }
  },
  "experimental": {
    "mcp_timeout": 30000
  }
}
```

## MCP compatibility

ArchContext uses the official Java MCP SDK. The currently integrated SDK version may negotiate an older protocol version than the latest published MCP specification. Verify compatibility with the MCP client you plan to use.

## How agents should use ArchContext

- Start with `get_server_info` when diagnosing client/server mismatches. It
  returns the JAR version, build timestamp, and embedded Git commit.
- Start with `get_solution_context` to understand the solution, repositories, active specs, and accepted ADRs.
- Use `get_repository_context` when working inside one repository.
- Use `get_implementation_context_for_spec` before implementing a spec.
- Use `resolve_repository_by_path` when an agent is running inside a local checkout and needs its ArchContext repository id.
- Use `get_repository_implementation_context_for_spec` for repo-scoped implementation work; it returns the local repositoryChange, applicable requirements, acceptance criteria, contracts, constraints, ADRs, guidelines, and other affected repositories.
- Use `get_agent_briefing_for_spec` when an implementation agent needs one consolidated payload for one spec and one repository.
- Use `search_context` for targeted architecture lookup.
- Prefer tools over broad resources for implementation workflows.

## Controlled write tools

ArchContext can also update known YAML context files through structured MCP tools. YAML remains the source of truth, and `.archcontext/archcontext.db` remains a generated local index. Full-workspace writes rebuild the index synchronously. Incremental spec writes persist YAML first and refresh the spec index asynchronously so MCP write tools do not block on SQLite.

Current write tools:

- `upsert_solution`: create or update solution identity, vision, principles, cross-cutting concerns, and glossary.
- `upsert_solution_principle`: add or update one solution principle.
- `upsert_solution_glossary_term`: add or update one glossary term.
- `upsert_repository`: create or update one repository in `.archcontext/repositories.yaml`.
- `upsert_repository_component`: add or update one component inside a repository definition.
- `upsert_repository_responsibility`: add or update one repository responsibility.
- `create_spec`: create one new spec under `.archcontext/specs/*.yaml`.
- `create_guideline`: create one new guideline under `.archcontext/guidelines/*.yaml`.
- `upsert_guideline`: create or update one guideline under `.archcontext/guidelines/*.yaml`.
- `upsert_spec_requirement`: add or update one functional or non-functional requirement in an existing spec.
- `deprecate_spec_requirement`: mark one requirement as `obsolete`, `superseded`, or `rejected` without deleting the historical record.
- `upsert_spec_acceptance_criterion`: add or update one acceptance criterion in an existing spec.
- `deprecate_spec_acceptance_criterion`: mark one acceptance criterion as `obsolete`, `superseded`, or `rejected` without deleting the historical record.
- `upsert_spec_constraint`: add or update one structured constraint in an existing spec without removing legacy constraints.
- `deprecate_spec_constraint`: mark one structured constraint as `obsolete`, `superseded`, or `rejected` without deleting the historical record.
- `upsert_spec_repository_change`: add or update one repository-scoped implementation plan in an existing spec.
- `upsert_spec_affected_component`: add or update one affected component or file breadcrumb in an existing spec.
- `update_spec_status`: change one existing spec status without rewriting the full spec.
- `upsert_spec_metadata`: add or update planning metadata such as priority, effort, sprint, phase, and tags.
- `upsert_spec_summary`: update existing spec summary fields such as title, owner, problem, or businessGoal.
- `append_spec_change`: append or update one structured change-log entry in an existing spec.
- `supersede_spec`: mark one existing spec as superseded by another existing spec and link both.
- `upsert_spec_related_adr` / `deprecate_spec_related_adr`: manage structured spec-to-ADR relations.
- `upsert_spec_related_spec` / `deprecate_spec_related_spec`: manage structured spec-to-spec relations.
- `add_spec_out_of_scope_item`: add an out-of-scope item while avoiding duplicate descriptions.
- `create_adr`: create one new ADR under `.archcontext/adrs/*.yaml`.
- `upsert_adr`: create or update one ADR under `.archcontext/adrs/*.yaml`.
- `upsert_adr_consequence`: add one consequence to an existing ADR.
- `update_adr_status`: change one ADR status, optionally recording `supersededBy` and a status note.
- `append_adr_change`: append or update one structured change-log entry in an existing ADR.
- `supersede_adr`: mark one existing ADR as superseded by another existing ADR and link both.
- `validate_spec_consistency`: validate deterministic consistency rules for superseding, relations, and repositoryChanges.
- `suggest_next_requirement_id`, `suggest_next_acceptance_criterion_id`, `suggest_next_constraint_id`: suggest the next conventional id in a spec.
- `validate_spec_repository_coverage`: validate repositoryChanges coverage for one spec.
- `validate_workspace`: validate repository references, component references, active spec readiness, related ADRs, and schema versions without writing files.

For Spec-Driven Development, acceptance criteria, constraints, repositoryChanges, contracts, and out-of-scope items are central. They make the implementation boundary explicit for both humans and agents: what must be true, what architectural rules must be respected, which repository owns which part, what contracts connect repositories, and what must not be implemented in the current change.

Requirements, acceptance criteria, and structured constraints marked as `obsolete`, `superseded`, or `rejected` remain in the spec YAML for traceability, but implementation-oriented context tools return only implementable items. Use `get_spec_context` when an agent needs the full historical record.

Specs and ADRs can also carry a structured `changeLog`. Implementation-oriented context tools include only the most recent entries by default so agents see why the context changed without receiving the full audit trail on every call.

Superseding tools link resources that already exist. Create the replacement spec or ADR first, then call `supersede_spec` or `supersede_adr` to update both sides and append traceability entries.

Implementation context tools keep payloads compact by default. Use `includeSuperseded: true` to include obsolete/superseded/rejected items, `includeChangeLog: "none" | "summary" | "full"` to control changelog depth, and `maxHistoricalItems` to adjust summary length.

Write tools are intentionally constrained:

- They do not expose arbitrary file writes.
- They do not accept raw YAML patches.
- They do not execute shell commands.
- They only write known files under `.archcontext`.
- They support `dryRun: true` to validate and preview changes without writing files.
- Validation errors are returned as MCP tool errors with structured details.

Dry-run example:

```json
{
  "name": "upsert_solution_principle",
  "arguments": {
    "id": "tdd-red-green-refactor",
    "title": "TDD red-green-refactor",
    "description": "Behavior changes should be covered by focused tests before implementation is considered complete.",
    "rationale": "Agents need executable feedback and a clear regression boundary.",
    "appliesTo": [
      "*"
    ],
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_repository_component",
  "arguments": {
    "repositoryId": "booking-api",
    "componentId": "application-use-case",
    "name": "Application Use Cases",
    "type": "layer",
    "path": "src/main/java/dev/booking/application",
    "description": "Coordinates booking use cases without framework dependencies.",
    "responsibilities": [
      "RESP-001"
    ],
    "dependsOn": [
      "booking-domain"
    ],
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_guideline",
  "arguments": {
    "id": "guideline-testing-be",
    "title": "Backend testing guideline",
    "category": "testing",
    "appliesTo": {
      "repositoryIds": [
        "booking-api"
      ],
      "languages": [
        "java"
      ],
      "repositoryTypes": [
        "backend"
      ]
    },
    "rules": [
      {
        "id": "TEST-001",
        "statement": "Write focused JUnit 5 tests for behavior changes.",
        "rationale": "Implementation agents need executable guardrails.",
        "examples": {
          "good": [
            "Use AssertJ assertions over observable behavior."
          ],
          "bad": [
            "Only assert that mocks were called."
          ]
        }
      }
    ],
    "dryRun": true
  }
}
```

```json
{
  "name": "create_spec",
  "arguments": {
    "id": "SPEC-002",
    "title": "Booking cancellation audit trail",
    "status": "draft",
    "owner": "booking-platform-team",
    "problem": "Support needs a reliable audit trail for customer cancellation actions.",
    "businessGoal": "Improve operational traceability and reduce manual investigation time.",
    "affectedRepositories": [
      "booking-api",
      "booking-web"
    ],
    "functionalRequirements": [
      {
        "id": "FR-001",
        "description": "Persist a cancellation audit event whenever a customer cancels booking items."
      },
      {
        "id": "FR-002",
        "description": "Show the cancellation audit status in the booking UI."
      }
    ],
    "acceptanceCriteria": [
      {
        "id": "AC-001",
        "description": "Every cancellation action is recorded with actor, timestamp, booking id, and affected item ids."
      },
      {
        "id": "AC-002",
        "description": "The UI confirms that the cancellation audit entry was recorded."
      }
    ],
    "repositoryChanges": [
      {
        "repositoryId": "booking-api",
        "role": "backend",
        "summary": "Record cancellation audit entries and expose audit status to the UI.",
        "requirements": [
          "FR-001"
        ],
        "acceptanceCriteria": [
          "AC-001"
        ],
        "contractsProvided": [
          "REST GET /bookings/{id}/cancellation-audit"
        ],
        "contractsConsumed": [],
        "outOfScope": [
          "Do not change refund calculation rules."
        ]
      },
      {
        "repositoryId": "booking-web",
        "role": "frontend",
        "summary": "Display cancellation audit status in the booking details screen.",
        "requirements": [
          "FR-002"
        ],
        "acceptanceCriteria": [
          "AC-002"
        ],
        "contractsProvided": [],
        "contractsConsumed": [
          "REST GET /bookings/{id}/cancellation-audit"
        ],
        "outOfScope": []
      }
    ],
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_spec_repository_change",
  "arguments": {
    "specId": "SPEC-002",
    "repositoryId": "booking-api",
    "role": "backend",
    "summary": "Record cancellation audit entries and expose audit status to the UI.",
    "requirements": [
      "FR-001"
    ],
    "acceptanceCriteria": [
      "AC-001"
    ],
    "contractsProvided": [
      "REST GET /bookings/{id}/cancellation-audit"
    ],
    "contractsConsumed": [],
    "outOfScope": [
      "Do not change refund calculation rules."
    ],
    "dryRun": true
  }
}
```

Spec enrichment examples:

```json
{
  "name": "upsert_spec_acceptance_criterion",
  "arguments": {
    "specId": "SPEC-002",
    "id": "AC-001",
    "description": "Every cancellation action is recorded with actor, timestamp, booking id, and affected item ids.",
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_spec_constraint",
  "arguments": {
    "specId": "SPEC-002",
    "id": "CON-001",
    "title": "Payment ownership",
    "description": "Booking services must not read or write payment-service tables directly."
  }
}
```

```json
{
  "name": "add_spec_out_of_scope_item",
  "arguments": {
    "specId": "SPEC-002",
    "description": "Changing refund calculation rules is out of scope for this spec."
  }
}
```

```json
{
  "name": "upsert_spec_affected_component",
  "arguments": {
    "specId": "SPEC-002",
    "repositoryId": "booking-api",
    "componentId": "application-use-case",
    "path": "src/main/java/dev/booking/application/CancelBookingUseCase.java",
    "lineStart": 50,
    "lineEnd": 65,
    "role": "modify",
    "note": "Add cancellation audit orchestration.",
    "dryRun": true
  }
}
```

```json
{
  "name": "create_adr",
  "arguments": {
    "id": "ADR-002",
    "title": "Expose cancellation audit status through Booking API",
    "status": "proposed",
    "date": "2026-05-28",
    "context": "The booking frontend needs cancellation audit status without coupling to persistence details.",
    "decision": "Booking API will provide the cancellation audit status contract consumed by booking-web.",
    "consequences": [
      "booking-web depends on the Booking API audit status contract.",
      "booking-api remains the owner of cancellation audit persistence."
    ],
    "affectedRepositories": [
      "booking-api",
      "booking-web"
    ],
    "relatedSpecs": [
      "SPEC-002"
    ],
    "dryRun": true
  }
}
```

```json
{
  "name": "deprecate_spec_requirement",
  "arguments": {
    "specId": "SPEC-002",
    "requirementType": "functional",
    "requirementId": "FR-003",
    "status": "superseded",
    "reason": "The backend contract now uses FR-005 as the canonical behavior.",
    "supersededBy": "FR-005",
    "relatedAdr": "ADR-004",
    "dryRun": true
  }
}
```

```json
{
  "name": "deprecate_spec_acceptance_criterion",
  "arguments": {
    "specId": "SPEC-002",
    "acceptanceCriterionId": "AC-003",
    "status": "obsolete",
    "reason": "The validation moved to AC-005.",
    "supersededBy": "AC-005",
    "relatedAdr": "ADR-004",
    "dryRun": true
  }
}
```

```json
{
  "name": "deprecate_spec_constraint",
  "arguments": {
    "specId": "SPEC-002",
    "constraintId": "CON-002",
    "status": "superseded",
    "reason": "The architecture decision now requires a different integration boundary.",
    "supersededBy": "CON-004",
    "relatedAdr": "ADR-006",
    "dryRun": true
  }
}
```

```json
{
  "name": "append_spec_change",
  "arguments": {
    "specId": "SPEC-002",
    "id": "CHG-20260625-001",
    "date": "2026-06-25",
    "summary": "Deprecated the old discriminator acceptance criterion.",
    "reason": "ADR-006 removed discriminator-based parsing from the contract.",
    "relatedAdr": "ADR-006",
    "changedBy": "architecture-agent",
    "dryRun": true
  }
}
```

```json
{
  "name": "supersede_spec",
  "arguments": {
    "oldSpecId": "SPEC-002",
    "newSpecId": "SPEC-002-v2",
    "reason": "The workflow was re-scoped after backend contract changes.",
    "relatedAdr": "ADR-006",
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_spec_related_adr",
  "arguments": {
    "specId": "SPEC-002-v2",
    "adrId": "ADR-006",
    "type": "governs",
    "note": "ADR-006 defines the replacement integration boundary.",
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_spec_related_spec",
  "arguments": {
    "specId": "SPEC-002-v2",
    "relatedSpecId": "SPEC-002",
    "type": "supersedes",
    "note": "SPEC-002-v2 replaces SPEC-002.",
    "dryRun": true
  }
}
```

```json
{
  "name": "update_spec_status",
  "arguments": {
    "specId": "SPEC-002",
    "status": "review",
    "note": "Implementation is complete and ready for review.",
    "dryRun": true
  }
}
```

```json
{
  "name": "update_adr_status",
  "arguments": {
    "adrId": "ADR-002",
    "status": "superseded",
    "supersededBy": "ADR-006",
    "note": "The original decision remains historical context but must not guide new implementations.",
    "dryRun": true
  }
}
```

```json
{
  "name": "append_adr_change",
  "arguments": {
    "adrId": "ADR-002",
    "id": "CHG-ADR-20260625-001",
    "date": "2026-06-25",
    "summary": "Recorded replacement by ADR-006.",
    "reason": "The previous integration boundary is no longer valid for new implementations.",
    "relatedAdr": "ADR-006",
    "changedBy": "architecture-agent",
    "dryRun": true
  }
}
```

```json
{
  "name": "supersede_adr",
  "arguments": {
    "oldAdrId": "ADR-002",
    "newAdrId": "ADR-006",
    "reason": "ADR-006 replaces the previous integration boundary.",
    "dryRun": true
  }
}
```

```json
{
  "name": "upsert_adr_consequence",
  "arguments": {
    "adrId": "ADR-002",
    "consequence": "The canonical binding timestamp format is yyyy-MM-dd HH:mm:ss.SSSSSSXXX UTC.",
    "dryRun": true
  }
}
```

```json
{
  "name": "get_agent_briefing_for_spec",
  "arguments": {
    "specId": "SPEC-002-v2",
    "repositoryId": "booking-api",
    "includeSuperseded": true,
    "includeChangeLog": "full",
    "maxHistoricalItems": 10
  }
}
```

```json
{
  "name": "validate_spec_repository_coverage",
  "arguments": {
    "specId": "SPEC-002",
    "strict": true
  }
}
```

```json
{
  "name": "validate_spec_consistency",
  "arguments": {
    "specId": "SPEC-002-v2",
    "strict": true
  }
}
```

```json
{
  "name": "suggest_next_requirement_id",
  "arguments": {
    "specId": "SPEC-002-v2",
    "requirementType": "functional"
  }
}
```

```json
{
  "name": "validate_workspace",
  "arguments": {
    "strict": false
  }
}
```

Example agent workflow:

1. Create a spec with `create_spec` and `dryRun: true`.
2. Create the spec without `dryRun`.
3. Register missing solution principles, glossary, repository components, and guidelines.
4. Add requirements with `upsert_spec_requirement`.
5. Add acceptance criteria with `upsert_spec_acceptance_criterion`.
6. Add constraints with `upsert_spec_constraint`.
7. Add repository-scoped plans with `upsert_spec_repository_change`.
8. Add affected components or file breadcrumbs with `upsert_spec_affected_component`.
9. Add boundaries with `add_spec_out_of_scope_item`.
10. Create or update ADRs with `create_adr` or `upsert_adr` when the spec introduces architecture decisions.
11. Use `deprecate_spec_requirement` when a requirement must remain traceable but must not be implemented.
12. Use `deprecate_spec_acceptance_criterion` when an acceptance criterion is no longer valid.
13. Use `deprecate_spec_constraint` when a structured constraint is no longer applicable.
14. Use `append_spec_change` to record why the spec changed.
15. Use `supersede_spec` after creating a replacement spec.
16. Use structured relation tools when a spec informs, supersedes, or is governed by another spec or ADR.
17. Use `update_spec_status` when implementation reaches `review` or `done`.
18. Use `upsert_adr_consequence` when implementation reveals a new consequence of an ADR.
19. Use `update_adr_status` or `supersede_adr` when an ADR becomes `deprecated` or `superseded`.
20. Use `append_adr_change` to record why an ADR changed.
21. Run `validate_spec_repository_coverage`.
22. Run `validate_spec_consistency`.
23. Run `validate_workspace`.
24. Implementation agents call `get_agent_briefing_for_spec` for their `{ specId, repositoryId }`.
25. Review the Git diff before committing shared `.archcontext` files.

## MCP surface

Resources:

- `archcontext://solution`
- `archcontext://repositories`
- `archcontext://repositories/{repositoryId}`
- `archcontext://specs`
- `archcontext://specs/{specId}`
- `archcontext://adrs`
- `archcontext://adrs/{adrId}`
- `archcontext://guidelines`

Tools:

- `get_server_info`
- `get_solution_context`
- `get_repository_context`
- `search_context`
- `get_spec_context`
- `list_adrs`
- `get_adr_context`
- `list_guidelines`
- `get_guideline`
- `get_implementation_context_for_spec`
- `get_repository_implementation_context_for_spec`
- `resolve_repository_by_path`
- `get_agent_briefing_for_spec`
- `validate_spec_completeness`
- `validate_spec_consistency`
- `suggest_next_requirement_id`
- `suggest_next_acceptance_criterion_id`
- `suggest_next_constraint_id`
- `list_active_specs`
- `upsert_solution`
- `upsert_solution_principle`
- `upsert_solution_glossary_term`
- `upsert_repository`
- `upsert_repository_component`
- `upsert_repository_responsibility`
- `create_spec`
- `create_guideline`
- `upsert_guideline`
- `upsert_spec_requirement`
- `deprecate_spec_requirement`
- `upsert_spec_acceptance_criterion`
- `deprecate_spec_acceptance_criterion`
- `upsert_spec_constraint`
- `deprecate_spec_constraint`
- `upsert_spec_repository_change`
- `upsert_spec_affected_component`
- `update_spec_status`
- `upsert_spec_metadata`
- `upsert_spec_summary`
- `append_spec_change`
- `supersede_spec`
- `upsert_spec_related_adr`
- `deprecate_spec_related_adr`
- `upsert_spec_related_spec`
- `deprecate_spec_related_spec`
- `add_spec_out_of_scope_item`
- `create_adr`
- `upsert_adr`
- `upsert_adr_consequence`
- `update_adr_status`
- `append_adr_change`
- `supersede_adr`
- `validate_spec_repository_coverage`
- `validate_workspace`

Prompts:

- `create_spec`
- `review_spec`
- `plan_implementation_from_spec`
- `validate_implementation_against_spec`
- `suggest_adr`

## Example folder structure

```text
workspace/
  .archcontext/
    solution.yaml
    repositories.yaml
    local.yaml
    archcontext.db
    specs/
      SPEC-001.yaml
    adrs/
      ADR-001.yaml
    guidelines/
      java-backend.yaml
  booking-api/
  payment-service/
```

## Example workflow

1. An architect creates or updates `.archcontext` YAML context.
2. Shared context such as `solution.yaml`, `repositories.yaml`, specs, ADRs, and guidelines is committed to Git.
3. A developer pulls the context.
4. The developer adjusts `.archcontext/local.yaml` if local paths differ.
5. The developer runs `archcontext import` to refresh the local SQLite index.
6. The developer starts `archcontext mcp` from an MCP-compatible coding agent.

## Troubleshooting

Verify the running build

- Run `java -jar target/archcontext.jar --version`.
- From the MCP client, call `get_server_info`.
- The CLI version, MCP `serverInfo.version`, and `get_server_info.gitCommit`
  should agree. If the MCP tool reports an old or `unknown` commit, rebuild with
  `mvn package -DskipTests -Dgit.commit=$(git rev-parse --short HEAD)` and
  restart the MCP client.

`SQLITE_CANTOPEN: Unable to open the database file`

- Check that `--root` points to the ArchContext workspace, not necessarily the directory where the MCP client starts.
- Check that `.archcontext` exists under that root.
- Check that the process can create or read `.archcontext/archcontext.db`.
- Run `java -jar target/archcontext.jar import --root /path/to/workspace`.

Empty results

- Run `archcontext import` after editing YAML.
- Confirm the MCP client config uses the same `--root` that you imported.

Repository path warnings in the sample

- Expected unless the sample repositories exist locally.
- Use `.archcontext/local.yaml` in a real workspace to override repository paths per developer.

MCP client starts but tools are unavailable

- Check the JAR path is absolute and points to `target/archcontext.jar`.
- Check the workspace path passed to `--root` is absolute.
- Check the Java version used by the MCP client is JDK 21 or newer.
- Check whether the client supports the protocol version negotiated by the Java MCP SDK.
- Increase the client's MCP startup/tool timeout if it defaults to a very low
  value. For opencode local MCP servers, set `timeout` in the server entry and
  `experimental.mcp_timeout` to values such as `30000` milliseconds.

MCP client cannot connect

- Ensure `mcp` mode prints no banners or logs to stdout.
- Diagnostics and logs must go to stderr or a file.
- Run `scripts/smoke-test-mcp.sh` to verify packaging and a minimal stdio handshake.
- If `serverInfo` or `get_server_info` intermittently times out, suspect client
  startup timeout rather than payload size. The server warms context in the
  background, but JVM startup and MCP SDK initialization still have a non-zero
  cold-start cost.

## Security and runtime model

ArchContext is local-only.

- It does not execute shell commands.
- It does not call external services.
- It does not require network access at runtime.
- It does not require Docker or a remote database.
- It stores SQLite locally under `.archcontext/archcontext.db`.
- Repository paths are metadata only in the current MVP.
- `.archcontext/archcontext.db` is a generated local index and should not be treated as a trusted shared artifact.

In MCP mode, diagnostics and logs must go to stderr or a file. stdout is reserved exclusively for MCP protocol messages so stdio clients can parse the stream safely.
