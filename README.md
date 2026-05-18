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
- **MCP stdio server**: `archcontext mcp` exposes resources, tools, and prompts over standard input/output. stdout is reserved for MCP protocol messages in MCP mode.

ArchContext explicitly supports one solution/workspace composed of multiple source code repositories.

## Installation from source

Requires Maven and a JDK compatible with the configured Java release.

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
java -jar target/archcontext.jar mcp
```

`repositories.yaml` is shared and versionable. `local.yaml` is developer-specific and ignored by `.archcontext/.gitignore`; it can override repository paths for each developer.

## MCP client configuration example

```json
{
  "mcpServers": {
    "archcontext": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/archcontext.jar",
        "mcp"
      ]
    }
  }
}
```

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

## Security and runtime model

ArchContext is local-only. It does not expose shell execution, does not send data to external services, does not inspect Git history, and does not require Docker or a remote database.
