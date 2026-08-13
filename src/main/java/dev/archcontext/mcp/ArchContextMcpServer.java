package dev.archcontext.mcp;

import com.fasterxml.jackson.databind.JavaType;
import dev.archcontext.BuildInfo;
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
import java.util.function.Supplier;

public class ArchContextMcpServer {
  private static final String JSON_MIME_TYPE = "application/json";
  static final int STRUCTURED_CONTENT_MAX_CHARS = 1_024;

  private final McpContextService svc;
  private final YamlWorkspaceWriter writer;
  private final McpJsonMapper jsonMapper;
  private final Object requestLock = new Object();

  public ArchContextMcpServer(Path root) {
    this(root, new JacksonMcpJsonMapperSupplier().get());
  }

  ArchContextMcpServer(Path root, McpJsonMapper jsonMapper) {
    this.svc = new McpContextService(root);
    this.writer = new YamlWorkspaceWriter(root);
    this.jsonMapper = jsonMapper;
    warmUpAsync();
  }

  private void warmUpAsync() {
    Thread thread =
        new Thread(
            () ->
                serializeRequest(
                    () -> {
                      svc.warmUp();
                      return null;
                    }),
            "archcontext-mcp-warmup");
    thread.setDaemon(true);
    thread.start();
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
        .serverInfo("ArchContext", BuildInfo.current().displayVersion())
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
        resource("archcontext://guidelines", "Guidelines"),
        resource("archcontext://reviews", "Implementation reviews"));
  }

  List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications() {
    return List.of(
        resourceTemplate("archcontext://repositories/{repositoryId}", "Repository"),
        resourceTemplate("archcontext://specs/{specId}", "Spec"),
        resourceTemplate("archcontext://adrs/{adrId}", "ADR"),
        resourceTemplate("archcontext://reviews/{reviewId}", "Implementation review"));
  }

  List<McpServerFeatures.SyncToolSpecification> toolSpecifications() {
    return List.of(
        tool(
            "get_server_info",
            "Return ArchContext MCP server build metadata, including jar version and commit.",
            strictObjectSchema(Map.of(), List.of()),
            args -> BuildInfo.current()),
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
            "Search specs, ADRs, guidelines, implementation reviews, and solution context for targeted architecture"
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
            "list_adrs",
            "Return all ADRs in the workspace.",
            strictObjectSchema(Map.of(), List.of()),
            args -> svc.listAdrs()),
        tool(
            "get_adr_context",
            "Return one ADR by id.",
            strictObjectSchema(Map.of("adrId", stringProperty("ADR id")), "adrId"),
            args -> svc.getAdrContext(requiredString(args, "adrId"))),
        tool(
            "list_guidelines",
            "Return guidelines with optional category or appliesTo filtering.",
            strictObjectSchema(
                Map.of(
                    "category",
                    stringProperty("Optional guideline category"),
                    "appliesTo",
                    stringProperty("Optional repository id, language, or repository type")),
                List.of()),
            args -> svc.listGuidelines(optionalString(args, "category"), optionalString(args, "appliesTo"))),
        tool(
            "get_guideline",
            "Return one guideline by id with its rules.",
            strictObjectSchema(Map.of("guidelineId", stringProperty("Guideline id")), "guidelineId"),
            args -> svc.getGuidelineContext(requiredString(args, "guidelineId"))),
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
                    stringProperty("Optional repository id"),
                    "includeSuperseded",
                    booleanProperty("Include obsolete/superseded/rejected items"),
                    "includeChangeLog",
                    stringProperty("none, summary, or full"),
                    "maxHistoricalItems",
                    integerProperty("Maximum summary changelog entries")),
                "specId"),
            args ->
                svc.getImplementationContextForSpec(
                    requiredString(args, "specId"),
                    optionalString(args, "repositoryId"),
                    bool(args.get("includeSuperseded")),
                    optionalString(args, "includeChangeLog"),
                    optionalIntOrDefault(args, "maxHistoricalItems", 5))),
        tool(
            "get_repository_implementation_context_for_spec",
            "Return repository-scoped implementation context for one spec and repository,"
                + " including local requirements, acceptance criteria, contracts, out-of-scope"
                + " items, other affected repositories, ADRs, and guidelines.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "repositoryId",
                    stringProperty("Repository id"),
                    "includeSuperseded",
                    booleanProperty("Include obsolete/superseded/rejected items"),
                    "includeChangeLog",
                    stringProperty("none, summary, or full"),
                    "maxHistoricalItems",
                    integerProperty("Maximum summary changelog entries")),
                "specId",
                "repositoryId"),
            args ->
                svc.getRepositoryImplementationContextForSpec(
                    requiredString(args, "specId"),
                    requiredString(args, "repositoryId"),
                    bool(args.get("includeSuperseded")),
                    optionalString(args, "includeChangeLog"),
                    optionalIntOrDefault(args, "maxHistoricalItems", 5))),
        tool(
            "resolve_repository_by_path",
            "Resolve a local filesystem path to the ArchContext repository definition whose"
                + " resolved path contains it.",
            strictObjectSchema(Map.of("path", stringProperty("Local filesystem path")), "path"),
            args -> svc.resolveRepositoryByPath(requiredString(args, "path"))),
        tool(
            "get_agent_briefing_for_spec",
            "Return one consolidated repository-scoped briefing for an agent implementing a spec.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "repositoryId",
                    stringProperty("Repository id"),
                    "includeSuperseded",
                    booleanProperty("Include obsolete/superseded/rejected items"),
                    "includeChangeLog",
                    stringProperty("none, summary, or full"),
                    "maxHistoricalItems",
                    integerProperty("Maximum summary changelog entries")),
                "specId",
                "repositoryId"),
            args ->
                svc.getAgentBriefingForSpec(
                    requiredString(args, "specId"),
                    requiredString(args, "repositoryId"),
                    bool(args.get("includeSuperseded")),
                    optionalString(args, "includeChangeLog"),
                    optionalIntOrDefault(args, "maxHistoricalItems", 5))),
        tool(
            "validate_spec_completeness",
            "Check whether a spec has the minimum sections needed for implementation planning.",
            strictObjectSchema(Map.of("specId", stringProperty("Spec id")), "specId"),
            args -> svc.validateSpecCompleteness(requiredString(args, "specId"))),
        tool(
            "validate_spec_consistency",
            "Validate deterministic consistency rules for one spec, including superseding and"
                + " non-implementable assignments.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "strict",
                    booleanProperty("Treat warnings as errors")),
                "specId"),
            args -> writer.validateSpecConsistency(requiredString(args, "specId"), bool(args.get("strict")))),
        tool(
            "suggest_next_requirement_id",
            "Suggest the next requirement id for one spec and requirement type.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "requirementType",
                    stringProperty("functional or nonFunctional")),
                "specId",
                "requirementType"),
            args ->
                writer.suggestNextRequirementId(
                    requiredString(args, "specId"), requiredString(args, "requirementType"))),
        tool(
            "suggest_next_acceptance_criterion_id",
            "Suggest the next acceptance criterion id for one spec.",
            strictObjectSchema(Map.of("specId", stringProperty("Spec id")), "specId"),
            args -> writer.suggestNextAcceptanceCriterionId(requiredString(args, "specId"))),
        tool(
            "suggest_next_constraint_id",
            "Suggest the next structured constraint id for one spec.",
            strictObjectSchema(Map.of("specId", stringProperty("Spec id")), "specId"),
            args -> writer.suggestNextConstraintId(requiredString(args, "specId"))),
        tool(
            "list_active_specs",
            "Return specs with active implementation statuses such as draft, active, in-progress,"
                + " or review.",
            strictObjectSchema(Map.of(), List.of()),
            args -> svc.listActiveSpecs()),
        tool(
            "upsert_solution",
            "Create or update solution identity, vision, principles, cross-cutting concerns, and glossary.",
            strictObjectSchema(solutionSchemaProperties(), "id", "name"),
            args ->
                writer.upsertSolution(
                    solution(args), list(args.get("principles"), Principle.class), bool(args.get("dryRun")))),
        tool(
            "upsert_solution_principle",
            "Add or update one solution principle.",
            strictObjectSchema(
                Map.of(
                    "id",
                    stringProperty("Principle id"),
                    "title",
                    stringProperty("Principle title"),
                    "description",
                    stringProperty("Principle description"),
                    "rationale",
                    stringProperty("Principle rationale"),
                    "appliesTo",
                    stringArrayProperty("Repository ids or *"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "id",
                "title",
                "description"),
            args -> writer.upsertSolutionPrinciple(principle(args), bool(args.get("dryRun")))),
        tool(
            "upsert_solution_glossary_term",
            "Add or update one solution glossary term.",
            strictObjectSchema(
                Map.of(
                    "term",
                    stringProperty("Glossary term"),
                    "definition",
                    stringProperty("Glossary definition"),
                    "aliases",
                    stringArrayProperty("Optional aliases"),
                    "relatedTerms",
                    stringArrayProperty("Optional related terms"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "term",
                "definition"),
            args -> writer.upsertSolutionGlossaryTerm(glossaryTerm(args), bool(args.get("dryRun")))),
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
            "upsert_repository_component",
            "Add or update one internal component for a repository.",
            strictObjectSchema(
                Map.of(
                    "repositoryId",
                    stringProperty("Repository id"),
                    "componentId",
                    stringProperty("Component id"),
                    "name",
                    stringProperty("Component name"),
                    "type",
                    stringProperty("Component type"),
                    "path",
                    stringProperty("Component path"),
                    "description",
                    stringProperty("Component description"),
                    "responsibilities",
                    stringArrayProperty("Responsibility ids"),
                    "dependsOn",
                    stringArrayProperty("Component dependencies"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "repositoryId",
                "componentId",
                "name",
                "type"),
            args ->
                writer.upsertRepositoryComponent(
                    requiredString(args, "repositoryId"), component(args), bool(args.get("dryRun")))),
        tool(
            "upsert_repository_responsibility",
            "Add or update one responsibility for a repository.",
            strictObjectSchema(
                Map.of(
                    "repositoryId",
                    stringProperty("Repository id"),
                    "id",
                    stringProperty("Responsibility id"),
                    "description",
                    stringProperty("Responsibility description"),
                    "category",
                    stringProperty("Optional responsibility category"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "repositoryId",
                "id",
                "description"),
            args ->
                writer.upsertRepositoryResponsibility(
                    requiredString(args, "repositoryId"), responsibility(args), bool(args.get("dryRun")))),
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
                    Map.entry("repositoryChanges", arrayProperty("Repository-scoped changes")),
                    Map.entry("metadata", Map.of("type", "object", "description", "Spec planning metadata")),
                    Map.entry("changeLog", arrayProperty("Structured spec change log")),
                    Map.entry("supersededBy", stringProperty("Replacement spec id")),
                    Map.entry("supersedes", stringArrayProperty("Superseded spec ids")),
                    Map.entry("statusNote", stringProperty("Spec status note")),
                    Map.entry("relatedSpecs", arrayProperty("Structured related spec links")),
                    Map.entry("relatedAdrLinks", arrayProperty("Structured related ADR links")),
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
            "create_guideline",
            "Create a new guideline YAML file under guidelines/ using structured, validated input.",
            strictObjectSchema(guidelineSchemaProperties(), "id", "title"),
            args -> writer.createGuideline(guideline(args), bool(args.get("dryRun")))),
        tool(
            "upsert_guideline",
            "Create or update a guideline YAML file under guidelines/ using structured, validated input.",
            strictObjectSchema(guidelineSchemaProperties(), "id", "title"),
            args -> writer.upsertGuideline(guideline(args), bool(args.get("dryRun")))),
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
            "deprecate_spec_requirement",
            "Mark one existing functional or non-functional requirement as obsolete, superseded,"
                + " or rejected without deleting it.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "requirementType",
                    stringProperty("functional or nonFunctional"),
                    "requirementId",
                    stringProperty("Requirement id"),
                    "status",
                    stringProperty("obsolete, superseded, or rejected"),
                    "reason",
                    stringProperty("Reason why the requirement is no longer implementable"),
                    "supersededBy",
                    stringProperty("Optional replacement requirement id"),
                    "relatedAdr",
                    stringProperty("Optional ADR id that records the decision"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "requirementType",
                "requirementId",
                "status",
                "reason"),
            args ->
                writer.deprecateSpecRequirement(
                    requiredString(args, "specId"),
                    requiredString(args, "requirementType"),
                    requiredString(args, "requirementId"),
                    requiredString(args, "status"),
                    requiredString(args, "reason"),
                    optionalString(args, "supersededBy"),
                    optionalString(args, "relatedAdr"),
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
            "deprecate_spec_acceptance_criterion",
            "Mark one existing acceptance criterion as obsolete, superseded, or rejected without"
                + " deleting it.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "acceptanceCriterionId",
                    stringProperty("Acceptance criterion id"),
                    "status",
                    stringProperty("obsolete, superseded, or rejected"),
                    "reason",
                    stringProperty("Reason why the acceptance criterion is no longer implementable"),
                    "supersededBy",
                    stringProperty("Optional replacement acceptance criterion id"),
                    "relatedAdr",
                    stringProperty("Optional ADR id that records the decision"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "acceptanceCriterionId",
                "status",
                "reason"),
            args ->
                writer.deprecateSpecAcceptanceCriterion(
                    requiredString(args, "specId"),
                    requiredString(args, "acceptanceCriterionId"),
                    requiredString(args, "status"),
                    requiredString(args, "reason"),
                    optionalString(args, "supersededBy"),
                    optionalString(args, "relatedAdr"),
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
            "deprecate_spec_constraint",
            "Mark one existing structured constraint as obsolete, superseded, or rejected without"
                + " deleting it.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "constraintId",
                    stringProperty("Constraint id"),
                    "status",
                    stringProperty("obsolete, superseded, or rejected"),
                    "reason",
                    stringProperty("Reason why the constraint is no longer applicable"),
                    "supersededBy",
                    stringProperty("Optional replacement constraint id"),
                    "relatedAdr",
                    stringProperty("Optional ADR id that records the decision"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "constraintId",
                "status",
                "reason"),
            args ->
                writer.deprecateSpecConstraint(
                    requiredString(args, "specId"),
                    requiredString(args, "constraintId"),
                    requiredString(args, "status"),
                    requiredString(args, "reason"),
                    optionalString(args, "supersededBy"),
                    optionalString(args, "relatedAdr"),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_repository_change",
            "Add or update the repository-scoped implementation plan for one affected repository"
                + " in an existing spec YAML.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "repositoryId",
                    stringProperty("Affected repository id"),
                    "role",
                    stringProperty("Repository role in this change"),
                    "summary",
                    stringProperty("Repository-specific implementation summary"),
                    "requirements",
                    stringArrayProperty("Requirement ids assigned to this repository"),
                    "acceptanceCriteria",
                    stringArrayProperty("Acceptance criterion ids assigned to this repository"),
                    "contractsProvided",
                    stringArrayProperty("Contracts this repository provides"),
                    "contractsConsumed",
                    stringArrayProperty("Contracts this repository consumes"),
                    "outOfScope",
                    stringArrayProperty("Repository-specific out-of-scope boundaries"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "repositoryId",
                "summary"),
            args ->
                writer.upsertSpecRepositoryChange(
                    requiredString(args, "specId"),
                    repositoryChange(args),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_affected_component",
            "Add or update one affected component or file breadcrumb in an existing spec YAML.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "repositoryId",
                    stringProperty("Repository id"),
                    "componentId",
                    stringProperty("Optional component id"),
                    "path",
                    stringProperty("Optional file path"),
                    "lineStart",
                    integerProperty("Optional start line"),
                    "lineEnd",
                    integerProperty("Optional end line"),
                    "role",
                    stringProperty("modify, create, delete, or read"),
                    "note",
                    stringProperty("Optional note"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "repositoryId"),
            args ->
                writer.upsertSpecAffectedComponent(
                    requiredString(args, "specId"), componentRef(args), bool(args.get("dryRun")))),
        tool(
            "update_spec_status",
            "Update an existing spec status without rewriting the full spec.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "status",
                    stringProperty("New spec status"),
                    "note",
                    stringProperty("Optional status change note"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "status"),
            args ->
                writer.updateSpecStatus(
                    requiredString(args, "specId"),
                    requiredString(args, "status"),
                    optionalString(args, "note"),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_metadata",
            "Add or update planning metadata for an existing spec.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "priority",
                    stringProperty("Optional priority"),
                    "effortHours",
                    numberProperty("Optional effort in hours"),
                    "sprint",
                    stringProperty("Optional sprint"),
                    "phase",
                    stringProperty("Optional phase"),
                    "tags",
                    stringArrayProperty("Optional tags"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId"),
            args ->
                writer.upsertSpecMetadata(
                    requiredString(args, "specId"), specMetadata(args), bool(args.get("dryRun")))),
        tool(
            "upsert_spec_summary",
            "Update existing spec summary fields such as title, owner, problem, or businessGoal.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "title",
                    stringProperty("Optional title"),
                    "owner",
                    stringProperty("Optional owner"),
                    "problem",
                    stringProperty("Optional problem statement"),
                    "businessGoal",
                    stringProperty("Optional business goal"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId"),
            args ->
                writer.upsertSpecSummary(
                    requiredString(args, "specId"),
                    optionalString(args, "title"),
                    optionalString(args, "owner"),
                    optionalString(args, "problem"),
                    optionalString(args, "businessGoal"),
                    bool(args.get("dryRun")))),
        tool(
            "append_spec_change",
            "Append or update one structured change log entry in an existing spec.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "id",
                    stringProperty("Change id"),
                    "date",
                    stringProperty("Change date"),
                    "summary",
                    stringProperty("Short change summary"),
                    "reason",
                    stringProperty("Reason for the change"),
                    "relatedAdr",
                    stringProperty("Optional related ADR id"),
                    "changedBy",
                    stringProperty("Optional author or agent identifier"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "id",
                "date",
                "summary",
                "reason"),
            args ->
                writer.appendSpecChange(
                    requiredString(args, "specId"), changeLogEntry(args), bool(args.get("dryRun")))),
        tool(
            "supersede_spec",
            "Mark one existing spec as superseded by another existing spec and link both specs.",
            strictObjectSchema(
                Map.of(
                    "oldSpecId",
                    stringProperty("Superseded spec id"),
                    "newSpecId",
                    stringProperty("Replacement spec id"),
                    "reason",
                    stringProperty("Reason for superseding"),
                    "relatedAdr",
                    stringProperty("Optional ADR id that records the decision"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "oldSpecId",
                "newSpecId",
                "reason"),
            args ->
                writer.supersedeSpec(
                    requiredString(args, "oldSpecId"),
                    requiredString(args, "newSpecId"),
                    requiredString(args, "reason"),
                    optionalString(args, "relatedAdr"),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_related_adr",
            "Add or update one structured ADR relation on a spec.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "adrId",
                    stringProperty("Related ADR id"),
                    "type",
                    stringProperty("Relation type"),
                    "note",
                    stringProperty("Optional relation note"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "adrId",
                "type"),
            args ->
                writer.upsertSpecRelatedAdr(
                    requiredString(args, "specId"),
                    requiredString(args, "adrId"),
                    requiredString(args, "type"),
                    optionalString(args, "note"),
                    bool(args.get("dryRun")))),
        tool(
            "deprecate_spec_related_adr",
            "Mark one structured spec-to-ADR relation as deprecated.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "adrId",
                    stringProperty("Related ADR id"),
                    "reason",
                    stringProperty("Reason for deprecating the relation"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "adrId",
                "reason"),
            args ->
                writer.deprecateSpecRelatedAdr(
                    requiredString(args, "specId"),
                    requiredString(args, "adrId"),
                    requiredString(args, "reason"),
                    bool(args.get("dryRun")))),
        tool(
            "upsert_spec_related_spec",
            "Add or update one structured relation between specs.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "relatedSpecId",
                    stringProperty("Related spec id"),
                    "type",
                    stringProperty("Relation type"),
                    "note",
                    stringProperty("Optional relation note"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "relatedSpecId",
                "type"),
            args ->
                writer.upsertSpecRelatedSpec(
                    requiredString(args, "specId"),
                    requiredString(args, "relatedSpecId"),
                    requiredString(args, "type"),
                    optionalString(args, "note"),
                    bool(args.get("dryRun")))),
        tool(
            "deprecate_spec_related_spec",
            "Mark one structured spec-to-spec relation as deprecated.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "relatedSpecId",
                    stringProperty("Related spec id"),
                    "reason",
                    stringProperty("Reason for deprecating the relation"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "specId",
                "relatedSpecId",
                "reason"),
            args ->
                writer.deprecateSpecRelatedSpec(
                    requiredString(args, "specId"),
                    requiredString(args, "relatedSpecId"),
                    requiredString(args, "reason"),
                    bool(args.get("dryRun")))),
        tool(
            "create_adr",
            "Create a new ADR YAML file under adrs/ using structured, validated input.",
            strictObjectSchema(
                adrSchemaProperties(), "id", "title", "status", "date", "context", "decision"),
            args -> writer.createAdr(adr(args), bool(args.get("dryRun")))),
        tool(
            "upsert_adr",
            "Create or update an ADR YAML file under adrs/ using structured, validated input.",
            strictObjectSchema(
                adrSchemaProperties(), "id", "title", "status", "date", "context", "decision"),
            args -> writer.upsertAdr(adr(args), bool(args.get("dryRun")))),
        tool(
            "upsert_adr_consequence",
            "Add one consequence to an existing ADR without rewriting the full ADR.",
            strictObjectSchema(
                Map.of(
                    "adrId",
                    stringProperty("ADR id"),
                    "consequence",
                    stringProperty("ADR consequence"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "adrId",
                "consequence"),
            args ->
                writer.upsertAdrConsequence(
                    requiredString(args, "adrId"),
                    requiredString(args, "consequence"),
                    bool(args.get("dryRun")))),
        tool(
            "update_adr_status",
            "Update an existing ADR status without rewriting the full ADR.",
            strictObjectSchema(
                Map.of(
                    "adrId",
                    stringProperty("ADR id"),
                    "status",
                    stringProperty("proposed, accepted, deprecated, or superseded"),
                    "supersededBy",
                    stringProperty("Optional replacement ADR id"),
                    "note",
                    stringProperty("Optional status change note"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "adrId",
                "status"),
            args ->
                writer.updateAdrStatus(
                    requiredString(args, "adrId"),
                    requiredString(args, "status"),
                    optionalString(args, "supersededBy"),
                    optionalString(args, "note"),
                    bool(args.get("dryRun")))),
        tool(
            "append_adr_change",
            "Append or update one structured change log entry in an existing ADR.",
            strictObjectSchema(
                Map.of(
                    "adrId",
                    stringProperty("ADR id"),
                    "id",
                    stringProperty("Change id"),
                    "date",
                    stringProperty("Change date"),
                    "summary",
                    stringProperty("Short change summary"),
                    "reason",
                    stringProperty("Reason for the change"),
                    "relatedAdr",
                    stringProperty("Optional related ADR id"),
                    "changedBy",
                    stringProperty("Optional author or agent identifier"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "adrId",
                "id",
                "date",
                "summary",
                "reason"),
            args ->
                writer.appendAdrChange(
                    requiredString(args, "adrId"), changeLogEntry(args), bool(args.get("dryRun")))),
        tool(
            "supersede_adr",
            "Mark one existing ADR as superseded by another existing ADR and link both ADRs.",
            strictObjectSchema(
                Map.of(
                    "oldAdrId",
                    stringProperty("Superseded ADR id"),
                    "newAdrId",
                    stringProperty("Replacement ADR id"),
                    "reason",
                    stringProperty("Reason for superseding"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "oldAdrId",
                "newAdrId",
                "reason"),
            args ->
                writer.supersedeAdr(
                    requiredString(args, "oldAdrId"),
                    requiredString(args, "newAdrId"),
                    requiredString(args, "reason"),
                    bool(args.get("dryRun")))),
        tool(
            "create_implementation_review",
            "Create a traceable implementation review for one spec, repository, and Git commit.",
            strictObjectSchema(
                implementationReviewSchemaProperties(),
                "id",
                "specId",
                "repositoryId",
                "commit",
                "reviewer",
                "reviewDate",
                "status",
                "summary"),
            args ->
                writer.createImplementationReview(
                    implementationReview(args), bool(args.get("dryRun")))),
        tool(
            "upsert_review_finding",
            "Add or update one structured implementation review finding.",
            strictObjectSchema(
                reviewFindingSchemaProperties(),
                "reviewId",
                "id",
                "type",
                "severity",
                "status",
                "title",
                "description"),
            args ->
                writer.upsertReviewFinding(
                    requiredString(args, "reviewId"),
                    reviewFinding(args),
                    bool(args.get("dryRun")))),
        tool(
            "update_review_finding_status",
            "Update a review finding status and record how it was resolved, including ADR or"
                + " constraint links created from the review.",
            strictObjectSchema(
                Map.ofEntries(
                    Map.entry("reviewId", stringProperty("Implementation review id")),
                    Map.entry("findingId", stringProperty("Review finding id")),
                    Map.entry(
                        "status",
                        stringProperty(
                            "open, acknowledged, in-progress, resolved, wont-fix, or dismissed")),
                    Map.entry("resolutionSummary", stringProperty("Resolution summary")),
                    Map.entry("resolvedDate", stringProperty("Resolution date")),
                    Map.entry("resolvedBy", stringProperty("Resolver or agent identifier")),
                    Map.entry("relatedAdr", stringProperty("ADR created or used by the resolution")),
                    Map.entry(
                        "relatedConstraint",
                        stringProperty("Spec constraint created or used by the resolution")),
                    Map.entry("dryRun", booleanProperty("Validate and preview without writing"))),
                "reviewId",
                "findingId",
                "status"),
            args ->
                writer.updateReviewFindingStatus(
                    requiredString(args, "reviewId"),
                    requiredString(args, "findingId"),
                    requiredString(args, "status"),
                    findingResolution(args),
                    bool(args.get("dryRun")))),
        tool(
            "update_implementation_review_status",
            "Update the lifecycle status of an implementation review.",
            strictObjectSchema(
                Map.of(
                    "reviewId",
                    stringProperty("Implementation review id"),
                    "status",
                    stringProperty("draft, in-progress, changes-requested, approved, or closed"),
                    "note",
                    stringProperty("Optional status note"),
                    "dryRun",
                    booleanProperty("Validate and preview without writing")),
                "reviewId",
                "status"),
            args ->
                writer.updateImplementationReviewStatus(
                    requiredString(args, "reviewId"),
                    requiredString(args, "status"),
                    optionalString(args, "note"),
                    bool(args.get("dryRun")))),
        tool(
            "get_implementation_review",
            "Return one complete implementation review by id.",
            strictObjectSchema(Map.of("reviewId", stringProperty("Implementation review id")), "reviewId"),
            args -> svc.getImplementationReview(requiredString(args, "reviewId"))),
        tool(
            "list_implementation_reviews",
            "List implementation reviews with optional spec, repository, and status filters.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Optional spec id"),
                    "repositoryId",
                    stringProperty("Optional repository id"),
                    "status",
                    stringProperty("Optional review status")),
                List.of()),
            args ->
                svc.listImplementationReviews(
                    optionalString(args, "specId"),
                    optionalString(args, "repositoryId"),
                    optionalString(args, "status"))),
        tool(
            "get_review_briefing_for_developer",
            "Return a compact correction briefing with actionable findings and applicable"
                + " architecture context for one implementation review.",
            strictObjectSchema(
                Map.of(
                    "reviewId",
                    stringProperty("Implementation review id"),
                    "includeResolved",
                    booleanProperty("Include resolved, dismissed, and wont-fix findings")),
                "reviewId"),
            args ->
                svc.getReviewBriefingForDeveloper(
                    requiredString(args, "reviewId"), bool(args.get("includeResolved")))),
        tool(
            "validate_workspace",
            "Validate repository references, component references, active spec readiness, related"
                + " ADR references, and supported schema versions without writing files.",
            strictObjectSchema(
                Map.of("strict", booleanProperty("Treat warnings such as missing ADRs as errors")),
                List.of()),
            args -> writer.validateWorkspace(bool(args.get("strict")))),
        tool(
            "validate_spec_repository_coverage",
            "Validate repositoryChanges coverage for one spec, including affected repositories,"
                + " assigned requirements, and assigned acceptance criteria.",
            strictObjectSchema(
                Map.of(
                    "specId",
                    stringProperty("Spec id"),
                    "strict",
                    booleanProperty("Treat coverage warnings as errors")),
                "specId"),
            args ->
                writer.validateSpecRepositoryCoverage(
                    requiredString(args, "specId"), bool(args.get("strict")))));
  }

  List<McpServerFeatures.SyncPromptSpecification> promptSpecifications() {
    return List.of(
        prompt(
            "create_spec",
            "create spec",
            "Create a new ArchContext spec YAML with schemaVersion, spec metadata, requirements,"
                + " acceptance criteria, constraints, repositoryChanges, contracts, and related"
                + " ADRs. Use concise IDs and assign work explicitly to affected repositories."),
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
                + " context. Keep work scoped by repositoryChange, contract, and acceptance"
                + " criterion."),
        prompt(
            "validate_implementation_against_spec",
            "validate implementation against spec",
            "Validate the implementation against the spec acceptance criteria, constraints,"
                + " repositoryChanges, contracts, related ADRs, and applicable guidelines. Report"
                + " gaps and risks."),
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
    String content = serializeRequest(() -> svc.readResource(request.uri()));
    return new McpSchema.ReadResourceResult(
        List.of(
            new McpSchema.TextResourceContents(
                request.uri(), JSON_MIME_TYPE, content)));
  }

  private McpServerFeatures.SyncToolSpecification tool(
      String name, String description, Map<String, Object> inputSchema, ToolHandler handler) {
    McpSchema.Tool tool =
        McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(inputSchema)
            .build();
    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(tool)
        .callHandler((exchange, request) -> callTool(handler, request.arguments()))
        .build();
  }

  private McpSchema.CallToolResult callTool(ToolHandler handler, Map<String, Object> arguments) {
    try {
      Object data = serializeRequest(() -> handler.call(arguments == null ? Map.of() : arguments));
      String content = Json.write(data);
      boolean isToolError =
          (data instanceof WriteResult result && !result.validation().errors().isEmpty())
              || (data instanceof WriteValidation validation && !validation.errors().isEmpty());
      McpSchema.CallToolResult.Builder builder =
          McpSchema.CallToolResult.builder()
              .content(List.of(new McpSchema.TextContent(content)))
              .isError(isToolError);
      if (shouldIncludeStructuredContent(content)) {
        builder.structuredContent(Map.of("data", Json.MAPPER.convertValue(data, Object.class)));
      }
      return builder.build();
    } catch (IllegalArgumentException e) {
      return McpSchema.CallToolResult.builder()
          .content(List.of(new McpSchema.TextContent(e.getMessage())))
          .isError(true)
          .build();
    }
  }

  <T> T serializeRequest(Supplier<T> work) {
    synchronized (requestLock) {
      return work.get();
    }
  }

  static boolean shouldIncludeStructuredContent(String content) {
    return content.length() <= STRUCTURED_CONTENT_MAX_CHARS;
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

  private static Map<String, Object> integerProperty(String description) {
    return Map.of("type", "integer", "description", description);
  }

  private static Map<String, Object> numberProperty(String description) {
    return Map.of("type", "number", "description", description);
  }

  private static Map<String, Object> arrayProperty(String description) {
    return Map.of("type", "array", "items", Map.of("type", "object"), "description", description);
  }

  private static Map<String, Object> stringArrayProperty(String description) {
    return Map.of("type", "array", "items", Map.of("type", "string"), "description", description);
  }

  private static Map<String, Object> adrSchemaProperties() {
    return Map.ofEntries(
        Map.entry("id", stringProperty("ADR id")),
        Map.entry("title", stringProperty("ADR title")),
        Map.entry("status", stringProperty("ADR status")),
        Map.entry("date", stringProperty("ADR date")),
        Map.entry("context", stringProperty("ADR context")),
        Map.entry("decision", stringProperty("ADR decision")),
        Map.entry("supersededBy", stringProperty("Replacement ADR id")),
        Map.entry("statusNote", stringProperty("ADR status note")),
        Map.entry("consequences", stringArrayProperty("ADR consequences")),
        Map.entry("affectedRepositories", stringArrayProperty("Affected repository ids")),
        Map.entry("relatedSpecs", stringArrayProperty("Related spec ids")),
        Map.entry("changeLog", arrayProperty("Structured ADR change log")),
        Map.entry("supersedes", stringArrayProperty("Superseded ADR ids")),
        Map.entry("dryRun", booleanProperty("Validate and preview without writing")));
  }

  private static Map<String, Object> implementationReviewSchemaProperties() {
    return Map.ofEntries(
        Map.entry("id", stringProperty("Implementation review id")),
        Map.entry("specId", stringProperty("Reviewed spec id")),
        Map.entry("repositoryId", stringProperty("Reviewed repository id")),
        Map.entry("branch", stringProperty("Reviewed Git branch")),
        Map.entry("commit", stringProperty("Reviewed Git commit")),
        Map.entry("reviewer", stringProperty("Reviewer or agent identifier")),
        Map.entry("reviewDate", stringProperty("Review date")),
        Map.entry("status", stringProperty("Implementation review status")),
        Map.entry("summary", stringProperty("Review summary")),
        Map.entry("statusNote", stringProperty("Optional review status note")),
        Map.entry("findings", arrayProperty("Structured review findings")),
        Map.entry("dryRun", booleanProperty("Validate and preview without writing")));
  }

  private static Map<String, Object> reviewFindingSchemaProperties() {
    return Map.ofEntries(
        Map.entry("reviewId", stringProperty("Implementation review id")),
        Map.entry("id", stringProperty("Review finding id")),
        Map.entry("type", stringProperty("Finding type, such as defect or constraint-required")),
        Map.entry("severity", stringProperty("blocker, critical, major, minor, or info")),
        Map.entry("category", stringProperty("Finding category")),
        Map.entry("status", stringProperty("Review finding status")),
        Map.entry("title", stringProperty("Finding title")),
        Map.entry("description", stringProperty("Finding description")),
        Map.entry("evidence", arrayProperty("Code paths and line ranges supporting the finding")),
        Map.entry("relatedRequirements", stringArrayProperty("Related requirement ids")),
        Map.entry(
            "relatedAcceptanceCriteria", stringArrayProperty("Related acceptance criterion ids")),
        Map.entry("relatedConstraints", stringArrayProperty("Related structured constraint ids")),
        Map.entry("relatedAdrs", stringArrayProperty("Related ADR ids")),
        Map.entry("recommendation", stringProperty("Recommended correction")),
        Map.entry(
            "proposedActions",
            arrayProperty("Proposed context actions such as create-adr or add-constraint")),
        Map.entry(
            "resolution",
            strictObjectSchema(
                Map.of(
                    "summary",
                    stringProperty("Resolution summary"),
                    "resolvedDate",
                    stringProperty("Resolution date"),
                    "resolvedBy",
                    stringProperty("Resolver or agent identifier"),
                    "relatedAdr",
                    stringProperty("Related ADR id"),
                    "relatedConstraint",
                    stringProperty("Related constraint id")),
                List.of())),
        Map.entry("dryRun", booleanProperty("Validate and preview without writing")));
  }

  private static Map<String, Object> solutionSchemaProperties() {
    return Map.ofEntries(
        Map.entry("id", stringProperty("Solution id")),
        Map.entry("name", stringProperty("Solution name")),
        Map.entry("description", stringProperty("Solution description")),
        Map.entry("vision", stringProperty("Solution vision")),
        Map.entry("principles", arrayProperty("Solution principles")),
        Map.entry("crossCuttingConcerns", arrayProperty("Cross-cutting concerns")),
        Map.entry("glossary", arrayProperty("Glossary terms")),
        Map.entry("dryRun", booleanProperty("Validate and preview without writing")));
  }

  private static Map<String, Object> guidelineSchemaProperties() {
    return Map.ofEntries(
        Map.entry("id", stringProperty("Guideline id")),
        Map.entry("title", stringProperty("Guideline title")),
        Map.entry("category", stringProperty("Guideline category")),
        Map.entry("appliesTo", Map.of("type", "object", "description", "Guideline scope")),
        Map.entry("rules", arrayProperty("Guideline rules")),
        Map.entry("references", stringArrayProperty("External references")),
        Map.entry("relatedAdrs", stringArrayProperty("Related ADR ids")),
        Map.entry("relatedSpecs", stringArrayProperty("Related spec ids")),
        Map.entry("dryRun", booleanProperty("Validate and preview without writing")));
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

  private static Integer optionalInt(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value instanceof Number n) return n.intValue();
    if (value == null || value.toString().isBlank()) return null;
    return Integer.valueOf(value.toString());
  }

  private static int optionalIntOrDefault(Map<String, Object> args, String name, int defaultValue) {
    Integer value = optionalInt(args, name);
    return value == null ? defaultValue : value;
  }

  private static Double optionalDouble(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value instanceof Number n) return n.doubleValue();
    if (value == null || value.toString().isBlank()) return null;
    return Double.valueOf(value.toString());
  }

  private static Solution solution(Map<String, Object> args) {
    return new Solution(
        requiredString(args, "id"),
        requiredString(args, "name"),
        optionalString(args, "description"),
        optionalString(args, "vision"),
        list(args.get("crossCuttingConcerns"), CrossCuttingConcern.class),
        list(args.get("glossary"), GlossaryTerm.class));
  }

  private static Principle principle(Map<String, Object> args) {
    return new Principle(
        requiredString(args, "id"),
        requiredString(args, "title"),
        requiredString(args, "description"),
        optionalString(args, "rationale"),
        stringList(args.get("appliesTo")));
  }

  private static GlossaryTerm glossaryTerm(Map<String, Object> args) {
    return new GlossaryTerm(
        requiredString(args, "term"),
        requiredString(args, "definition"),
        stringList(args.get("aliases")),
        stringList(args.get("relatedTerms")));
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

  private static Component component(Map<String, Object> args) {
    return new Component(
        requiredString(args, "componentId"),
        requiredString(args, "name"),
        requiredString(args, "type"),
        optionalString(args, "path"),
        optionalString(args, "description"),
        stringList(args.get("responsibilities")),
        stringList(args.get("dependsOn")));
  }

  private static Responsibility responsibility(Map<String, Object> args) {
    return new Responsibility(
        requiredString(args, "id"),
        requiredString(args, "description"),
        optionalString(args, "category"));
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
        list(args.get("repositoryChanges"), RepositoryChange.class),
        object(args.get("metadata"), SpecMetadata.class),
        list(args.get("changeLog"), ChangeLogEntry.class),
        optionalString(args, "supersededBy"),
        stringList(args.get("supersedes")),
        optionalString(args, "statusNote"),
        list(args.get("relatedSpecs"), SpecRelation.class),
        list(args.get("relatedAdrLinks"), AdrRelation.class),
        stringList(args.get("relatedAdrs")),
        null);
  }

  private static ChangeLogEntry changeLogEntry(Map<String, Object> args) {
    return new ChangeLogEntry(
        requiredString(args, "id"),
        requiredString(args, "date"),
        requiredString(args, "summary"),
        requiredString(args, "reason"),
        optionalString(args, "relatedAdr"),
        optionalString(args, "changedBy"));
  }

  private static RepositoryChange repositoryChange(Map<String, Object> args) {
    return new RepositoryChange(
        requiredString(args, "repositoryId"),
        optionalString(args, "role"),
        requiredString(args, "summary"),
        stringList(args.get("requirements")),
        stringList(args.get("acceptanceCriteria")),
        stringList(args.get("contractsProvided")),
        stringList(args.get("contractsConsumed")),
        stringList(args.get("outOfScope")));
  }

  private static ComponentRef componentRef(Map<String, Object> args) {
    return new ComponentRef(
        requiredString(args, "repositoryId"),
        optionalString(args, "componentId"),
        optionalString(args, "path"),
        optionalInt(args, "lineStart"),
        optionalInt(args, "lineEnd"),
        optionalString(args, "role"),
        optionalString(args, "note"));
  }

  private static SpecMetadata specMetadata(Map<String, Object> args) {
    return new SpecMetadata(
        optionalString(args, "priority"),
        optionalDouble(args, "effortHours"),
        optionalString(args, "sprint"),
        optionalString(args, "phase"),
        stringList(args.get("tags")));
  }

  private static Guideline guideline(Map<String, Object> args) {
    return new Guideline(
        requiredString(args, "id"),
        requiredString(args, "title"),
        optionalString(args, "category"),
        object(args.get("appliesTo"), AppliesTo.class),
        list(args.get("rules"), GuidelineRule.class),
        stringList(args.get("references")),
        stringList(args.get("relatedAdrs")),
        stringList(args.get("relatedSpecs")),
        null);
  }

  private static Adr adr(Map<String, Object> args) {
    return new Adr(
        requiredString(args, "id"),
        requiredString(args, "title"),
        requiredString(args, "status"),
        requiredString(args, "date"),
        requiredString(args, "context"),
        requiredString(args, "decision"),
        optionalString(args, "supersededBy"),
        optionalString(args, "statusNote"),
        stringList(args.get("consequences")),
        stringList(args.get("affectedRepositories")),
        stringList(args.get("relatedSpecs")),
        list(args.get("changeLog"), ChangeLogEntry.class),
        stringList(args.get("supersedes")),
        null);
  }

  private static ImplementationReview implementationReview(Map<String, Object> args) {
    return new ImplementationReview(
        requiredString(args, "id"),
        requiredString(args, "specId"),
        requiredString(args, "repositoryId"),
        optionalString(args, "branch"),
        requiredString(args, "commit"),
        requiredString(args, "reviewer"),
        requiredString(args, "reviewDate"),
        requiredString(args, "status"),
        requiredString(args, "summary"),
        optionalString(args, "statusNote"),
        list(args.get("findings"), ReviewFinding.class),
        null);
  }

  private static ReviewFinding reviewFinding(Map<String, Object> args) {
    return new ReviewFinding(
        requiredString(args, "id"),
        requiredString(args, "type"),
        requiredString(args, "severity"),
        optionalString(args, "category"),
        requiredString(args, "status"),
        requiredString(args, "title"),
        requiredString(args, "description"),
        list(args.get("evidence"), ReviewEvidence.class),
        stringList(args.get("relatedRequirements")),
        stringList(args.get("relatedAcceptanceCriteria")),
        stringList(args.get("relatedConstraints")),
        stringList(args.get("relatedAdrs")),
        optionalString(args, "recommendation"),
        list(args.get("proposedActions"), ProposedReviewAction.class),
        object(args.get("resolution"), FindingResolution.class));
  }

  private static FindingResolution findingResolution(Map<String, Object> args) {
    String summary = optionalString(args, "resolutionSummary");
    String resolvedDate = optionalString(args, "resolvedDate");
    String resolvedBy = optionalString(args, "resolvedBy");
    String relatedAdr = optionalString(args, "relatedAdr");
    String relatedConstraint = optionalString(args, "relatedConstraint");
    if (summary == null
        && resolvedDate == null
        && resolvedBy == null
        && relatedAdr == null
        && relatedConstraint == null) return null;
    return new FindingResolution(
        summary, resolvedDate, resolvedBy, relatedAdr, relatedConstraint);
  }

  private static <T> List<T> list(Object value, Class<T> type) {
    if (!(value instanceof List<?>)) return List.of();
    JavaType listType = Json.MAPPER.getTypeFactory().constructCollectionType(List.class, type);
    return Json.MAPPER.convertValue(value, listType);
  }

  private static <T> T object(Object value, Class<T> type) {
    return value == null ? null : Json.MAPPER.convertValue(value, type);
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
