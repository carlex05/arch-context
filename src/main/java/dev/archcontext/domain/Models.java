package dev.archcontext.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Models {
  private Models() {}

  public record ArchContextWorkspace(Path root, Path archContextDir) {}

  public record Solution(String id, String name, String description) {}

  public record Principle(String id, String title, String description) {}

  public record RepositoryDefinition(
      String id,
      String name,
      String path,
      String type,
      String language,
      String boundedContext,
      String description) {}

  public record LocalRepositoryOverride(String path) {}

  public record Requirement(String id, String description) {}

  public record AcceptanceCriterion(String id, String description) {}

  public record Spec(
      String id,
      String title,
      String status,
      String owner,
      String problem,
      String businessGoal,
      List<String> affectedRepositories,
      List<String> affectedBoundedContexts,
      List<Requirement> functionalRequirements,
      List<Requirement> nonFunctionalRequirements,
      List<AcceptanceCriterion> acceptanceCriteria,
      List<String> constraints,
      List<String> relatedAdrs,
      String sourcePath) {}

  public record Adr(
      String id,
      String title,
      String status,
      String date,
      String context,
      String decision,
      List<String> consequences,
      List<String> affectedRepositories,
      List<String> relatedSpecs,
      String sourcePath) {}

  public record Guideline(
      String id, String title, AppliesTo appliesTo, List<GuidelineRule> rules, String sourcePath) {}

  public record AppliesTo(List<String> languages, List<String> repositoryTypes) {}

  public record GuidelineRule(String id, String description) {}

  public record ContextDocument(
      String type, String id, String title, String path, String content, String hash) {}

  public record DocumentChunk(
      long documentId,
      String documentType,
      String documentKey,
      String title,
      String path,
      String content,
      double score) {}

  public record RepositoryContext(
      RepositoryDefinition repository,
      List<Spec> specs,
      List<Adr> adrs,
      List<Guideline> guidelines,
      List<String> constraints) {}

  public record SolutionContext(
      Solution solution,
      List<Principle> principles,
      List<RepositoryDefinition> repositories,
      List<Spec> activeSpecs,
      List<Adr> acceptedAdrs) {}

  public record ValidationResult(
      List<String> missingSections, List<String> warnings, List<String> suggestions) {}

  public record ImplementationContext(
      Spec spec,
      List<RepositoryDefinition> affectedRepositories,
      RepositoryContext repositoryContext,
      List<Requirement> functionalRequirements,
      List<Requirement> nonFunctionalRequirements,
      List<AcceptanceCriterion> acceptanceCriteria,
      List<String> constraints,
      List<Adr> relatedAdrs,
      List<Guideline> applicableGuidelines) {}

  public record PromptTemplate(
      String name, String description, List<String> arguments, String template) {}

  public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {}
}
