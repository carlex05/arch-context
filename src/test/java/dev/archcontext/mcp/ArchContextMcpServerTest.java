package dev.archcontext.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArchContextMcpServerTest {
  @Test
  void serverCanBeConstructedUsingSdkStdioTransport() throws Exception {
    ArchContextMcpServer server = new ArchContextMcpServer(Files.createTempDirectory("ac-mcp"));
    ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertDoesNotThrow(() -> server.createServer(in, out).closeGracefully());
  }

  @Test
  void resourcesAndTemplatesAreRegistered() throws Exception {
    ArchContextMcpServer server = new ArchContextMcpServer(Files.createTempDirectory("ac-mcp"));

    assertEquals(
        List.of(
            "archcontext://solution",
            "archcontext://repositories",
            "archcontext://specs",
            "archcontext://adrs",
            "archcontext://guidelines"),
        server.resourceSpecifications().stream().map(s -> s.resource().uri()).toList());

    assertEquals(
        List.of(
            "archcontext://repositories/{repositoryId}",
            "archcontext://specs/{specId}",
            "archcontext://adrs/{adrId}"),
        server.resourceTemplateSpecifications().stream()
            .map(s -> s.resourceTemplate().uriTemplate())
            .toList());
  }

  @Test
  void toolsAreRegisteredWithAccurateSchemas() throws Exception {
    ArchContextMcpServer server = new ArchContextMcpServer(Files.createTempDirectory("ac-mcp"));
    Map<String, McpSchema.Tool> tools = new LinkedHashMap<>();
    server.toolSpecifications().forEach(s -> tools.put(s.tool().name(), s.tool()));

    assertEquals(
        Set.of(
            "get_solution_context",
            "get_repository_context",
            "search_context",
            "get_spec_context",
            "list_adrs",
            "get_adr_context",
            "get_implementation_context_for_spec",
            "get_repository_implementation_context_for_spec",
            "resolve_repository_by_path",
            "validate_spec_completeness",
            "list_active_specs",
            "upsert_repository",
            "create_spec",
            "upsert_spec_requirement",
            "upsert_spec_acceptance_criterion",
            "add_spec_out_of_scope_item",
            "upsert_spec_constraint",
            "upsert_spec_repository_change",
            "create_adr",
            "upsert_adr",
            "validate_workspace",
            "validate_spec_repository_coverage"),
        tools.keySet());

    assertStrictNoArgSchema(tools.get("get_solution_context"));
    assertStrictNoArgSchema(tools.get("list_active_specs"));
    assertStrictNoArgSchema(tools.get("list_adrs"));
    assertRequired(tools.get("get_repository_context"), "repositoryId");
    assertRequired(tools.get("get_spec_context"), "specId");
    assertRequired(tools.get("get_adr_context"), "adrId");
    assertRequired(tools.get("validate_spec_completeness"), "specId");
    assertRequired(tools.get("search_context"), "query");
    assertProperty(tools.get("search_context"), "types");
    assertRequired(tools.get("get_implementation_context_for_spec"), "specId");
    assertProperty(tools.get("get_implementation_context_for_spec"), "repositoryId");
    assertRequired(tools.get("get_repository_implementation_context_for_spec"), "specId");
    assertRequired(tools.get("get_repository_implementation_context_for_spec"), "repositoryId");
    assertRequired(tools.get("resolve_repository_by_path"), "path");
    assertRequired(tools.get("upsert_repository"), "id");
    assertRequired(tools.get("upsert_repository"), "name");
    assertRequired(tools.get("upsert_repository"), "type");
    assertRequired(tools.get("upsert_repository"), "language");
    assertProperty(tools.get("upsert_repository"), "dryRun");
    assertRequired(tools.get("create_spec"), "id");
    assertRequired(tools.get("create_spec"), "title");
    assertRequired(tools.get("create_spec"), "status");
    assertRequired(tools.get("create_spec"), "owner");
    assertRequired(tools.get("create_spec"), "problem");
    assertRequired(tools.get("create_spec"), "businessGoal");
    assertProperty(tools.get("create_spec"), "repositoryChanges");
    assertProperty(tools.get("create_spec"), "dryRun");
    assertRequired(tools.get("upsert_spec_requirement"), "specId");
    assertRequired(tools.get("upsert_spec_requirement"), "requirementType");
    assertRequired(tools.get("upsert_spec_requirement"), "id");
    assertRequired(tools.get("upsert_spec_requirement"), "description");
    assertRequired(tools.get("upsert_spec_acceptance_criterion"), "specId");
    assertRequired(tools.get("upsert_spec_acceptance_criterion"), "id");
    assertRequired(tools.get("upsert_spec_acceptance_criterion"), "description");
    assertProperty(tools.get("upsert_spec_acceptance_criterion"), "dryRun");
    assertRequired(tools.get("add_spec_out_of_scope_item"), "specId");
    assertRequired(tools.get("add_spec_out_of_scope_item"), "description");
    assertProperty(tools.get("add_spec_out_of_scope_item"), "dryRun");
    assertRequired(tools.get("upsert_spec_constraint"), "specId");
    assertRequired(tools.get("upsert_spec_constraint"), "id");
    assertRequired(tools.get("upsert_spec_constraint"), "description");
    assertProperty(tools.get("upsert_spec_constraint"), "title");
    assertProperty(tools.get("upsert_spec_constraint"), "dryRun");
    assertRequired(tools.get("upsert_spec_repository_change"), "specId");
    assertRequired(tools.get("upsert_spec_repository_change"), "repositoryId");
    assertRequired(tools.get("upsert_spec_repository_change"), "summary");
    assertProperty(tools.get("upsert_spec_repository_change"), "requirements");
    assertProperty(tools.get("upsert_spec_repository_change"), "acceptanceCriteria");
    assertProperty(tools.get("upsert_spec_repository_change"), "contractsProvided");
    assertProperty(tools.get("upsert_spec_repository_change"), "contractsConsumed");
    assertProperty(tools.get("upsert_spec_repository_change"), "outOfScope");
    assertProperty(tools.get("upsert_spec_repository_change"), "dryRun");
    assertRequired(tools.get("create_adr"), "id");
    assertRequired(tools.get("create_adr"), "title");
    assertRequired(tools.get("create_adr"), "status");
    assertRequired(tools.get("create_adr"), "date");
    assertRequired(tools.get("create_adr"), "context");
    assertRequired(tools.get("create_adr"), "decision");
    assertProperty(tools.get("create_adr"), "consequences");
    assertProperty(tools.get("create_adr"), "affectedRepositories");
    assertProperty(tools.get("create_adr"), "relatedSpecs");
    assertProperty(tools.get("create_adr"), "dryRun");
    assertRequired(tools.get("upsert_adr"), "id");
    assertRequired(tools.get("upsert_adr"), "title");
    assertRequired(tools.get("upsert_adr"), "status");
    assertRequired(tools.get("upsert_adr"), "date");
    assertRequired(tools.get("upsert_adr"), "context");
    assertRequired(tools.get("upsert_adr"), "decision");
    assertProperty(tools.get("upsert_adr"), "dryRun");
    assertProperty(tools.get("validate_workspace"), "strict");
    assertRequired(tools.get("validate_spec_repository_coverage"), "specId");
    assertProperty(tools.get("validate_spec_repository_coverage"), "strict");
  }

  @Test
  void promptsAreRegistered() throws Exception {
    ArchContextMcpServer server = new ArchContextMcpServer(Files.createTempDirectory("ac-mcp"));

    assertEquals(
        List.of(
            "create_spec",
            "review_spec",
            "plan_implementation_from_spec",
            "validate_implementation_against_spec",
            "suggest_adr"),
        server.promptSpecifications().stream().map(s -> s.prompt().name()).toList());
  }

  @Test
  void stdioStartupWithClosedInputDoesNotPrintBannersToStdout() throws Exception {
    ArchContextMcpServer server = new ArchContextMcpServer(Files.createTempDirectory("ac-mcp"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    server.run(new ByteArrayInputStream(new byte[0]), out);
    Thread.sleep(100);

    assertEquals("", out.toString());
  }

  private static void assertStrictNoArgSchema(McpSchema.Tool tool) {
    assertEquals("object", tool.inputSchema().get("type"));
    assertEquals(false, tool.inputSchema().get("additionalProperties"));
    assertEquals(Map.of(), tool.inputSchema().get("properties"));
    assertEquals(List.of(), tool.inputSchema().get("required"));
  }

  @SuppressWarnings("unchecked")
  private static void assertRequired(McpSchema.Tool tool, String property) {
    assertProperty(tool, property);
    assertTrue(((List<String>) tool.inputSchema().get("required")).contains(property));
    assertEquals(false, tool.inputSchema().get("additionalProperties"));
  }

  @SuppressWarnings("unchecked")
  private static void assertProperty(McpSchema.Tool tool, String property) {
    Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().get("properties");
    assertTrue(properties.containsKey(property));
  }
}
