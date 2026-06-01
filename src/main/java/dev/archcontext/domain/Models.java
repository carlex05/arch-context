package dev.archcontext.domain;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Models {
  private Models() {}

  public record ArchContextWorkspace(Path root, Path archContextDir) {}

  public record Solution(
      String id,
      String name,
      String description,
      String vision,
      List<CrossCuttingConcern> crossCuttingConcerns,
      List<GlossaryTerm> glossary) {
    public Solution {
      crossCuttingConcerns = crossCuttingConcerns == null ? List.of() : crossCuttingConcerns;
      glossary = glossary == null ? List.of() : glossary;
    }

    public Solution(String id, String name, String description) {
      this(id, name, description, null, List.of(), List.of());
    }
  }

  public record CrossCuttingConcern(String id, String title, String description) {}

  public record GlossaryTerm(
      String term, String definition, List<String> aliases, List<String> relatedTerms) {
    public GlossaryTerm {
      aliases = aliases == null ? List.of() : aliases;
      relatedTerms = relatedTerms == null ? List.of() : relatedTerms;
    }

    public GlossaryTerm(String term, String definition) {
      this(term, definition, List.of(), List.of());
    }
  }

  public record Principle(
      String id, String title, String description, String rationale, List<String> appliesTo) {
    public Principle {
      appliesTo = appliesTo == null ? List.of() : appliesTo;
    }

    public Principle(String id, String title, String description) {
      this(id, title, description, null, List.of());
    }
  }

  public record RepositoryDefinition(
      String id,
      String name,
      String path,
      String type,
      String language,
      String boundedContext,
      String description,
      List<Responsibility> responsibilities,
      List<Component> components) {
    public RepositoryDefinition {
      responsibilities = responsibilities == null ? List.of() : responsibilities;
      components = components == null ? List.of() : components;
    }

    public RepositoryDefinition(
        String id,
        String name,
        String path,
        String type,
        String language,
        String boundedContext,
        String description) {
      this(id, name, path, type, language, boundedContext, description, List.of(), List.of());
    }
  }

  public record Responsibility(String id, String description, String category) {
    public Responsibility(String id, String description) {
      this(id, description, null);
    }
  }

  public record Component(
      String id,
      String name,
      String type,
      String path,
      String description,
      List<String> responsibilities,
      List<String> dependsOn) {
    public Component {
      responsibilities = responsibilities == null ? List.of() : responsibilities;
      dependsOn = dependsOn == null ? List.of() : dependsOn;
    }

    public Component(
        String id, String name, String type, String description, List<String> responsibilities) {
      this(id, name, type, null, description, responsibilities, List.of());
    }
  }

  public record ComponentRef(
      String repositoryId,
      String componentId,
      String path,
      Integer lineStart,
      Integer lineEnd,
      String role,
      String note) {
    public ComponentRef(String repositoryId, String componentId) {
      this(repositoryId, componentId, null, null, null, null, null);
    }
  }

  public record LocalRepositoryOverride(String path) {}

  public record Requirement(String id, String description) {}

  public record AcceptanceCriterion(String id, String description) {}

  public record Constraint(String id, String title, String description) {}

  public record OutOfScopeItem(String description) {}

  public record OpenQuestion(String id, String question) {}

  public record SpecMetadata(
      String priority, Double effortHours, String sprint, String phase, List<String> tags) {
    public SpecMetadata {
      tags = tags == null ? List.of() : tags;
    }
  }

  public record RepositoryChange(
      String repositoryId,
      String role,
      String summary,
      List<String> requirements,
      List<String> acceptanceCriteria,
      List<String> contractsProvided,
      List<String> contractsConsumed,
      List<String> outOfScope) {
    public RepositoryChange {
      requirements = requirements == null ? List.of() : requirements;
      acceptanceCriteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria;
      contractsProvided = contractsProvided == null ? List.of() : contractsProvided;
      contractsConsumed = contractsConsumed == null ? List.of() : contractsConsumed;
      outOfScope = outOfScope == null ? List.of() : outOfScope;
    }
  }

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
      List<Constraint> structuredConstraints,
      List<ComponentRef> affectedComponents,
      List<OutOfScopeItem> outOfScope,
      List<OpenQuestion> openQuestions,
      List<RepositoryChange> repositoryChanges,
      SpecMetadata metadata,
      List<String> relatedAdrs,
      String sourcePath) {
    public Spec {
      affectedRepositories = affectedRepositories == null ? List.of() : affectedRepositories;
      affectedBoundedContexts =
          affectedBoundedContexts == null ? List.of() : affectedBoundedContexts;
      functionalRequirements = functionalRequirements == null ? List.of() : functionalRequirements;
      nonFunctionalRequirements =
          nonFunctionalRequirements == null ? List.of() : nonFunctionalRequirements;
      acceptanceCriteria = acceptanceCriteria == null ? List.of() : acceptanceCriteria;
      constraints = constraints == null ? List.of() : constraints;
      structuredConstraints = structuredConstraints == null ? List.of() : structuredConstraints;
      affectedComponents = affectedComponents == null ? List.of() : affectedComponents;
      outOfScope = outOfScope == null ? List.of() : outOfScope;
      openQuestions = openQuestions == null ? List.of() : openQuestions;
      repositoryChanges = repositoryChanges == null ? List.of() : repositoryChanges;
      relatedAdrs = relatedAdrs == null ? List.of() : relatedAdrs;
    }

    public Spec(
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
        List<Constraint> structuredConstraints,
        List<ComponentRef> affectedComponents,
        List<OutOfScopeItem> outOfScope,
        List<OpenQuestion> openQuestions,
        List<String> relatedAdrs,
        String sourcePath) {
      this(
          id,
          title,
          status,
          owner,
          problem,
          businessGoal,
          affectedRepositories,
          affectedBoundedContexts,
          functionalRequirements,
          nonFunctionalRequirements,
          acceptanceCriteria,
          constraints,
          structuredConstraints,
          affectedComponents,
          outOfScope,
          openQuestions,
          List.of(),
          null,
          relatedAdrs,
          sourcePath);
    }

    public Spec(
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
        String sourcePath) {
      this(
          id,
          title,
          status,
          owner,
          problem,
          businessGoal,
          affectedRepositories,
          affectedBoundedContexts,
          functionalRequirements,
          nonFunctionalRequirements,
          acceptanceCriteria,
          constraints,
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          null,
          relatedAdrs,
          sourcePath);
    }
  }

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
      String id,
      String title,
      String category,
      AppliesTo appliesTo,
      List<GuidelineRule> rules,
      List<String> references,
      List<String> relatedAdrs,
      List<String> relatedSpecs,
      String sourcePath) {
    public Guideline {
      rules = rules == null ? List.of() : rules;
      references = references == null ? List.of() : references;
      relatedAdrs = relatedAdrs == null ? List.of() : relatedAdrs;
      relatedSpecs = relatedSpecs == null ? List.of() : relatedSpecs;
    }

    public Guideline(
        String id, String title, AppliesTo appliesTo, List<GuidelineRule> rules, String sourcePath) {
      this(id, title, null, appliesTo, rules, List.of(), List.of(), List.of(), sourcePath);
    }
  }

  public record AppliesTo(
      List<String> repositoryIds, List<String> languages, List<String> repositoryTypes) {
    public AppliesTo {
      repositoryIds = repositoryIds == null ? List.of() : repositoryIds;
      languages = languages == null ? List.of() : languages;
      repositoryTypes = repositoryTypes == null ? List.of() : repositoryTypes;
    }

    public AppliesTo(List<String> languages, List<String> repositoryTypes) {
      this(List.of(), languages, repositoryTypes);
    }
  }

  public record GuidelineRule(
      String id, String description, String statement, String rationale, RuleExamples examples) {
    public GuidelineRule(String id, String description) {
      this(id, description, null, null, null);
    }
  }

  public record RuleExamples(List<String> good, List<String> bad) {
    public RuleExamples {
      good = good == null ? List.of() : good;
      bad = bad == null ? List.of() : bad;
    }
  }

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

  public record WriteValidation(List<String> errors, List<String> warnings) {}

  public record WriteResult(
      boolean changed,
      boolean dryRun,
      List<String> updatedFiles,
      String summary,
      WriteValidation validation,
      Object object) {}

  public record ImplementationContext(
      Spec spec,
      List<RepositoryDefinition> affectedRepositories,
      RepositoryContext repositoryContext,
      List<Requirement> functionalRequirements,
      List<Requirement> nonFunctionalRequirements,
      List<AcceptanceCriterion> acceptanceCriteria,
      List<String> constraints,
      List<Constraint> structuredConstraints,
      List<Adr> relatedAdrs,
      List<Guideline> applicableGuidelines) {}

  public record RepositoryImplementationContext(
      Spec spec,
      RepositoryDefinition repository,
      RepositoryChange repositoryChange,
      List<RepositoryDefinition> otherAffectedRepositories,
      List<Requirement> applicableFunctionalRequirements,
      List<Requirement> applicableNonFunctionalRequirements,
      List<AcceptanceCriterion> applicableAcceptanceCriteria,
      List<String> constraints,
      List<Constraint> structuredConstraints,
      List<Adr> relatedAdrs,
      List<Guideline> applicableGuidelines) {}

  public record AgentBriefing(
      Solution solution,
      List<Principle> principles,
      List<GlossaryTerm> glossary,
      Spec spec,
      RepositoryDefinition repository,
      RepositoryChange repositoryChange,
      List<RepositoryDefinition> otherAffectedRepositories,
      List<ComponentRef> affectedComponents,
      List<Requirement> functionalRequirements,
      List<Requirement> nonFunctionalRequirements,
      List<AcceptanceCriterion> acceptanceCriteria,
      List<String> constraints,
      List<Constraint> structuredConstraints,
      List<Adr> relatedAdrs,
      List<Guideline> applicableGuidelines) {}

  public record PromptTemplate(
      String name, String description, List<String> arguments, String template) {}

  public record McpRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {}
}
