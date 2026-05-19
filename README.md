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

## 5-minute quickstart

Requirements:

- JDK 21 or newer.
- Maven 3.9 or newer.

ArchContext currently compiles with `maven.compiler.release=21`. The code does not require Java 25 APIs, and Java 21 keeps the MVP easier to build across local machines and CI while still supporting the official Java MCP SDK.

Build the executable JAR:

```bash
mvn -q clean package
```

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
mvn package
java -jar target/archcontext.jar --help
```

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

Run `archcontext import` in that workspace before starting the MCP server so `.archcontext/archcontext.db` exists.

## MCP compatibility

ArchContext uses the official Java MCP SDK. The currently integrated SDK version may negotiate an older protocol version than the latest published MCP specification. Verify compatibility with the MCP client you plan to use.

## How agents should use ArchContext

- Start with `get_solution_context` to understand the solution, repositories, active specs, and accepted ADRs.
- Use `get_repository_context` when working inside one repository.
- Use `get_implementation_context_for_spec` before implementing a spec.
- Use `search_context` for targeted architecture lookup.
- Prefer tools over broad resources for implementation workflows.

## Controlled write tools

ArchContext can also update known YAML context files through structured MCP tools. YAML remains the source of truth, and `.archcontext/archcontext.db` remains a generated local index rebuilt after successful writes.

Current write tools:

- `upsert_repository`: create or update one repository in `.archcontext/repositories.yaml`.
- `create_spec`: create one new spec under `.archcontext/specs/*.yaml`.
- `upsert_spec_requirement`: add or update one functional or non-functional requirement in an existing spec.

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
  "name": "create_spec",
  "arguments": {
    "id": "SPEC-002",
    "title": "Booking cancellation audit trail",
    "status": "draft",
    "owner": "booking-platform-team",
    "problem": "Support needs a reliable audit trail for customer cancellation actions.",
    "businessGoal": "Improve operational traceability and reduce manual investigation time.",
    "affectedRepositories": [
      "booking-api"
    ],
    "dryRun": true
  }
}
```

Example agent workflow:

1. Create a spec with `dryRun: true`.
2. Review the proposed change and validation result.
3. Create the spec without `dryRun`.
4. Add requirements with `upsert_spec_requirement`.
5. Let ArchContext re-import/index automatically after successful writes.
6. Review the Git diff before committing shared `.archcontext` files.

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

- `get_solution_context`
- `get_repository_context`
- `search_context`
- `get_spec_context`
- `get_implementation_context_for_spec`
- `validate_spec_completeness`
- `list_active_specs`
- `upsert_repository`
- `create_spec`
- `upsert_spec_requirement`

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

MCP client cannot connect

- Ensure `mcp` mode prints no banners or logs to stdout.
- Diagnostics and logs must go to stderr or a file.
- Run `scripts/smoke-test-mcp.sh` to verify packaging and a minimal stdio handshake.

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
