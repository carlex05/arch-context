package dev.archcontext.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
            "get_server_info",
            "get_solution_context",
            "get_repository_context",
            "search_context",
            "get_spec_context",
            "list_adrs",
            "get_adr_context",
            "list_guidelines",
            "get_guideline",
            "get_implementation_context_for_spec",
            "get_repository_implementation_context_for_spec",
            "resolve_repository_by_path",
            "get_agent_briefing_for_spec",
            "validate_spec_completeness",
            "validate_spec_consistency",
            "suggest_next_requirement_id",
            "suggest_next_acceptance_criterion_id",
            "suggest_next_constraint_id",
            "list_active_specs",
            "upsert_solution",
            "upsert_solution_principle",
            "upsert_solution_glossary_term",
            "upsert_repository",
            "upsert_repository_component",
            "upsert_repository_responsibility",
            "create_spec",
            "create_guideline",
            "upsert_guideline",
            "upsert_spec_requirement",
            "deprecate_spec_requirement",
            "upsert_spec_acceptance_criterion",
            "deprecate_spec_acceptance_criterion",
            "add_spec_out_of_scope_item",
            "upsert_spec_constraint",
            "deprecate_spec_constraint",
            "upsert_spec_repository_change",
            "upsert_spec_affected_component",
            "update_spec_status",
            "upsert_spec_metadata",
            "upsert_spec_summary",
            "append_spec_change",
            "supersede_spec",
            "upsert_spec_related_adr",
            "deprecate_spec_related_adr",
            "upsert_spec_related_spec",
            "deprecate_spec_related_spec",
            "create_adr",
            "upsert_adr",
            "upsert_adr_consequence",
            "update_adr_status",
            "append_adr_change",
            "supersede_adr",
            "validate_workspace",
            "validate_spec_repository_coverage"),
        tools.keySet());

    assertStrictNoArgSchema(tools.get("get_server_info"));
    assertStrictNoArgSchema(tools.get("get_solution_context"));
    assertStrictNoArgSchema(tools.get("list_active_specs"));
    assertStrictNoArgSchema(tools.get("list_adrs"));
    assertProperty(tools.get("list_guidelines"), "category");
    assertProperty(tools.get("list_guidelines"), "appliesTo");
    assertRequired(tools.get("get_repository_context"), "repositoryId");
    assertRequired(tools.get("get_spec_context"), "specId");
    assertRequired(tools.get("get_adr_context"), "adrId");
    assertRequired(tools.get("get_guideline"), "guidelineId");
    assertRequired(tools.get("validate_spec_completeness"), "specId");
    assertRequired(tools.get("search_context"), "query");
    assertProperty(tools.get("search_context"), "types");
    assertRequired(tools.get("get_implementation_context_for_spec"), "specId");
    assertProperty(tools.get("get_implementation_context_for_spec"), "repositoryId");
    assertProperty(tools.get("get_implementation_context_for_spec"), "includeSuperseded");
    assertProperty(tools.get("get_implementation_context_for_spec"), "includeChangeLog");
    assertRequired(tools.get("get_repository_implementation_context_for_spec"), "specId");
    assertRequired(tools.get("get_repository_implementation_context_for_spec"), "repositoryId");
    assertProperty(tools.get("get_repository_implementation_context_for_spec"), "includeSuperseded");
    assertProperty(tools.get("get_repository_implementation_context_for_spec"), "includeChangeLog");
    assertRequired(tools.get("resolve_repository_by_path"), "path");
    assertRequired(tools.get("get_agent_briefing_for_spec"), "specId");
    assertRequired(tools.get("get_agent_briefing_for_spec"), "repositoryId");
    assertProperty(tools.get("get_agent_briefing_for_spec"), "includeSuperseded");
    assertProperty(tools.get("get_agent_briefing_for_spec"), "includeChangeLog");
    assertRequired(tools.get("validate_spec_consistency"), "specId");
    assertProperty(tools.get("validate_spec_consistency"), "strict");
    assertRequired(tools.get("suggest_next_requirement_id"), "specId");
    assertRequired(tools.get("suggest_next_requirement_id"), "requirementType");
    assertRequired(tools.get("suggest_next_acceptance_criterion_id"), "specId");
    assertRequired(tools.get("suggest_next_constraint_id"), "specId");
    assertRequired(tools.get("upsert_solution"), "id");
    assertRequired(tools.get("upsert_solution"), "name");
    assertProperty(tools.get("upsert_solution"), "principles");
    assertProperty(tools.get("upsert_solution"), "glossary");
    assertRequired(tools.get("upsert_solution_principle"), "id");
    assertRequired(tools.get("upsert_solution_principle"), "title");
    assertRequired(tools.get("upsert_solution_principle"), "description");
    assertRequired(tools.get("upsert_solution_glossary_term"), "term");
    assertRequired(tools.get("upsert_solution_glossary_term"), "definition");
    assertRequired(tools.get("upsert_repository"), "id");
    assertRequired(tools.get("upsert_repository"), "name");
    assertRequired(tools.get("upsert_repository"), "type");
    assertRequired(tools.get("upsert_repository"), "language");
    assertProperty(tools.get("upsert_repository"), "dryRun");
    assertRequired(tools.get("upsert_repository_component"), "repositoryId");
    assertRequired(tools.get("upsert_repository_component"), "componentId");
    assertRequired(tools.get("upsert_repository_component"), "name");
    assertRequired(tools.get("upsert_repository_component"), "type");
    assertRequired(tools.get("upsert_repository_responsibility"), "repositoryId");
    assertRequired(tools.get("upsert_repository_responsibility"), "id");
    assertRequired(tools.get("upsert_repository_responsibility"), "description");
    assertRequired(tools.get("create_spec"), "id");
    assertRequired(tools.get("create_spec"), "title");
    assertRequired(tools.get("create_spec"), "status");
    assertRequired(tools.get("create_spec"), "owner");
    assertRequired(tools.get("create_spec"), "problem");
    assertRequired(tools.get("create_spec"), "businessGoal");
    assertProperty(tools.get("create_spec"), "repositoryChanges");
    assertProperty(tools.get("create_spec"), "dryRun");
    assertRequired(tools.get("create_guideline"), "id");
    assertRequired(tools.get("create_guideline"), "title");
    assertProperty(tools.get("create_guideline"), "rules");
    assertRequired(tools.get("upsert_guideline"), "id");
    assertRequired(tools.get("upsert_guideline"), "title");
    assertRequired(tools.get("upsert_spec_requirement"), "specId");
    assertRequired(tools.get("upsert_spec_requirement"), "requirementType");
    assertRequired(tools.get("upsert_spec_requirement"), "id");
    assertRequired(tools.get("upsert_spec_requirement"), "description");
    assertRequired(tools.get("deprecate_spec_requirement"), "specId");
    assertRequired(tools.get("deprecate_spec_requirement"), "requirementType");
    assertRequired(tools.get("deprecate_spec_requirement"), "requirementId");
    assertRequired(tools.get("deprecate_spec_requirement"), "status");
    assertRequired(tools.get("deprecate_spec_requirement"), "reason");
    assertProperty(tools.get("deprecate_spec_requirement"), "supersededBy");
    assertProperty(tools.get("deprecate_spec_requirement"), "relatedAdr");
    assertRequired(tools.get("upsert_spec_acceptance_criterion"), "specId");
    assertRequired(tools.get("upsert_spec_acceptance_criterion"), "id");
    assertRequired(tools.get("upsert_spec_acceptance_criterion"), "description");
    assertProperty(tools.get("upsert_spec_acceptance_criterion"), "dryRun");
    assertRequired(tools.get("deprecate_spec_acceptance_criterion"), "specId");
    assertRequired(tools.get("deprecate_spec_acceptance_criterion"), "acceptanceCriterionId");
    assertRequired(tools.get("deprecate_spec_acceptance_criterion"), "status");
    assertRequired(tools.get("deprecate_spec_acceptance_criterion"), "reason");
    assertProperty(tools.get("deprecate_spec_acceptance_criterion"), "supersededBy");
    assertProperty(tools.get("deprecate_spec_acceptance_criterion"), "relatedAdr");
    assertRequired(tools.get("add_spec_out_of_scope_item"), "specId");
    assertRequired(tools.get("add_spec_out_of_scope_item"), "description");
    assertProperty(tools.get("add_spec_out_of_scope_item"), "dryRun");
    assertRequired(tools.get("upsert_spec_constraint"), "specId");
    assertRequired(tools.get("upsert_spec_constraint"), "id");
    assertRequired(tools.get("upsert_spec_constraint"), "description");
    assertProperty(tools.get("upsert_spec_constraint"), "title");
    assertProperty(tools.get("upsert_spec_constraint"), "dryRun");
    assertRequired(tools.get("deprecate_spec_constraint"), "specId");
    assertRequired(tools.get("deprecate_spec_constraint"), "constraintId");
    assertRequired(tools.get("deprecate_spec_constraint"), "status");
    assertRequired(tools.get("deprecate_spec_constraint"), "reason");
    assertProperty(tools.get("deprecate_spec_constraint"), "supersededBy");
    assertProperty(tools.get("deprecate_spec_constraint"), "relatedAdr");
    assertRequired(tools.get("upsert_spec_repository_change"), "specId");
    assertRequired(tools.get("upsert_spec_repository_change"), "repositoryId");
    assertRequired(tools.get("upsert_spec_repository_change"), "summary");
    assertProperty(tools.get("upsert_spec_repository_change"), "requirements");
    assertProperty(tools.get("upsert_spec_repository_change"), "acceptanceCriteria");
    assertProperty(tools.get("upsert_spec_repository_change"), "contractsProvided");
    assertProperty(tools.get("upsert_spec_repository_change"), "contractsConsumed");
    assertProperty(tools.get("upsert_spec_repository_change"), "outOfScope");
    assertProperty(tools.get("upsert_spec_repository_change"), "dryRun");
    assertRequired(tools.get("upsert_spec_affected_component"), "specId");
    assertRequired(tools.get("upsert_spec_affected_component"), "repositoryId");
    assertProperty(tools.get("upsert_spec_affected_component"), "path");
    assertProperty(tools.get("upsert_spec_affected_component"), "lineStart");
    assertRequired(tools.get("update_spec_status"), "specId");
    assertRequired(tools.get("update_spec_status"), "status");
    assertProperty(tools.get("update_spec_status"), "note");
    assertRequired(tools.get("upsert_spec_metadata"), "specId");
    assertProperty(tools.get("upsert_spec_metadata"), "priority");
    assertProperty(tools.get("upsert_spec_metadata"), "effortHours");
    assertRequired(tools.get("upsert_spec_summary"), "specId");
    assertProperty(tools.get("upsert_spec_summary"), "title");
    assertRequired(tools.get("append_spec_change"), "specId");
    assertRequired(tools.get("append_spec_change"), "id");
    assertRequired(tools.get("append_spec_change"), "date");
    assertRequired(tools.get("append_spec_change"), "summary");
    assertRequired(tools.get("append_spec_change"), "reason");
    assertProperty(tools.get("append_spec_change"), "relatedAdr");
    assertProperty(tools.get("append_spec_change"), "changedBy");
    assertRequired(tools.get("supersede_spec"), "oldSpecId");
    assertRequired(tools.get("supersede_spec"), "newSpecId");
    assertRequired(tools.get("supersede_spec"), "reason");
    assertRequired(tools.get("upsert_spec_related_adr"), "specId");
    assertRequired(tools.get("upsert_spec_related_adr"), "adrId");
    assertRequired(tools.get("upsert_spec_related_adr"), "type");
    assertRequired(tools.get("deprecate_spec_related_adr"), "specId");
    assertRequired(tools.get("deprecate_spec_related_adr"), "adrId");
    assertRequired(tools.get("deprecate_spec_related_adr"), "reason");
    assertRequired(tools.get("upsert_spec_related_spec"), "specId");
    assertRequired(tools.get("upsert_spec_related_spec"), "relatedSpecId");
    assertRequired(tools.get("upsert_spec_related_spec"), "type");
    assertRequired(tools.get("deprecate_spec_related_spec"), "specId");
    assertRequired(tools.get("deprecate_spec_related_spec"), "relatedSpecId");
    assertRequired(tools.get("deprecate_spec_related_spec"), "reason");
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
    assertRequired(tools.get("upsert_adr_consequence"), "adrId");
    assertRequired(tools.get("upsert_adr_consequence"), "consequence");
    assertRequired(tools.get("update_adr_status"), "adrId");
    assertRequired(tools.get("update_adr_status"), "status");
    assertProperty(tools.get("update_adr_status"), "supersededBy");
    assertProperty(tools.get("update_adr_status"), "note");
    assertRequired(tools.get("append_adr_change"), "adrId");
    assertRequired(tools.get("append_adr_change"), "id");
    assertRequired(tools.get("append_adr_change"), "date");
    assertRequired(tools.get("append_adr_change"), "summary");
    assertRequired(tools.get("append_adr_change"), "reason");
    assertProperty(tools.get("append_adr_change"), "relatedAdr");
    assertProperty(tools.get("append_adr_change"), "changedBy");
    assertRequired(tools.get("supersede_adr"), "oldAdrId");
    assertRequired(tools.get("supersede_adr"), "newAdrId");
    assertRequired(tools.get("supersede_adr"), "reason");
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

  @Test
  void structuredContentIsLimitedToSmallToolResults() {
    assertTrue(ArchContextMcpServer.shouldIncludeStructuredContent("{}"));
    assertFalse(
        ArchContextMcpServer.shouldIncludeStructuredContent(
            "x".repeat(ArchContextMcpServer.STRUCTURED_CONTENT_MAX_CHARS + 1)));
  }

  @Test
  void mcpRequestWorkIsSerialized() throws Exception {
    ArchContextMcpServer server = new ArchContextMcpServer(Files.createTempDirectory("ac-mcp"));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch secondReady = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maxActive = new AtomicInteger();

    try {
      Future<Integer> first =
          executor.submit(
              () ->
                  server.serializeRequest(
                      () -> {
                        firstEntered.countDown();
                        int current = active.incrementAndGet();
                        maxActive.accumulateAndGet(current, Math::max);
                        try {
                          assertTrue(releaseFirst.await(1, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                          Thread.currentThread().interrupt();
                          fail(e);
                        } finally {
                          active.decrementAndGet();
                        }
                        return current;
                      }));

      assertTrue(firstEntered.await(1, TimeUnit.SECONDS));

      Future<Integer> second =
          executor.submit(
              () -> {
                secondReady.countDown();
                return server.serializeRequest(
                    () -> {
                      int current = active.incrementAndGet();
                      maxActive.accumulateAndGet(current, Math::max);
                      active.decrementAndGet();
                      return current;
                    });
              });

      assertTrue(secondReady.await(1, TimeUnit.SECONDS));
      assertFalse(second.isDone());
      releaseFirst.countDown();

      assertEquals(1, first.get(1, TimeUnit.SECONDS));
      assertEquals(1, second.get(1, TimeUnit.SECONDS));
      assertEquals(1, maxActive.get());
    } finally {
      releaseFirst.countDown();
      executor.shutdownNow();
    }
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
