package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WorkspaceValidator {
  private static final Pattern KEBAB_CASE = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*");

  private final RepositoryService repositoryService = new RepositoryService();

  public WriteValidation validateRepository(RepositoryDefinition repository) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (blank(repository.id())) {
      errors.add("Repository id is required.");
    } else if (!KEBAB_CASE.matcher(repository.id()).matches()) {
      errors.add("Repository id must be lowercase kebab-case: " + repository.id());
    }
    if (blank(repository.name())) errors.add("Repository name is required.");
    if (blank(repository.type())) errors.add("Repository type is required.");
    if (blank(repository.language())) errors.add("Repository language is required.");
    if (blank(repository.path())) warnings.add("Repository path is missing.");
    return new WriteValidation(errors, warnings);
  }

  public WriteValidation validateSolution(Solution solution, List<Principle> principles) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (solution == null) {
      errors.add("Solution is required.");
      return new WriteValidation(errors, warnings);
    }
    if (blank(solution.id())) errors.add("Solution id is required.");
    if (blank(solution.name())) errors.add("Solution name is required.");
    if (blank(solution.description())) warnings.add("Solution description is missing.");
    requireUnique(
        nvl(principles).stream().map(Principle::id).toList(), "Duplicate principle id: ", errors);
    requireUnique(
        nvl(solution.crossCuttingConcerns()).stream().map(CrossCuttingConcern::id).toList(),
        "Duplicate cross-cutting concern id: ",
        errors);
    requireUnique(
        nvl(solution.glossary()).stream().map(GlossaryTerm::term).toList(),
        "Duplicate glossary term: ",
        errors);
    return new WriteValidation(errors, warnings);
  }

  public WriteValidation validateGuideline(Path root, Guideline guideline) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (blank(guideline.id())) errors.add("Guideline id is required.");
    if (blank(guideline.title())) errors.add("Guideline title is required.");
    requireUnique(
        nvl(guideline.rules()).stream().map(GuidelineRule::id).toList(),
        "Duplicate guideline rule id: ",
        errors);
    try {
      Set<String> repositoryIds =
          repositoryService.list(root).stream()
              .map(RepositoryDefinition::id)
              .collect(Collectors.toSet());
      AppliesTo appliesTo = guideline.appliesTo();
      if (appliesTo != null) {
        for (String repositoryId : nvl(appliesTo.repositoryIds())) {
          if (!"*".equals(repositoryId) && !repositoryIds.contains(repositoryId)) {
            errors.add("Unknown guideline repository: " + repositoryId);
          }
        }
      }
    } catch (IOException e) {
      errors.add("Cannot read repository definitions: " + e.getMessage());
    }
    if (nvl(guideline.rules()).isEmpty()) warnings.add("Guideline has no rules: " + guideline.id());
    return new WriteValidation(errors, warnings);
  }

  public WriteValidation validateWorkspace(Path root, boolean strict) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    validateSchemaVersions(root, strict, errors, warnings);
    try {
      Map<String, RepositoryDefinition> repositories =
          repositoryService.list(root).stream()
              .collect(Collectors.toMap(RepositoryDefinition::id, r -> r, (a, b) -> a));
      Set<String> adrIds = adrIds(root);
      List<Spec> workspaceSpecs = specs(root);
      Map<String, Spec> specsById =
          workspaceSpecs.stream().collect(Collectors.toMap(Spec::id, s -> s, (a, b) -> a));
      for (Spec spec : workspaceSpecs) {
        WriteValidation specValidation = validateSpec(spec, repositories);
        errors.addAll(specValidation.errors());
        warnings.addAll(specValidation.warnings());
        WriteValidation coverage = validateSpecRepositoryCoverage(root, spec, strict);
        errors.addAll(coverage.errors());
        warnings.addAll(coverage.warnings());
        for (String adrId : nvl(spec.relatedAdrs())) {
          if (!adrIds.contains(adrId)) {
            String message = "Unknown related ADR in spec " + spec.id() + ": " + adrId;
            if (strict) errors.add(message);
            else warnings.add(message);
          }
        }
        for (String repositoryId : nvl(spec.affectedRepositories())) {
          if (!repositories.containsKey(repositoryId)) {
            errors.add("Unknown affected repository in spec " + spec.id() + ": " + repositoryId);
          }
        }
        if ("active".equalsIgnoreCase(spec.status()) && nvl(spec.acceptanceCriteria()).isEmpty()) {
          warnings.add("Active spec has no acceptance criteria: " + spec.id());
        }
      }
      for (ImplementationReview review : implementationReviews(root)) {
        WriteValidation reviewValidation =
            validateImplementationReview(review, specsById, repositories.keySet(), adrIds);
        errors.addAll(reviewValidation.errors());
        warnings.addAll(reviewValidation.warnings());
      }
    } catch (IOException e) {
      errors.add("Cannot validate workspace: " + e.getMessage());
    }
    return new WriteValidation(distinct(errors), distinct(warnings));
  }

  public WriteValidation validateSpec(Path root, Spec spec) {
    try {
      Map<String, RepositoryDefinition> repositories =
          repositoryService.list(root).stream()
              .collect(Collectors.toMap(RepositoryDefinition::id, r -> r, (a, b) -> a));
      return validateSpec(spec, repositories);
    } catch (IOException e) {
      return new WriteValidation(
          List.of("Cannot read repository definitions: " + e.getMessage()), List.of());
    }
  }

  private WriteValidation validateSpec(
      Spec spec, Map<String, RepositoryDefinition> repositories) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (blank(spec.id())) errors.add("Spec id is required.");
    if (blank(spec.title())) errors.add("Spec title is required.");
    if (blank(spec.status())) errors.add("Spec status is required.");
    if (blank(spec.owner())) errors.add("Spec owner is required.");
    if (blank(spec.problem())) errors.add("Spec problem is required.");
    if (blank(spec.businessGoal())) errors.add("Spec businessGoal is required.");

    validateRepositoryRefs(spec, repositories, errors);
    validateComponentRefs(spec, repositories, errors);
    validateRepositoryChanges(spec, repositories, errors);

    return new WriteValidation(errors, warnings);
  }

  public WriteValidation validateAdr(Path root, Adr adr) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (blank(adr.id())) errors.add("ADR id is required.");
    if (blank(adr.title())) errors.add("ADR title is required.");
    if (blank(adr.status())) errors.add("ADR status is required.");
    if (blank(adr.date())) errors.add("ADR date is required.");
    if (blank(adr.context())) errors.add("ADR context is required.");
    if (blank(adr.decision())) errors.add("ADR decision is required.");

    try {
      Map<String, RepositoryDefinition> repositories =
          repositoryService.list(root).stream()
              .collect(Collectors.toMap(RepositoryDefinition::id, r -> r, (a, b) -> a));
      for (String repositoryId : nvl(adr.affectedRepositories())) {
        if (!repositories.containsKey(repositoryId)) {
          errors.add("Unknown affected repository in ADR " + adr.id() + ": " + repositoryId);
        }
      }
      Set<String> specIds = specs(root).stream().map(Spec::id).collect(Collectors.toSet());
      for (String specId : nvl(adr.relatedSpecs())) {
        if (!specIds.contains(specId)) {
          errors.add("Unknown related spec in ADR " + adr.id() + ": " + specId);
        }
      }
    } catch (IOException e) {
      errors.add("Cannot validate ADR references: " + e.getMessage());
    }

    return new WriteValidation(errors, warnings);
  }

  public WriteValidation validateImplementationReview(Path root, ImplementationReview review) {
    try {
      Map<String, Spec> specs =
          specs(root).stream().collect(Collectors.toMap(Spec::id, s -> s, (a, b) -> a));
      Set<String> repositoryIds =
          repositoryService.list(root).stream()
              .map(RepositoryDefinition::id)
              .collect(Collectors.toSet());
      return validateImplementationReview(review, specs, repositoryIds, adrIds(root));
    } catch (IOException e) {
      return new WriteValidation(
          List.of("Cannot validate implementation review references: " + e.getMessage()), List.of());
    }
  }

  private WriteValidation validateImplementationReview(
      ImplementationReview review,
      Map<String, Spec> specs,
      Set<String> repositoryIds,
      Set<String> adrIds) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (review == null) {
      return new WriteValidation(List.of("Implementation review is required."), List.of());
    }
    if (blank(review.id())) errors.add("Implementation review id is required.");
    if (blank(review.specId())) errors.add("Implementation review specId is required.");
    if (blank(review.repositoryId())) errors.add("Implementation review repositoryId is required.");
    if (blank(review.commit())) errors.add("Implementation review commit is required.");
    if (blank(review.reviewer())) errors.add("Implementation review reviewer is required.");
    if (blank(review.reviewDate())) errors.add("Implementation review reviewDate is required.");
    if (!Set.of("draft", "in-progress", "changes-requested", "approved", "closed")
        .contains(String.valueOf(review.status()).toLowerCase(Locale.ROOT))) {
      errors.add("Invalid implementation review status: " + review.status());
    }
    if (blank(review.summary())) warnings.add("Implementation review summary is missing.");
    requireUnique(
        nvl(review.findings()).stream().map(ReviewFinding::id).toList(),
        "Duplicate review finding id: ",
        errors);
    if ("approved".equalsIgnoreCase(review.status())
        && nvl(review.findings()).stream().anyMatch(ReviewFinding::actionable)) {
      errors.add("Approved implementation review cannot contain actionable findings: " + review.id());
    }

    Spec spec = specs.get(review.specId());
    if (spec == null) errors.add("Unknown specId: " + review.specId());
    if (!repositoryIds.contains(review.repositoryId())) {
      errors.add("Unknown repositoryId: " + review.repositoryId());
    } else if (spec != null && !nvl(spec.affectedRepositories()).contains(review.repositoryId())) {
      errors.add(
          "Review repository must be affected by spec "
              + review.specId()
              + ": "
              + review.repositoryId());
    }
    for (ReviewFinding finding : nvl(review.findings())) {
      validateReviewFinding(finding, spec, adrIds, errors, warnings);
    }
    return new WriteValidation(distinct(errors), distinct(warnings));
  }

  public WriteValidation validateSpecRepositoryCoverage(Path root, Spec spec, boolean strict) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<String> affectedRepositories = nvl(spec.affectedRepositories());
    List<RepositoryChange> repositoryChanges = nvl(spec.repositoryChanges());

    if (affectedRepositories.size() > 1 && repositoryChanges.isEmpty()) {
      add(
          strict,
          errors,
          warnings,
          "Multi-repository spec has no repositoryChanges: " + spec.id());
    }

    if (!repositoryChanges.isEmpty()) {
      Set<String> changedRepositories =
          repositoryChanges.stream()
              .map(RepositoryChange::repositoryId)
              .filter(id -> id != null && !id.isBlank())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      for (String repositoryId : affectedRepositories) {
        if (!changedRepositories.contains(repositoryId)) {
          add(
              strict,
              errors,
              warnings,
              "Affected repository has no repositoryChange in spec "
                  + spec.id()
                  + ": "
                  + repositoryId);
        }
      }

      Set<String> assignedRequirements =
          repositoryChanges.stream()
              .flatMap(c -> nvl(c.requirements()).stream())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      for (String requirementId : implementableRequirementIds(spec)) {
        if (!assignedRequirements.contains(requirementId)) {
          add(
              strict,
              errors,
              warnings,
              "Requirement is not assigned to a repositoryChange in spec "
                  + spec.id()
                  + ": "
                  + requirementId);
        }
      }

      Set<String> assignedAcceptanceCriteria =
          repositoryChanges.stream()
              .flatMap(c -> nvl(c.acceptanceCriteria()).stream())
              .collect(Collectors.toCollection(LinkedHashSet::new));
      for (String acceptanceCriterionId : implementableAcceptanceCriterionIds(spec)) {
        if (!assignedAcceptanceCriteria.contains(acceptanceCriterionId)) {
          add(
              strict,
              errors,
              warnings,
              "Acceptance criterion is not assigned to a repositoryChange in spec "
                  + spec.id()
                  + ": "
                  + acceptanceCriterionId);
        }
      }
    }

    return new WriteValidation(distinct(errors), distinct(warnings));
  }

  public WriteValidation validateSpecConsistency(Path root, String specId, boolean strict) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    try {
      Map<String, Spec> specs =
          specs(root).stream().collect(Collectors.toMap(Spec::id, s -> s, (a, b) -> a));
      Map<String, Adr> adrs =
          adrs(root).stream().collect(Collectors.toMap(Adr::id, a -> a, (a, b) -> a));
      Spec spec = specs.get(specId);
      if (spec == null) return new WriteValidation(List.of("Unknown specId: " + specId), List.of());

      validateSpecSuperseding(spec, specs, strict, errors, warnings);
      validateSpecRelations(spec, specs, adrs, strict, errors, warnings);
      validateRepositoryChangeConsistency(spec, strict, errors, warnings);
      validateAdrConsistency(spec, adrs, strict, errors, warnings);
    } catch (IOException e) {
      errors.add("Cannot validate spec consistency: " + e.getMessage());
    }
    return new WriteValidation(distinct(errors), distinct(warnings));
  }

  public void validateKnownWriteTarget(Path root, Path target) {
    Path archContextDir = root.resolve(".archcontext").toAbsolutePath().normalize();
    Path normalizedTarget = target.toAbsolutePath().normalize();
    if (!normalizedTarget.startsWith(archContextDir)) {
      throw new IllegalArgumentException(
          "Write target must stay under " + archContextDir + ": " + normalizedTarget);
    }
    if (Files.isSymbolicLink(archContextDir)) {
      throw new IllegalArgumentException(".archcontext must not be a symbolic link.");
    }
    Path parent = normalizedTarget.getParent();
    while (parent != null && parent.startsWith(archContextDir)) {
      if (Files.exists(parent) && Files.isSymbolicLink(parent)) {
        throw new IllegalArgumentException("Write parent must not be a symbolic link: " + parent);
      }
      if (parent.equals(archContextDir)) break;
      parent = parent.getParent();
    }
    if (Files.exists(normalizedTarget) && Files.isSymbolicLink(normalizedTarget)) {
      throw new IllegalArgumentException("Write target must not be a symbolic link: " + target);
    }

    Path repositoriesFile = archContextDir.resolve("repositories.yaml").normalize();
    Path solutionFile = archContextDir.resolve("solution.yaml").normalize();
    Path specsDir = archContextDir.resolve("specs").normalize();
    Path adrsDir = archContextDir.resolve("adrs").normalize();
    Path guidelinesDir = archContextDir.resolve("guidelines").normalize();
    Path reviewsDir = archContextDir.resolve("reviews").normalize();
    boolean knownRepositoriesFile = normalizedTarget.equals(repositoriesFile);
    boolean knownSolutionFile = normalizedTarget.equals(solutionFile);
    boolean knownSpecFile =
        normalizedTarget.getParent() != null
            && normalizedTarget.getParent().normalize().equals(specsDir)
            && normalizedTarget.getFileName().toString().endsWith(".yaml");
    boolean knownAdrFile =
        normalizedTarget.getParent() != null
            && normalizedTarget.getParent().normalize().equals(adrsDir)
            && normalizedTarget.getFileName().toString().endsWith(".yaml");
    boolean knownGuidelineFile =
        normalizedTarget.getParent() != null
            && normalizedTarget.getParent().normalize().equals(guidelinesDir)
            && normalizedTarget.getFileName().toString().endsWith(".yaml");
    boolean knownReviewFile =
        normalizedTarget.getParent() != null
            && normalizedTarget.getParent().normalize().equals(reviewsDir)
            && normalizedTarget.getFileName().toString().endsWith(".yaml");
    if (!knownRepositoriesFile
        && !knownSolutionFile
        && !knownSpecFile
        && !knownAdrFile
        && !knownGuidelineFile
        && !knownReviewFile) {
      throw new IllegalArgumentException("Unsupported ArchContext write target: " + target);
    }
  }

  private void validateRepositoryRefs(
      Spec spec, Map<String, RepositoryDefinition> repositories, List<String> errors) {
    for (String repositoryId : nvl(spec.affectedRepositories())) {
      if (!repositories.containsKey(repositoryId)) {
        errors.add("Unknown affected repository: " + repositoryId);
      }
    }
  }

  private void validateComponentRefs(
      Spec spec, Map<String, RepositoryDefinition> repositories, List<String> errors) {
    for (ComponentRef ref : nvl(spec.affectedComponents())) {
      RepositoryDefinition repository = repositories.get(ref.repositoryId());
      if (repository == null) {
        errors.add("Unknown component repository: " + ref.repositoryId());
        continue;
      }
      if (blank(ref.componentId()) && !blank(ref.path())) continue;
      boolean componentExists =
          nvl(repository.components()).stream().anyMatch(c -> c.id().equals(ref.componentId()));
      if (!componentExists) {
        errors.add("Unknown component reference: " + ref.repositoryId() + ":" + ref.componentId());
      }
    }
  }

  private void validateRepositoryChanges(
      Spec spec, Map<String, RepositoryDefinition> repositories, List<String> errors) {
    Set<String> seenRepositories = new LinkedHashSet<>();
    Set<String> requirementIds = requirementIds(spec);
    Set<String> acceptanceCriterionIds = acceptanceCriterionIds(spec);
    for (RepositoryChange change : nvl(spec.repositoryChanges())) {
      if (blank(change.repositoryId())) {
        errors.add("RepositoryChange repositoryId is required in spec " + spec.id() + ".");
        continue;
      }
      if (!seenRepositories.add(change.repositoryId())) {
        errors.add(
            "Duplicate repositoryChange in spec " + spec.id() + ": " + change.repositoryId());
      }
      if (!repositories.containsKey(change.repositoryId())) {
        errors.add("Unknown repositoryChange repository: " + change.repositoryId());
      }
      if (!nvl(spec.affectedRepositories()).contains(change.repositoryId())) {
        errors.add(
            "RepositoryChange repository must be listed in affectedRepositories: "
                + change.repositoryId());
      }
      for (String requirementId : nvl(change.requirements())) {
        if (!requirementIds.contains(requirementId)) {
          errors.add(
              "Unknown repositoryChange requirement in spec "
                  + spec.id()
                  + ": "
                  + requirementId);
        }
      }
      for (String acceptanceCriterionId : nvl(change.acceptanceCriteria())) {
        if (!acceptanceCriterionIds.contains(acceptanceCriterionId)) {
          errors.add(
              "Unknown repositoryChange acceptance criterion in spec "
                  + spec.id()
                  + ": "
                  + acceptanceCriterionId);
        }
      }
    }
  }

  private Set<String> requirementIds(Spec spec) {
    Set<String> ids = new LinkedHashSet<>();
    for (Requirement requirement : nvl(spec.functionalRequirements())) ids.add(requirement.id());
    for (Requirement requirement : nvl(spec.nonFunctionalRequirements())) ids.add(requirement.id());
    return ids;
  }

  private Set<String> implementableRequirementIds(Spec spec) {
    Set<String> ids = new LinkedHashSet<>();
    for (Requirement requirement : nvl(spec.functionalRequirements())) {
      if (requirement.implementable()) ids.add(requirement.id());
    }
    for (Requirement requirement : nvl(spec.nonFunctionalRequirements())) {
      if (requirement.implementable()) ids.add(requirement.id());
    }
    return ids;
  }

  private Set<String> acceptanceCriterionIds(Spec spec) {
    return nvl(spec.acceptanceCriteria()).stream()
        .map(AcceptanceCriterion::id)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private Set<String> implementableAcceptanceCriterionIds(Spec spec) {
    return nvl(spec.acceptanceCriteria()).stream()
        .filter(AcceptanceCriterion::implementable)
        .map(AcceptanceCriterion::id)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private void validateSpecSuperseding(
      Spec spec,
      Map<String, Spec> specs,
      boolean strict,
      List<String> errors,
      List<String> warnings) {
    if (!blank(spec.supersededBy()) && !specs.containsKey(spec.supersededBy())) {
      errors.add("Spec " + spec.id() + " supersededBy points to unknown spec: " + spec.supersededBy());
    }
    for (String supersedes : nvl(spec.supersedes())) {
      if (!specs.containsKey(supersedes)) {
        errors.add("Spec " + spec.id() + " supersedes unknown spec: " + supersedes);
      }
    }
    if (!blank(spec.supersededBy()) && !"superseded".equalsIgnoreCase(spec.status())) {
      add(strict, errors, warnings, "Spec " + spec.id() + " has supersededBy but status is not superseded.");
    }
  }

  private void validateSpecRelations(
      Spec spec,
      Map<String, Spec> specs,
      Map<String, Adr> adrs,
      boolean strict,
      List<String> errors,
      List<String> warnings) {
    for (SpecRelation relation : nvl(spec.relatedSpecs())) {
      if (blank(relation.specId())) {
        errors.add("Spec relation in " + spec.id() + " has no specId.");
        continue;
      }
      Spec related = specs.get(relation.specId());
      if (related == null) {
        errors.add("Spec " + spec.id() + " relates to unknown spec: " + relation.specId());
      } else if (relation.active() && "superseded".equalsIgnoreCase(related.status())) {
        add(strict, errors, warnings, "Spec " + spec.id() + " has active relation to superseded spec: " + relation.specId());
      }
    }
    for (AdrRelation relation : nvl(spec.relatedAdrLinks())) {
      if (blank(relation.adrId())) {
        errors.add("ADR relation in spec " + spec.id() + " has no adrId.");
        continue;
      }
      Adr related = adrs.get(relation.adrId());
      if (related == null) {
        errors.add("Spec " + spec.id() + " relates to unknown ADR: " + relation.adrId());
      } else if (relation.active() && "superseded".equalsIgnoreCase(related.status())) {
        add(strict, errors, warnings, "Spec " + spec.id() + " has active relation to superseded ADR: " + relation.adrId());
      }
    }
  }

  private void validateRepositoryChangeConsistency(
      Spec spec, boolean strict, List<String> errors, List<String> warnings) {
    Set<String> nonImplementableRequirements = nonImplementableRequirementIds(spec);
    Set<String> nonImplementableAcceptanceCriteria = nonImplementableAcceptanceCriterionIds(spec);
    for (RepositoryChange change : nvl(spec.repositoryChanges())) {
      for (String requirementId : nvl(change.requirements())) {
        if (nonImplementableRequirements.contains(requirementId)) {
          add(strict, errors, warnings, "RepositoryChange in spec " + spec.id() + " assigns non-implementable requirement: " + requirementId);
        }
      }
      for (String acceptanceCriterionId : nvl(change.acceptanceCriteria())) {
        if (nonImplementableAcceptanceCriteria.contains(acceptanceCriterionId)) {
          add(strict, errors, warnings, "RepositoryChange in spec " + spec.id() + " assigns non-implementable acceptance criterion: " + acceptanceCriterionId);
        }
      }
    }
  }

  private void validateAdrConsistency(
      Spec spec, Map<String, Adr> adrs, boolean strict, List<String> errors, List<String> warnings) {
    for (String adrId : nvl(spec.relatedAdrs())) {
      Adr adr = adrs.get(adrId);
      if (adr != null && "superseded".equalsIgnoreCase(adr.status())) {
        add(strict, errors, warnings, "Spec " + spec.id() + " references superseded ADR: " + adrId);
      }
    }
  }

  private Set<String> nonImplementableRequirementIds(Spec spec) {
    Set<String> ids = new LinkedHashSet<>();
    for (Requirement requirement : nvl(spec.functionalRequirements())) {
      if (!requirement.implementable()) ids.add(requirement.id());
    }
    for (Requirement requirement : nvl(spec.nonFunctionalRequirements())) {
      if (!requirement.implementable()) ids.add(requirement.id());
    }
    return ids;
  }

  private Set<String> nonImplementableAcceptanceCriterionIds(Spec spec) {
    return nvl(spec.acceptanceCriteria()).stream()
        .filter(c -> !c.implementable())
        .map(AcceptanceCriterion::id)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  private void add(
      boolean strict, List<String> errors, List<String> warnings, String message) {
    if (strict) errors.add(message);
    else warnings.add(message);
  }

  private void requireUnique(List<String> values, String messagePrefix, List<String> errors) {
    Set<String> seen = new LinkedHashSet<>();
    for (String value : nvl(values)) {
      if (blank(value)) continue;
      if (!seen.add(value)) errors.add(messagePrefix + value);
    }
  }

  private void validateSchemaVersions(
      Path root, boolean strict, List<String> errors, List<String> warnings) {
    Path dir = root.resolve(".archcontext");
    List<Path> files = new ArrayList<>();
    for (String name : List.of("solution.yaml", "repositories.yaml")) {
      Path file = dir.resolve(name);
      if (Files.exists(file)) files.add(file);
    }
    for (String subdir : List.of("specs", "adrs", "guidelines", "reviews")) {
      Path child = dir.resolve(subdir);
      if (Files.isDirectory(child)) {
        try (var paths = Files.list(child)) {
          files.addAll(
              paths
                  .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                  .sorted()
                  .toList());
        } catch (IOException e) {
          errors.add("Cannot read " + child + ": " + e.getMessage());
        }
      }
    }
    for (Path file : files) {
      try {
        String schemaVersion = schemaVersion(file);
        if (!Set.of("1.0", "1.1").contains(schemaVersion)) {
          String message = "Unsupported or missing schemaVersion in " + root.relativize(file);
          if (strict) errors.add(message);
          else warnings.add(message);
        }
      } catch (IOException e) {
        errors.add("Cannot read " + file + ": " + e.getMessage());
      }
    }
  }

  private String schemaVersion(Path file) throws IOException {
    for (String line : Files.readAllLines(file)) {
      String trimmed = line.trim();
      if (trimmed.startsWith("schemaVersion:")) {
        return trimmed
            .substring("schemaVersion:".length())
            .trim()
            .replaceAll("^['\"]|['\"]$", "");
      }
    }
    return null;
  }

  private List<Spec> specs(Path root) throws IOException {
    Path dir = root.resolve(".archcontext/specs");
    if (!Files.isDirectory(dir)) return List.of();
    List<Spec> specs = new ArrayList<>();
    try (var paths = Files.list(dir)) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        var doc = new dev.archcontext.yaml.YamlMapper().read(path);
        if (doc.spec != null) specs.add(doc.spec);
      }
    }
    return specs;
  }

  private Set<String> adrIds(Path root) throws IOException {
    Path dir = root.resolve(".archcontext/adrs");
    if (!Files.isDirectory(dir)) return Set.of();
    Set<String> ids = new LinkedHashSet<>();
    try (var paths = Files.list(dir)) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        var doc = new dev.archcontext.yaml.YamlMapper().read(path);
        if (doc.adr != null) ids.add(doc.adr.id());
      }
    }
    return ids;
  }

  private List<Adr> adrs(Path root) throws IOException {
    Path dir = root.resolve(".archcontext/adrs");
    if (!Files.isDirectory(dir)) return List.of();
    List<Adr> adrs = new ArrayList<>();
    try (var paths = Files.list(dir)) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        var doc = new dev.archcontext.yaml.YamlMapper().read(path);
        if (doc.adr != null) adrs.add(doc.adr);
      }
    }
    return adrs;
  }

  private List<ImplementationReview> implementationReviews(Path root) throws IOException {
    Path dir = root.resolve(".archcontext/reviews");
    if (!Files.isDirectory(dir)) return List.of();
    List<ImplementationReview> reviews = new ArrayList<>();
    try (var paths = Files.list(dir)) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        var doc = new dev.archcontext.yaml.YamlMapper().read(path);
        if (doc.implementationReview != null) reviews.add(doc.implementationReview);
      }
    }
    return reviews;
  }

  private void validateReviewFinding(
      ReviewFinding finding,
      Spec spec,
      Set<String> adrIds,
      List<String> errors,
      List<String> warnings) {
    if (finding == null) {
      errors.add("Implementation review contains a null finding.");
      return;
    }
    if (blank(finding.id())) errors.add("Review finding id is required.");
    if (blank(finding.type())) errors.add("Review finding type is required: " + finding.id());
    if (!Set.of("blocker", "critical", "major", "minor", "info")
        .contains(String.valueOf(finding.severity()).toLowerCase(Locale.ROOT))) {
      errors.add("Invalid review finding severity in " + finding.id() + ": " + finding.severity());
    }
    if (!Set.of("open", "acknowledged", "in-progress", "resolved", "wont-fix", "dismissed")
        .contains(String.valueOf(finding.status()).toLowerCase(Locale.ROOT))) {
      errors.add("Invalid review finding status in " + finding.id() + ": " + finding.status());
    }
    if (blank(finding.title())) errors.add("Review finding title is required: " + finding.id());
    if (blank(finding.description())) {
      errors.add("Review finding description is required: " + finding.id());
    }
    for (ReviewEvidence evidence : nvl(finding.evidence())) {
      if (blank(evidence.path())) errors.add("Review evidence path is required: " + finding.id());
      if (evidence.lineStart() != null && evidence.lineStart() < 1) {
        errors.add("Review evidence lineStart must be positive: " + finding.id());
      }
      if (evidence.lineEnd() != null
          && evidence.lineStart() != null
          && evidence.lineEnd() < evidence.lineStart()) {
        errors.add("Review evidence lineEnd precedes lineStart: " + finding.id());
      }
    }
    requireUnique(
        nvl(finding.proposedActions()).stream().map(ProposedReviewAction::id).toList(),
        "Duplicate proposed review action id in " + finding.id() + ": ",
        errors);
    for (ProposedReviewAction action : nvl(finding.proposedActions())) {
      if (blank(action.id())) errors.add("Proposed review action id is required: " + finding.id());
      if (blank(action.type())) errors.add("Proposed review action type is required: " + finding.id());
      if (!Set.of("proposed", "accepted", "applied", "rejected")
          .contains(String.valueOf(action.status()).toLowerCase(Locale.ROOT))) {
        errors.add("Invalid proposed review action status in " + action.id() + ": " + action.status());
      }
      if (blank(action.title())) warnings.add("Proposed review action title is missing: " + action.id());
    }
    if (spec != null) validateFindingSpecReferences(finding, spec, errors);
    for (String adrId : nvl(finding.relatedAdrs())) {
      if (!adrIds.contains(adrId)) errors.add("Unknown review finding ADR: " + adrId);
    }
    boolean terminal =
        Set.of("resolved", "wont-fix", "dismissed")
            .contains(String.valueOf(finding.status()).toLowerCase(Locale.ROOT));
    if (terminal && finding.resolution() == null) {
      errors.add("Terminal review finding requires a resolution: " + finding.id());
    }
    FindingResolution resolution = finding.resolution();
    if (resolution != null) {
      if (blank(resolution.summary())) {
        errors.add("Finding resolution summary is required: " + finding.id());
      }
      if (!blank(resolution.relatedAdr()) && !adrIds.contains(resolution.relatedAdr())) {
        errors.add("Unknown finding resolution ADR: " + resolution.relatedAdr());
      }
      if (!blank(resolution.relatedConstraint())
          && (spec == null
              || nvl(spec.structuredConstraints()).stream()
                  .noneMatch(c -> resolution.relatedConstraint().equals(c.id())))) {
        errors.add("Unknown finding resolution constraint: " + resolution.relatedConstraint());
      }
    }
  }

  private void validateFindingSpecReferences(
      ReviewFinding finding, Spec spec, List<String> errors) {
    Set<String> requirementIds = requirementIds(spec);
    Set<String> acceptanceCriterionIds = acceptanceCriterionIds(spec);
    Set<String> constraintIds =
        nvl(spec.structuredConstraints()).stream()
            .map(Constraint::id)
            .collect(Collectors.toSet());
    for (String id : nvl(finding.relatedRequirements())) {
      if (!requirementIds.contains(id)) errors.add("Unknown review finding requirement: " + id);
    }
    for (String id : nvl(finding.relatedAcceptanceCriteria())) {
      if (!acceptanceCriterionIds.contains(id)) {
        errors.add("Unknown review finding acceptance criterion: " + id);
      }
    }
    for (String id : nvl(finding.relatedConstraints())) {
      if (!constraintIds.contains(id)) errors.add("Unknown review finding constraint: " + id);
    }
  }

  private static List<String> distinct(List<String> values) {
    return new ArrayList<>(new LinkedHashSet<>(values));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static <T> List<T> nvl(List<T> value) {
    return value == null ? List.of() : value;
  }
}
