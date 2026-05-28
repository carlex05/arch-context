package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import dev.archcontext.yaml.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class YamlWorkspaceWriter {
  private final Path root;
  private final Path archContextDir;
  private final YamlMapper yaml = new YamlMapper();
  private final ImportService importService = new ImportService();
  private final WorkspaceValidator validator = new WorkspaceValidator();

  public YamlWorkspaceWriter(Path root) {
    this.root = root.toAbsolutePath().normalize();
    this.archContextDir = this.root.resolve(".archcontext");
  }

  public WriteResult upsertRepository(RepositoryDefinition repository, boolean dryRun) {
    Path target = repositoriesFile();
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateRepository(repository);
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "Repository was not written.", validation, repository);
    }

    try {
      YamlDocuments doc = readOrNew(target);
      doc.schemaVersion = "1.1";
      if (doc.repositories == null) doc.repositories = new ArrayList<>();
      Optional<RepositoryDefinition> existing =
          doc.repositories.stream().filter(r -> r.id().equals(repository.id())).findFirst();
      boolean changed = existing.map(r -> !r.equals(repository)).orElse(true);
      doc.repositories.removeIf(r -> r.id().equals(repository.id()));
      doc.repositories.add(repository);
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      String action = existing.isPresent() ? "Updated repository " : "Created repository ";
      return result(
          changed, dryRun, target, action + repository.id() + ".", validation, repository);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Repository was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          repository);
    }
  }

  public WriteResult createSpec(Spec spec, boolean dryRun) {
    Path target = specFile(spec);
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateSpec(root, spec);
    if (Files.exists(target)) {
      validation =
          new WriteValidation(
              append(validation.errors(), "Spec already exists: " + relative(target)),
              validation.warnings());
    }
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "Spec was not written.", validation, spec);
    }

    try {
      YamlDocuments doc = new YamlDocuments();
      doc.schemaVersion = "1.1";
      doc.spec = spec;
      if (!dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      return result(true, dryRun, target, "Created spec " + spec.id() + ".", validation, spec);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Spec was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          spec);
    }
  }

  public WriteResult upsertSpecRequirement(
      String specId, String requirementType, Requirement requirement, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Requirement was not written.",
        spec -> updateRequirement(spec, requirementType, requirement),
        "Upserted " + requirementType + " requirement " + requirement.id() + ".");
  }

  public WriteResult upsertSpecAcceptanceCriterion(
      String specId, AcceptanceCriterion acceptanceCriterion, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Acceptance criterion was not written.",
        spec -> updateAcceptanceCriterion(spec, acceptanceCriterion),
        "Upserted acceptance criterion " + acceptanceCriterion.id() + ".");
  }

  public WriteResult addSpecOutOfScopeItem(String specId, OutOfScopeItem item, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Out-of-scope item was not written.",
        spec -> addOutOfScopeItem(spec, item),
        "Added out-of-scope item to " + specId + ".");
  }

  public WriteResult upsertSpecConstraint(String specId, Constraint constraint, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Constraint was not written.",
        spec -> updateStructuredConstraint(spec, constraint),
        "Upserted structured constraint " + constraint.id() + ".");
  }

  public WriteResult upsertSpecRepositoryChange(
      String specId, RepositoryChange repositoryChange, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Repository change was not written.",
        spec -> updateRepositoryChange(spec, repositoryChange),
        "Upserted repository change for " + repositoryChange.repositoryId() + ".");
  }

  public WriteValidation validateWorkspace(boolean strict) {
    return validator.validateWorkspace(root, strict);
  }

  public WriteValidation validateSpecRepositoryCoverage(String specId, boolean strict) {
    try {
      SpecFile specFile = findSpec(specId);
      if (specFile == null) {
        return new WriteValidation(List.of("Unknown specId: " + specId), List.of());
      }
      WriteValidation validation = validator.validateSpec(root, specFile.document().spec);
      WriteValidation coverage =
          validator.validateSpecRepositoryCoverage(root, specFile.document().spec, strict);
      return new WriteValidation(
          concat(validation.errors(), coverage.errors()),
          concat(validation.warnings(), coverage.warnings()));
    } catch (IOException e) {
      return new WriteValidation(List.of(e.getMessage()), List.of());
    }
  }

  public void validateKnownWriteTarget(Path target) {
    validator.validateKnownWriteTarget(root, target);
  }

  private WriteResult updateSpec(
      String specId,
      boolean dryRun,
      String failureSummary,
      SpecUpdater updater,
      String successSummary) {
    try {
      SpecFile specFile = findSpec(specId);
      if (specFile == null) {
        WriteValidation validation =
            new WriteValidation(List.of("Unknown specId: " + specId), List.of());
        return result(false, dryRun, specsDir(), failureSummary, validation, null);
      }
      validator.validateKnownWriteTarget(root, specFile.path());
      Spec original = specFile.document().spec;
      Spec updated = updater.update(original);
      WriteValidation validation = validator.validateSpec(root, updated);
      if (!validation.errors().isEmpty()) {
        return result(false, dryRun, specFile.path(), failureSummary, validation, updated);
      }

      boolean changed = !updated.equals(original);
      specFile.document().schemaVersion = "1.1";
      specFile.document().spec = updated;
      if (changed && !dryRun) {
        writeAtomically(specFile.path(), specFile.document());
        reindex();
      }
      String summary = changed ? successSummary : "No changes for spec " + specId + ".";
      return result(changed, dryRun, specFile.path(), summary, validation, updated);
    } catch (IOException | IllegalArgumentException e) {
      WriteValidation validation = new WriteValidation(List.of(e.getMessage()), List.of());
      return result(false, dryRun, specsDir(), failureSummary, validation, null);
    }
  }

  private Spec updateRequirement(Spec spec, String requirementType, Requirement requirement) {
    if (!"functional".equals(requirementType) && !"nonFunctional".equals(requirementType)) {
      throw new IllegalArgumentException("requirementType must be functional or nonFunctional.");
    }
    List<Requirement> functional = new ArrayList<>(nvl(spec.functionalRequirements()));
    List<Requirement> nonFunctional = new ArrayList<>(nvl(spec.nonFunctionalRequirements()));
    List<Requirement> target = "functional".equals(requirementType) ? functional : nonFunctional;
    target.removeIf(r -> r.id().equals(requirement.id()));
    target.add(requirement);
    return spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.owner(),
        spec.problem(),
        spec.businessGoal(),
        nvl(spec.affectedRepositories()),
        nvl(spec.affectedBoundedContexts()),
        functional,
        nonFunctional,
        nvl(spec.acceptanceCriteria()),
        nvl(spec.constraints()),
        nvl(spec.structuredConstraints()),
        nvl(spec.affectedComponents()),
        nvl(spec.outOfScope()),
        nvl(spec.openQuestions()),
        nvl(spec.repositoryChanges()),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateAcceptanceCriterion(Spec spec, AcceptanceCriterion acceptanceCriterion) {
    List<AcceptanceCriterion> criteria = new ArrayList<>(nvl(spec.acceptanceCriteria()));
    criteria.removeIf(c -> c.id().equals(acceptanceCriterion.id()));
    criteria.add(acceptanceCriterion);
    return spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.owner(),
        spec.problem(),
        spec.businessGoal(),
        nvl(spec.affectedRepositories()),
        nvl(spec.affectedBoundedContexts()),
        nvl(spec.functionalRequirements()),
        nvl(spec.nonFunctionalRequirements()),
        criteria,
        nvl(spec.constraints()),
        nvl(spec.structuredConstraints()),
        nvl(spec.affectedComponents()),
        nvl(spec.outOfScope()),
        nvl(spec.openQuestions()),
        nvl(spec.repositoryChanges()),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec addOutOfScopeItem(Spec spec, OutOfScopeItem item) {
    List<OutOfScopeItem> items = new ArrayList<>(nvl(spec.outOfScope()));
    boolean exists =
        items.stream().anyMatch(i -> i.description().equalsIgnoreCase(item.description()));
    if (!exists) items.add(item);
    return spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.owner(),
        spec.problem(),
        spec.businessGoal(),
        nvl(spec.affectedRepositories()),
        nvl(spec.affectedBoundedContexts()),
        nvl(spec.functionalRequirements()),
        nvl(spec.nonFunctionalRequirements()),
        nvl(spec.acceptanceCriteria()),
        nvl(spec.constraints()),
        nvl(spec.structuredConstraints()),
        nvl(spec.affectedComponents()),
        items,
        nvl(spec.openQuestions()),
        nvl(spec.repositoryChanges()),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateStructuredConstraint(Spec spec, Constraint constraint) {
    List<Constraint> constraints = new ArrayList<>(nvl(spec.structuredConstraints()));
    constraints.removeIf(c -> c.id().equals(constraint.id()));
    constraints.add(constraint);
    return spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.owner(),
        spec.problem(),
        spec.businessGoal(),
        nvl(spec.affectedRepositories()),
        nvl(spec.affectedBoundedContexts()),
        nvl(spec.functionalRequirements()),
        nvl(spec.nonFunctionalRequirements()),
        nvl(spec.acceptanceCriteria()),
        nvl(spec.constraints()),
        constraints,
        nvl(spec.affectedComponents()),
        nvl(spec.outOfScope()),
        nvl(spec.openQuestions()),
        nvl(spec.repositoryChanges()),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateRepositoryChange(Spec spec, RepositoryChange repositoryChange) {
    List<RepositoryChange> repositoryChanges = new ArrayList<>(nvl(spec.repositoryChanges()));
    repositoryChanges.removeIf(c -> c.repositoryId().equals(repositoryChange.repositoryId()));
    repositoryChanges.add(repositoryChange);
    return spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.owner(),
        spec.problem(),
        spec.businessGoal(),
        nvl(spec.affectedRepositories()),
        nvl(spec.affectedBoundedContexts()),
        nvl(spec.functionalRequirements()),
        nvl(spec.nonFunctionalRequirements()),
        nvl(spec.acceptanceCriteria()),
        nvl(spec.constraints()),
        nvl(spec.structuredConstraints()),
        nvl(spec.affectedComponents()),
        nvl(spec.outOfScope()),
        nvl(spec.openQuestions()),
        repositoryChanges,
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec spec(
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
      List<String> relatedAdrs,
      String sourcePath) {
    return new Spec(
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
        repositoryChanges,
        relatedAdrs,
        sourcePath);
  }

  private SpecFile findSpec(String specId) throws IOException {
    if (!Files.isDirectory(specsDir())) return null;
    try (var paths = Files.list(specsDir())) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        YamlDocuments doc = yaml.read(path);
        if (doc.spec != null && specId.equals(doc.spec.id())) {
          return new SpecFile(path, doc);
        }
      }
    }
    return null;
  }

  private YamlDocuments readOrNew(Path path) throws IOException {
    return Files.exists(path) ? yaml.read(path) : new YamlDocuments();
  }

  private void writeAtomically(Path target, YamlDocuments doc) throws IOException {
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
    try {
      yaml.write(temp, doc);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private void reindex() {
    importService.importWorkspace(root);
  }

  private WriteResult result(
      boolean changed,
      boolean dryRun,
      Path updatedFile,
      String summary,
      WriteValidation validation,
      Object object) {
    List<String> files = updatedFile == null ? List.of() : List.of(relative(updatedFile));
    return new WriteResult(changed, dryRun, files, summary, validation, object);
  }

  private List<String> append(List<String> values, String value) {
    List<String> out = new ArrayList<>(values == null ? List.of() : values);
    out.add(value);
    return out;
  }

  private List<String> concat(List<String> first, List<String> second) {
    List<String> out = new ArrayList<>(first == null ? List.of() : first);
    out.addAll(second == null ? List.of() : second);
    return out;
  }

  private Path repositoriesFile() {
    return archContextDir.resolve("repositories.yaml");
  }

  private Path specsDir() {
    return archContextDir.resolve("specs");
  }

  private Path specFile(Spec spec) {
    return specsDir().resolve(slug(spec.id()) + ".yaml");
  }

  private String relative(Path path) {
    return root.relativize(path.toAbsolutePath().normalize()).toString();
  }

  private String slug(String value) {
    if (value == null || value.isBlank()) return "spec";
    return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
  }

  private static <T> List<T> nvl(List<T> value) {
    return value == null ? List.of() : value;
  }

  private record SpecFile(Path path, YamlDocuments document) {}

  @FunctionalInterface
  private interface SpecUpdater {
    Spec update(Spec spec);
  }
}
