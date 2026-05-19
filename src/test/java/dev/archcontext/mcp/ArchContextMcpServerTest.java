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
            "get_implementation_context_for_spec",
            "validate_spec_completeness",
            "list_active_specs"),
        tools.keySet());

    assertStrictNoArgSchema(tools.get("get_solution_context"));
    assertStrictNoArgSchema(tools.get("list_active_specs"));
    assertRequired(tools.get("get_repository_context"), "repositoryId");
    assertRequired(tools.get("get_spec_context"), "specId");
    assertRequired(tools.get("validate_spec_completeness"), "specId");
    assertRequired(tools.get("search_context"), "query");
    assertProperty(tools.get("search_context"), "types");
    assertRequired(tools.get("get_implementation_context_for_spec"), "specId");
    assertProperty(tools.get("get_implementation_context_for_spec"), "repositoryId");
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
