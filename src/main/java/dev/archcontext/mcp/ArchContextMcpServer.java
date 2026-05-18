package dev.archcontext.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.archcontext.service.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ArchContextMcpServer {
  private final McpContextService svc;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  public ArchContextMcpServer(Path root) {
    svc = new McpContextService(root);
  }

  public void run() throws IOException {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    BufferedWriter out =
        new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) continue;
      Map<String, Object> req = json.readValue(line, new TypeReference<>() {});
      Object id = req.get("id");
      String method = (String) req.get("method");
      if (id == null) {
        continue;
      }
      Object result;
      try {
        result = handle(method, map(req.get("params")));
        write(out, Map.of("jsonrpc", "2.0", "id", id, "result", result));
      } catch (Exception e) {
        write(
            out,
            Map.of(
                "jsonrpc",
                "2.0",
                "id",
                id,
                "error",
                Map.of(
                    "code",
                    -32603,
                    "message",
                    e.getMessage() == null ? e.toString() : e.getMessage())));
      }
    }
  }

  private Object handle(String method, Map<String, Object> p) {
    return switch (method) {
      case "initialize" ->
          Map.of(
              "protocolVersion",
              "2025-06-18",
              "serverInfo",
              Map.of("name", "ArchContext", "version", "0.1.0"),
              "capabilities",
              Map.of("resources", Map.of(), "tools", Map.of(), "prompts", Map.of()));
      case "resources/list" -> Map.of("resources", resources());
      case "resources/read" -> resourceRead((String) p.get("uri"));
      case "tools/list" -> Map.of("tools", tools());
      case "tools/call" -> toolCall((String) p.get("name"), map(p.get("arguments")));
      case "prompts/list" -> Map.of("prompts", prompts());
      case "prompts/get" -> promptGet((String) p.get("name"), map(p.get("arguments")));
      default -> throw new IllegalArgumentException("Unsupported MCP method: " + method);
    };
  }

  private Object resourceRead(String uri) {
    return Map.of(
        "contents",
        List.of(Map.of("uri", uri, "mimeType", "application/json", "text", svc.readResource(uri))));
  }

  private Object toolCall(String name, Map<String, Object> a) {
    Object data =
        switch (name) {
          case "get_solution_context" -> svc.getSolutionContext();
          case "get_repository_context" -> svc.getRepositoryContext(str(a, "repositoryId"));
          case "search_context" -> svc.searchContext(str(a, "query"), list(a.get("types")));
          case "get_spec_context" -> svc.getSpecContext(str(a, "specId"));
          case "get_implementation_context_for_spec" ->
              svc.getImplementationContextForSpec(str(a, "specId"), (String) a.get("repositoryId"));
          case "validate_spec_completeness" -> svc.validateSpecCompleteness(str(a, "specId"));
          case "list_active_specs" -> svc.listActiveSpecs();
          default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    return Map.of(
        "content", List.of(Map.of("type", "text", "text", dev.archcontext.util.Json.write(data))));
  }

  private Object promptGet(String name, Map<String, Object> a) {
    String text =
        switch (name) {
          case "create_spec" ->
              "Create a new ArchContext spec YAML with schemaVersion, spec metadata, requirements,"
                  + " acceptance criteria, constraints, and related ADRs. Use concise IDs and cite"
                  + " affected repositories.";
          case "review_spec" ->
              "Review the specified ArchContext spec for completeness, ambiguity, missing"
                  + " acceptance criteria, architectural impact, repository impact, constraints,"
                  + " and related ADRs.";
          case "plan_implementation_from_spec" ->
              "Build an implementation plan from the spec and ArchContext repository/ADR/guideline"
                  + " context. Keep work scoped by repository and acceptance criterion.";
          case "validate_implementation_against_spec" ->
              "Validate the implementation against the spec acceptance criteria, constraints,"
                  + " related ADRs, and applicable guidelines. Report gaps and risks.";
          case "suggest_adr" ->
              "Determine whether the spec or implementation plan introduces a decision that should"
                  + " be documented as an ADR. If yes, draft an ADR outline.";
          default -> throw new IllegalArgumentException("Unknown prompt: " + name);
        };
    return Map.of(
        "description",
        name,
        "messages",
        List.of(Map.of("role", "user", "content", Map.of("type", "text", "text", text))));
  }

  private List<Map<String, Object>> resources() {
    return List.of(
        res("archcontext://solution", "Solution"),
        res("archcontext://repositories", "Repositories"),
        res("archcontext://specs", "Specs"),
        res("archcontext://adrs", "ADRs"),
        res("archcontext://guidelines", "Guidelines"));
  }

  private Map<String, Object> res(String uri, String name) {
    return Map.of("uri", uri, "name", name, "mimeType", "application/json");
  }

  private List<Map<String, Object>> tools() {
    return List.of(
        tool("get_solution_context", Map.of()),
        tool("get_repository_context", schema("repositoryId")),
        tool("search_context", schema("query")),
        tool("get_spec_context", schema("specId")),
        tool("get_implementation_context_for_spec", schema("specId")),
        tool("validate_spec_completeness", schema("specId")),
        tool("list_active_specs", Map.of()));
  }

  private Map<String, Object> tool(String n, Map<String, Object> schema) {
    return Map.of(
        "name",
        n,
        "description",
        n.replace('_', ' '),
        "inputSchema",
        schema.isEmpty() ? Map.of("type", "object", "properties", Map.of()) : schema);
  }

  private Map<String, Object> schema(String required) {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(required, Map.of("type", "string")),
        "required",
        List.of(required));
  }

  private List<Map<String, Object>> prompts() {
    return List.of(
            "create_spec",
            "review_spec",
            "plan_implementation_from_spec",
            "validate_implementation_against_spec",
            "suggest_adr")
        .stream()
        .map(n -> Map.<String, Object>of("name", n, "description", n.replace('_', ' ')))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> map(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  @SuppressWarnings("unchecked")
  private List<String> list(Object o) {
    return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
  }

  private String str(Map<String, Object> m, String k) {
    Object v = m.get(k);
    if (v == null) throw new IllegalArgumentException("Missing required argument: " + k);
    return v.toString();
  }

  private void write(BufferedWriter out, Object msg) throws IOException {
    out.write(json.writeValueAsString(msg));
    out.write("\n");
    out.flush();
  }
}
