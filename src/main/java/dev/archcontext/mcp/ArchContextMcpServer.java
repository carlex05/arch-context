package dev.archcontext.mcp;

import com.fasterxml.jackson.databind.JavaType;
import dev.archcontext.domain.Models.*;
import dev.archcontext.service.McpContextService;
import dev.archcontext.service.YamlWorkspaceWriter;
import dev.archcontext.util.Json;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class ArchContextMcpServer {
  private static final String JSON_MIME_TYPE = "application/json";

  private final McpContextService svc;
  private final YamlWorkspaceWriter writer;
  private final McpJsonMapper jsonMapper;

  public ArchContextMcpServer(Path root) {
    this(root, new JacksonMcpJsonMapperSupplier().get());
  }

  ArchContextMcpServer(Path root, McpJsonMapper jsonMapper) {
    this.svc = new McpContextService(root);
    this.writer = new YamlWorkspaceWriter(root);
    this.jsonMapper = jsonMapper;
  }

  public void run() {
    run(System.in, System.out);
  }

  void run(InputStream in, OutputStream out) {
    CloseAwareInputStream closeAwareIn = new CloseAwareInputStream(in);
    McpSyncServer server = createServer(closeAwareIn, out);
    try {
      closeAwareIn.awaitClosed();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      server.closeGracefully();
    }
  }

  McpSyncServer createServer(InputStream in, OutputStream out) {
    StdioServerTransportProvider transportProvider =
        new StdioServerTransportProvider(jsonMapper, in, out);
    return McpServer.sync(transportProvider)
        .serverInfo("ArchContext", "0.1.0")
        .capabilities(
            McpSchema.ServerCapabilities.builder()
                .resources(false, false)
                .tools(false)
                .prompts(false)
                .build())
        .resources(resourceSpecifications())
        .resourceTemplates(resourceTemplateSpecifications())
        .tools(toolSpecifications())
        .prompts(promptSpecifications())
        .build();
  }

  List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications() {
    return List.of(
        resource("archcontext://solution", "Solution"),
        resource("archcontext://repositories", "Repositories"),
        resource("archcontext://specs", "Specs"),
        resource("archcontext://adrs", "ADRs"),
        resource("archcontext://guidelines", "Guidelines"));
  }

  List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications() {
    return List.of(
        resourceTemplate("archcontext://repositories/{repositoryId}", "Repository"),
        resourceTemplate("archcontext://specs/{specId}", "Spec"),
        resourceTemplate("archcontext://adrs/{adrId}", "ADR"));
  }

  List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
    return List.of(
        tool(
            "get_solution_context",
            "Return solution metadata, architecture principles, repositories, active specs, and"
                + " accepted ADRs for the workspace.",
            strictObjectSchema(Map.of(), List.of()),
            args -> svc.getSolutionContext()),
        tool(
            "get_repository_context",
            "Return repository metadata, related specs, ADRs, applicable guidelines, and"
                + " constraints for one repository.",
            strictObjectSchema(
                Map.of("repositoryId", stringProperty("Repository id")), "repositoryId"),
            args -> svc.getRepositoryContext(requiredString(args, "repositoryId"))),
        tool(
            "search_context",
            "Search specs, ADRs, guidelines, and solution context for targeted architecture"
                + " information.",
            strictObjectSchema(
                Map.of(
                    "query",
                    stringProperty("Search query"),
                    "types",
                    Map.of(
                        "type",
                        "array",
                        "items",
                        Map.of("type", "string"),
                        "description",
                        "Optional document types to search")),
                "query"),
            args ->
                svc.searchContext(requiredString(args, "query"), stringList(args.get("types")))),
        tool(
            "get_spec_context",
            "Return the full structured spec context for one spec id.",
            strictObjectSchema(Map.of("specId", stringProperty("Spec id")), "specId"),
            args -> svc.getSpecContext(requiredString(args, "specId"))),
        tool(
            "get_implementation_context_for_spec",
            "Return focused implementation context for a spec, including affected repositories,"
                + " requirements, acceptance criteria, constraints, related ADRs, and applicable"
                + " guidelines.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "repositoryId",
                    stringProperty("Optional repository id")),
                "specId"),
            args ->
                svc.getImplementationContextForSpec(
                    requiredString(args, "specId"), optionalString(args, "repositoryId"))),
        tool(
            "validate_spec_completeness",
            "Check whether a spec has the minimum sections needed for implementation planning.",
            strictObjectSchema(Map.of("specId", stringProperty("Spec id")), "specId"),
            args -> svc.validateSpecCompleteness(requiredString(args, "specId"))),
        tool(
            "list_active_specs",
            "Return specs with active implementation statuses such as draft, active, in-progress,"
                + " or review.",
            strictObjectSchema(Map.of(), List.of()),
            args -> svc.listActiveSpecs()),
        tool(
            "upsert_repository",
            "Create or update a repository definition in repositories.yaml using structured,"
                + " validated input.",
            strictObjectSchema(
                Map.ofEntries(
                    Map.entry("id", stringProperty("Lowercase kebab-case repository id")),
                    Map.entry("name", stringProperty("Repository display name")),
                    Map.entry("path", stringProperty("Optional repository path")),
                    Map.entry(
                        "type", stringProperty("Repository type such as backend or frontend")),
                    Map.entry("language", stringProperty("Primary repository language")),
                    Map.entry("boundedContext", stringProperty("Optional bounded context")),
                    Map.entry(
                        "description",
                        stringProperty("Optional repository responsibility summary")),
                    Map.entry(
                        "responsibilities", arrayProperty("Optional repository responsibilities")),
                    Map.entry("components", arrayProperty("Optional repository components")),
                    Map.entry("dryRun", booleanProperty("Validate and preview without writing"))),
                "id",
                "name",
                "type",
                "language"),
            args -> writer.upsertRepository(repository(args), bool(args.get("dryRun")))),
        tool(
            "create_spec",
            "Create a new spec YAML file under specs/ using structured, validated input.",
            strictObjectSchema(
                Map.ofEntries(
                    Map.entry("id", stringProperty("Spec id")),
                    Map.entry("title", stringProperty("Spec title")),
                    Map.entry("status", stringProperty("Spec status")),
                    Map.entry("owner", stringProperty("Spec owner")),
                    Map.entry("problem", stringProperty("Problem statement")),
                    Map.entry("businessGoal", stringProperty("Business goal")),
                    Map.entry(
                        "affectedRepositories", stringArrayProperty("Affected repository ids")),
                    Map.entry("affectedComponents", arrayProperty("Affected component refs")),
                    Map.entry("functionalRequirements", arrayProperty("Functional requirements")),
                    Map.entry(
                        "nonFunctionalRequirements", arrayProperty("Non-functional requirements")),
                    Map.entry("acceptanceCriteria", arrayProperty("Acceptance criteria")),
                    Map.entry("constraints", arrayProperty("Structured constraints")),
                    Map.entry("outOfScope", arrayProperty("Out-of-scope items")),
                    Map.entry("openQuestions", arrayProperty("Open questions")),
                    Map.entry("relatedAdrs", stringArrayProperty("Related ADR ids")),
                    Map.entry("dryRun", booleanProperty("Validate and preview without writing"))),
                "id",
                "title",
                "status",
                "owner",
                "problem",
                "businessGoal"),
            args -> writer.createSpec(spec(args), bool(args.get("dryRun")))),
        tool(
            "upsert_spec_requirement",
            "Add or update one functional or non-functional requirement in an existing spec YAML.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "requirementType",
                    stringProperty("functional or nonFunctional"),
                    "id",
                    stringProperty("Requirement id"),
                    "description",
                    stringProperty("Requirement description"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "requirementType",
                "id",
                "description"),
            args ->
                writer.upsertSpecRequirement(
                    requiredString(args, "specId"),
                    requiredString(args, "requirementType"),
                    new Requirement(
                        requiredString(args, "id"), requiredString(args, "description")),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_acceptance_criterion",
            "Add or update one acceptance criterion in an existing spec YAML.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "id",
                    stringProperty("Acceptance criterion id"),
                    "description",
                    stringProperty("Acceptance criterion description"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "id",
                "description"),
            args ->
                writer.upsertSpecAcceptanceCriterion(
                    requiredString(args, "specId"),
                    new AcceptanceCriterion(
                        requiredString(args, "id"), requiredString(args, "description")),
                    bool(args.get("dryRun")))),
        tool(
            "add_spec_out_of_scope_item",
            "Add one out-of-scope item to an existing spec YAML, avoiding duplicate descriptions.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "description",
                    stringProperty("Out-of-scope item description"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "description"),
            args ->
                writer.addSpecOutOfScopeItem(
                    requiredString(args, "specId"),
                    new OutOfScopeItem(requiredString(args, "description")),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_constraint",
            "Add or update one structured constraint in an existing spec YAML without removing"
                + " legacy constraints.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "id",
                    stringProperty("Constraint id"),
                    "title",
                    stringProperty("Optional constraint title"),
                    "description",
                    stringProperty("Constraint description"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "id",
                "description"),
            args ->
                writer.upsertSpecConstraint(
                    requiredString(args, "specId"),
                    new Constraint(
                        requiredString(args, "id"),
                        optionalString(args, "title"),
                        requiredString(args, "description")),
                    bool(args.get("dryRun")))),
        tool(
            "validate_workspace",
            "Validate repository references, component references, active spec readiness, related"
                + " ADR references, and supported schema versions without writing files.",
            strictObjectSchema(
                Map.of("strict", booleanProperty("Treat warnings such as missing ADRs as errors")),
                List.of()),
            args -> writer.validateWorkspace(bool(args.get("strict")))));
  }

  List<McpServerFeatures.SyncPromptSpecification> promptSpecifications() {
    return List.of(
        prompt(
            "create_spec",
            "create spec",
            "Create a new ArchContext spec YAML with schemaVersion, spec metadata, requirements,"
                + " acceptance criteria, constraints, and related ADRs. Use concise IDs and cite"
                + " affected repositories."),
        prompt(
            "review_spec",
            "review spec",
            "Review the specified ArchContext spec for completeness, ambiguity, missing"
                + " acceptance criteria, architectural impact, repository impact, constraints,"
                + " and related ADRs."),
        prompt(
            "plan_implementation_from_spec",
            "plan implementation from spec",
            "Build an implementation plan from the spec and ArchContext repository/ADR/guideline"
                + " context. Keep work scoped by repository and acceptance criterion."),
        prompt(
            "validate_implementation_against_spec",
            "validate implementation against spec",
            "Validate the implementation against the spec acceptance criteria, constraints,"
                + " related ADRs, and applicable guidelines. Report gaps and risks."),
        prompt(
            "suggest_adr",
            "suggest adr",
            "Determine whether the spec or implementation plan introduces a decision that should"
                + " be documented as an ADR. If yes, draft an ADR outline."));
  }

  private McpServerFeatures.SyncResourceSpecification resource(String uri, String name) {
    McpSchema.Resource resource =
        McpSchema.Resource.builder().uri(uri).name(name).mimeType(JSON_MIME_TYPE).build();
    return new McpServerFeatures.SyncResourceSpecification(resource, this::readResource);
  }

  private McpServerFeatures.SyncResourceTemplateSpecification resourceTemplate(
      String uriTemplate, String name) {
    McpSchema.ResourceTemplate template =
        McpSchema.ResourceTemplate.builder()
            .uriTemplate(uriTemplate)
            .name(name)
            .mimeType(JSON_MIME_TYPE)
            .build();
    return new McpServerFeatures.SyncResourceTemplateSpecification(template, this::readResource);
  }

  private McpSchema.ReadResourceResult readResource(
      Object exchange, McpSchema.ReadResourceRequest request) {
    return new McpSchema.ReadResourceResult(
        List.of(
            new McpSchema.TextResourceContents(
                request.uri(), JSON_MIME_TYPE, svc.readResource(request.uri()))));
  }

  private McpServerFeatures.SyncToolSpecification tool(
      String name, String description, Map<String, Object> inputSchema, ToolHandler handler) {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .outputSchema(
                Map.of(
                    "type",
                    "object",
                    "properties",
                    Map.of("data", Map.of("description", "Structured ArchContext result"))))
            .build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> callTool(handler, request.arguments()))
        .build();
  }

  private McpSchema.CallToolResult callTool(ToolHandler handler, Map<String, Object> arguments) {
    try {
      Object data = handler.call(arguments == null ? Map.of() : arguments);
      Object structured = Map.of("data", Json.MAPPER.convertValue(data, Object.class));
      boolean isToolError =
          (data instanceof WriteResult result && !result.validation().errors().isEmpty())
              || (data instanceof WriteValidation validation && !validation.errors().isEmpty());
      return McpSchema.CallToolResult.builder()
          .content(List.of(new McpSchema.TextContent(Json.write(data))))
          .structuredContent(structured)
          .isError(isToolError)
          .build();
    } catch (IllegalArgumentException e) {
      return McpSchema.CallToolResult.builder()
          .content(List.of(new McpSchema.TextContent(e.getMessage())))
          .isError(true)
          .build();
    }
  }

  private McpServerFeatures.SyncPromptSpecification prompt(
      String name, String description, String text) {
    McpSchema.Prompt prompt = new McpSchema.Prompt(name, description, List.of());
    return new McpServerFeatures.SyncPromptSpecification(
        prompt,
        (exchange, request) ->
            new McpSchema.GetPromptResult(
                description,
                List.of(
                    new McpSchema.PromptMessage(
                        McpSchema.Role.USER, new McpSchema.TextContent(text)))));
  }

  private static Map<String, Object> strictObjectSchema(
      Map<String, Object> properties, String... required) {
    return strictObjectSchema(properties, List.of(required));
  }

  private static Map<String, Object> strictObjectSchema(
      Map<String, Object> properties, List<String> required) {
    return Map.of(
        "type",
        "object",
        "properties",
        properties,
        "required",
        required,
        "additionalProperties",
        false);
  }

  private static Map<String, Object> stringProperty(String description) {
    return Map.of("type", "string", "description", description);
  }

  private static Map<String, Object> booleanProperty(String description) {
    return Map.of("type", "boolean", "description", description);
  }

  private static Map<String, Object> arrayProperty(String description) {
    return Map.of("type", "array", "items", Map.of("type", "object"), "description", description);
  }

  private static Map<String, Object> stringArrayProperty(String description) {
    return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
  }

  private static String requiredString(Map<String, Object> args, String name) {
    String value = optionalString(args, name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: " + name);
    }
    return value;
  }

  private static String optionalString(Map<String, Object> args, String name) {
    Object value = args.get(name);
    return value == null ? null : value.toString();
  }

  private static List<String> stringList(Object value) {
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }

  private static boolean bool(Object value) {
    return value instanceof Boolean b && b;
  }

  private static RepositoryDefinition repository(Map<String, Object> args) {
    return new RepositoryDefinition(
        requiredString(args, "id"),
        requiredString(args, "name"),
        optionalString(args, "path"),
        requiredString(args, "type"),
        requiredString(args, "language"),
        optionalString(args, "boundedContext"),
        optionalString(args, "description"),
        list(args.get("responsibilities"), Responsibility.class),
        list(args.get("components"), Component.class));
  }

  private static Spec spec(Map<String, Object> args) {
    return new Spec(
        requiredString(args, "id"),
        requiredString(args, "title"),
        requiredString(args, "status"),
        requiredString(args, "owner"),
        requiredString(args, "problem"),
        requiredString(args, "businessGoal"),
        stringList(args.get("affectedRepositories")),
        List.of(),
        list(args.get("functionalRequirements"), Requirement.class),
        list(args.get("nonFunctionalRequirements"), Requirement.class),
        list(args.get("acceptanceCriteria"), AcceptanceCriterion.class),
        List.of(),
        list(args.get("constraints"), Constraint.class),
        list(args.get("affectedComponents"), ComponentRef.class),
        list(args.get("outOfScope"), OutOfScopeItem.class),
        list(args.get("openQuestions"), OpenQuestion.class),
        stringList(args.get("relatedAdrs")),
        null);
  }

  private static <T> List<T> list(Object value, Class<T> type) {
    if (!(value instanceof List<?>)) return List.of();
    JavaType listType = Json.MAPPER.getTypeFactory().constructCollectionType(List.class, type);
    return Json.MAPPER.convertValue(value, listType);
  }

  @FunctionalInterface
  private interface ToolHandler {
    Object call(Map<String, Object> args);
  }

  private static final class CloseAwareInputStream extends FilterInputStream {
    private final CountDownLatch closed = new CountDownLatch(1);

    private CloseAwareInputStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value == -1) closed.countDown();
      return value;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int count = super.read(b, off, len);
      if (count == -1) closed.countDown();
      return count;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        closed.countDown();
      }
    }

    private void awaitClosed() throws InterruptedException {
      closed.await();
    }
  }
}
