package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import dev.archcontext.yaml.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

public class YamlWorkspaceWriter {
  private static final ExecutorService SPEC_INDEX_EXECUTOR =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "archcontext-spec-indexer");
            thread.setDaemon(true);
            return thread;
          });

  private final Path root;
  private final Path archContextDir;
  private final YamlMapper yaml = new YamlMapper();
  private final ImportService importService = new ImportService();
  private final WorkspaceValidator validator = new WorkspaceValidator();
  private ValidationCache validationCache;
  private volatile Future<?> pendingSpecIndexing = CompletableFuture.completedFuture(null);

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

  public WriteResult upsertSolution(
      Solution solution, List<Principle> principles, boolean dryRun) {
    Path target = solutionFile();
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateSolution(solution, principles);
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "Solution was not written.", validation, solution);
    }
    try {
      YamlDocuments doc = readOrNew(target);
      Solution existingSolution = doc.solution;
      List<Principle> safePrinciples = new ArrayList<>(nvl(principles));
      boolean changed = !Objects.equals(existingSolution, solution) || !nvl(doc.principles).equals(safePrinciples);
      doc.schemaVersion = "1.1";
      doc.solution = solution;
      doc.principles = safePrinciples;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      return result(changed, dryRun, target, "Updated solution context.", validation, solution);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Solution was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          solution);
    }
  }

  public WriteResult upsertSolutionPrinciple(Principle principle, boolean dryRun) {
    Path target = solutionFile();
    validator.validateKnownWriteTarget(root, target);
    try {
      YamlDocuments doc = readOrNew(target);
      if (doc.solution == null) {
        doc.solution = new Solution("archcontext-solution", "ArchContext Solution", null);
      }
      List<Principle> principles = new ArrayList<>(nvl(doc.principles));
      principles.removeIf(p -> p.id().equals(principle.id()));
      principles.add(principle);
      WriteValidation validation = validator.validateSolution(doc.solution, principles);
      if (!validation.errors().isEmpty()) {
        return result(false, dryRun, target, "Principle was not written.", validation, principle);
      }
      boolean changed = !nvl(doc.principles).equals(principles);
      doc.schemaVersion = "1.1";
      doc.principles = principles;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      return result(changed, dryRun, target, "Upserted principle " + principle.id() + ".", validation, principle);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Principle was not written.",
          new WriteValidation(List.of(e.getMessage()), List.of()),
          principle);
    }
  }

  public WriteResult upsertSolutionGlossaryTerm(GlossaryTerm term, boolean dryRun) {
    Path target = solutionFile();
    validator.validateKnownWriteTarget(root, target);
    try {
      YamlDocuments doc = readOrNew(target);
      Solution current =
          doc.solution == null
              ? new Solution("archcontext-solution", "ArchContext Solution", null)
              : doc.solution;
      List<GlossaryTerm> glossary = new ArrayList<>(nvl(current.glossary()));
      glossary.removeIf(t -> t.term().equalsIgnoreCase(term.term()));
      glossary.add(term);
      Solution updated =
          new Solution(
              current.id(),
              current.name(),
              current.description(),
              current.vision(),
              current.crossCuttingConcerns(),
              glossary);
      WriteValidation validation = validator.validateSolution(updated, doc.principles);
      if (!validation.errors().isEmpty()) {
        return result(false, dryRun, target, "Glossary term was not written.", validation, term);
      }
      boolean changed = !updated.equals(current);
      doc.schemaVersion = "1.1";
      doc.solution = updated;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      return result(changed, dryRun, target, "Upserted glossary term " + term.term() + ".", validation, term);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Glossary term was not written.",
          new WriteValidation(List.of(e.getMessage()), List.of()),
          term);
    }
  }

  public WriteResult upsertRepositoryComponent(
      String repositoryId, Component component, boolean dryRun) {
    return updateRepository(
        repositoryId,
        dryRun,
        "Repository component was not written.",
        repository -> {
          List<Component> components = new ArrayList<>(nvl(repository.components()));
          components.removeIf(c -> c.id().equals(component.id()));
          components.add(component);
          return new RepositoryDefinition(
              repository.id(),
              repository.name(),
              repository.path(),
              repository.type(),
              repository.language(),
              repository.boundedContext(),
              repository.description(),
              repository.responsibilities(),
              components);
        },
        "Upserted repository component " + component.id() + ".");
  }

  public WriteResult upsertRepositoryResponsibility(
      String repositoryId, Responsibility responsibility, boolean dryRun) {
    return updateRepository(
        repositoryId,
        dryRun,
        "Repository responsibility was not written.",
        repository -> {
          List<Responsibility> responsibilities = new ArrayList<>(nvl(repository.responsibilities()));
          responsibilities.removeIf(r -> r.id().equals(responsibility.id()));
          responsibilities.add(responsibility);
          return new RepositoryDefinition(
              repository.id(),
              repository.name(),
              repository.path(),
              repository.type(),
              repository.language(),
              repository.boundedContext(),
              repository.description(),
              responsibilities,
              repository.components());
        },
        "Upserted repository responsibility " + responsibility.id() + ".");
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

  public WriteResult createAdr(Adr adr, boolean dryRun) {
    Path target = adrFile(adr);
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateAdr(root, adr);
    if (Files.exists(target)) {
      validation =
          new WriteValidation(
              append(validation.errors(), "ADR already exists: " + relative(target)),
              validation.warnings());
    }
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "ADR was not written.", validation, adr);
    }

    try {
      YamlDocuments doc = new YamlDocuments();
      doc.schemaVersion = "1.1";
      doc.adr = adr;
      if (!dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      return result(true, dryRun, target, "Created ADR " + adr.id() + ".", validation, adr);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "ADR was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          adr);
    }
  }

  public WriteResult upsertAdr(Adr adr, boolean dryRun) {
    Path target = findAdrPath(adr.id());
    if (target == null) target = adrFile(adr);
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateAdr(root, adr);
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "ADR was not written.", validation, adr);
    }

    try {
      YamlDocuments doc = Files.exists(target) ? yaml.read(target) : new YamlDocuments();
      Adr existing = doc.adr;
      boolean changed = existing == null || !existing.equals(adr);
      doc.schemaVersion = "1.1";
      doc.adr = adr;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      String action = existing == null ? "Created ADR " : "Updated ADR ";
      return result(changed, dryRun, target, action + adr.id() + ".", validation, adr);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "ADR was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          adr);
    }
  }

  public WriteResult createGuideline(Guideline guideline, boolean dryRun) {
    Path target = guidelineFile(guideline.id());
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateGuideline(root, guideline);
    if (Files.exists(target)) {
      validation =
          new WriteValidation(
              append(validation.errors(), "Guideline already exists: " + relative(target)),
              validation.warnings());
    }
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "Guideline was not written.", validation, guideline);
    }
    try {
      YamlDocuments doc = new YamlDocuments();
      doc.schemaVersion = "1.1";
      doc.guideline = guideline;
      if (!dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      return result(
          true, dryRun, target, "Created guideline " + guideline.id() + ".", validation, guideline);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Guideline was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          guideline);
    }
  }

  public WriteResult upsertGuideline(Guideline guideline, boolean dryRun) {
    Path target = findGuidelinePath(guideline.id());
    if (target == null) target = guidelineFile(guideline.id());
    validator.validateKnownWriteTarget(root, target);
    WriteValidation validation = validator.validateGuideline(root, guideline);
    if (!validation.errors().isEmpty()) {
      return result(false, dryRun, target, "Guideline was not written.", validation, guideline);
    }
    try {
      YamlDocuments doc = Files.exists(target) ? yaml.read(target) : new YamlDocuments();
      Guideline existing = doc.guideline;
      boolean changed = existing == null || !existing.equals(guideline);
      doc.schemaVersion = "1.1";
      doc.guideline = guideline;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      String action = existing == null ? "Created guideline " : "Updated guideline ";
      return result(changed, dryRun, target, action + guideline.id() + ".", validation, guideline);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          "Guideline was not written.",
          new WriteValidation(List.of(e.getMessage()), validation.warnings()),
          guideline);
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

  public WriteResult deprecateSpecRequirement(
      String specId,
      String requirementType,
      String requirementId,
      String status,
      String reason,
      String supersededBy,
      String relatedAdr,
      boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Requirement status was not written.",
        spec ->
            deprecateRequirement(
                spec, requirementType, requirementId, status, reason, supersededBy, relatedAdr),
        "Updated " + requirementType + " requirement " + requirementId + " status to " + status + ".");
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

  public WriteResult upsertSpecAffectedComponent(
      String specId, ComponentRef affectedComponent, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Affected component was not written.",
        spec -> updateAffectedComponent(spec, affectedComponent),
        "Upserted affected component for " + affectedComponent.repositoryId() + ".");
  }

  public WriteResult updateSpecStatus(
      String specId, String status, String note, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Spec status was not written.",
        spec -> updateSpecStatus(spec, status),
        "Updated spec " + specId + " status to " + status + ".");
  }

  public WriteResult upsertSpecMetadata(String specId, SpecMetadata metadata, boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Spec metadata was not written.",
        spec -> updateSpecMetadata(spec, metadata),
        "Upserted metadata for spec " + specId + ".");
  }

  public WriteResult upsertSpecSummary(
      String specId,
      String title,
      String owner,
      String problem,
      String businessGoal,
      boolean dryRun) {
    return updateSpec(
        specId,
        dryRun,
        "Spec summary was not written.",
        spec -> updateSpecSummary(spec, title, owner, problem, businessGoal),
        "Updated summary fields for spec " + specId + ".");
  }

  public WriteResult upsertAdrConsequence(
      String adrId, String consequence, boolean dryRun) {
    try {
      Path target = findAdrPath(adrId);
      if (target == null) {
        WriteValidation validation = new WriteValidation(List.of("Unknown adrId: " + adrId), List.of());
        return result(false, dryRun, adrsDir(), "ADR consequence was not written.", validation, null);
      }
      validator.validateKnownWriteTarget(root, target);
      YamlDocuments doc = yaml.read(target);
      Adr original = doc.adr;
      List<String> consequences = new ArrayList<>(nvl(original.consequences()));
      boolean exists = consequences.stream().anyMatch(c -> c.equalsIgnoreCase(consequence));
      if (!exists) consequences.add(consequence);
      Adr updated =
          new Adr(
              original.id(),
              original.title(),
              original.status(),
              original.date(),
              original.context(),
              original.decision(),
              original.supersededBy(),
              original.statusNote(),
              consequences,
              original.affectedRepositories(),
              original.relatedSpecs(),
              original.sourcePath());
      WriteValidation validation = validator.validateAdr(root, updated);
      if (!validation.errors().isEmpty()) {
        return result(false, dryRun, target, "ADR consequence was not written.", validation, updated);
      }
      boolean changed = !updated.equals(original);
      doc.schemaVersion = "1.1";
      doc.adr = updated;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      String summary = changed ? "Upserted consequence for ADR " + adrId + "." : "No changes for ADR " + adrId + ".";
      return result(changed, dryRun, target, summary, validation, updated);
    } catch (IOException | IllegalArgumentException e) {
      WriteValidation validation = new WriteValidation(List.of(e.getMessage()), List.of());
      return result(false, dryRun, adrsDir(), "ADR consequence was not written.", validation, null);
    }
  }

  public WriteResult updateAdrStatus(
      String adrId, String status, String supersededBy, String note, boolean dryRun) {
    try {
      Path target = findAdrPath(adrId);
      if (target == null) {
        WriteValidation validation = new WriteValidation(List.of("Unknown adrId: " + adrId), List.of());
        return result(false, dryRun, adrsDir(), "ADR status was not written.", validation, null);
      }
      validator.validateKnownWriteTarget(root, target);
      YamlDocuments doc = yaml.read(target);
      Adr original = doc.adr;
      Adr updated =
          new Adr(
              original.id(),
              original.title(),
              status,
              original.date(),
              original.context(),
              original.decision(),
              supersededBy == null || supersededBy.isBlank() ? original.supersededBy() : supersededBy,
              note == null || note.isBlank() ? original.statusNote() : note,
              original.consequences(),
              original.affectedRepositories(),
              original.relatedSpecs(),
              original.sourcePath());
      WriteValidation validation = validator.validateAdr(root, updated);
      if (!validation.errors().isEmpty()) {
        return result(false, dryRun, target, "ADR status was not written.", validation, updated);
      }
      boolean changed = !updated.equals(original);
      doc.schemaVersion = "1.1";
      doc.adr = updated;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      String summary = changed ? "Updated ADR " + adrId + " status to " + status + "." : "No changes for ADR " + adrId + ".";
      return result(changed, dryRun, target, summary, validation, updated);
    } catch (IOException | IllegalArgumentException e) {
      WriteValidation validation = new WriteValidation(List.of(e.getMessage()), List.of());
      return result(false, dryRun, adrsDir(), "ADR status was not written.", validation, null);
    }
  }

  public WriteValidation validateWorkspace(boolean strict) {
    WorkspaceFingerprint fingerprint = fingerprint();
    if (validationCache != null
        && validationCache.strict() == strict
        && validationCache.fingerprint().equals(fingerprint)) {
      return validationCache.validation();
    }
    WriteValidation validation = validator.validateWorkspace(root, strict);
    validationCache = new ValidationCache(strict, fingerprint, validation);
    return validation;
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

  private WriteResult updateRepository(
      String repositoryId,
      boolean dryRun,
      String failureSummary,
      RepositoryUpdater updater,
      String successSummary) {
    Path target = repositoriesFile();
    validator.validateKnownWriteTarget(root, target);
    try {
      YamlDocuments doc = readOrNew(target);
      List<RepositoryDefinition> repositories = new ArrayList<>(nvl(doc.repositories));
      Optional<RepositoryDefinition> original =
          repositories.stream().filter(r -> r.id().equals(repositoryId)).findFirst();
      if (original.isEmpty()) {
        return result(
            false,
            dryRun,
            target,
            failureSummary,
            new WriteValidation(List.of("Unknown repositoryId: " + repositoryId), List.of()),
            null);
      }
      RepositoryDefinition updated = updater.update(original.get());
      WriteValidation validation = validator.validateRepository(updated);
      if (!validation.errors().isEmpty()) {
        return result(false, dryRun, target, failureSummary, validation, updated);
      }
      repositories.removeIf(r -> r.id().equals(repositoryId));
      repositories.add(updated);
      boolean changed = !updated.equals(original.get());
      doc.schemaVersion = "1.1";
      doc.repositories = repositories;
      if (changed && !dryRun) {
        writeAtomically(target, doc);
        reindex();
      }
      String summary = changed ? successSummary : "No changes for repository " + repositoryId + ".";
      return result(changed, dryRun, target, summary, validation, updated);
    } catch (IOException e) {
      return result(
          false,
          dryRun,
          target,
          failureSummary,
          new WriteValidation(List.of(e.getMessage()), List.of()),
          null);
    }
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
        reindexSpec(specFile.path(), original.id());
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
        spec.metadata(),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec deprecateRequirement(
      Spec spec,
      String requirementType,
      String requirementId,
      String status,
      String reason,
      String supersededBy,
      String relatedAdr) {
    if (!Set.of("obsolete", "superseded", "rejected").contains(status)) {
      throw new IllegalArgumentException("status must be obsolete, superseded, or rejected.");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason is required when deprecating a requirement.");
    }
    if (!"functional".equals(requirementType) && !"nonFunctional".equals(requirementType)) {
      throw new IllegalArgumentException("requirementType must be functional or nonFunctional.");
    }
    List<Requirement> functional = new ArrayList<>(nvl(spec.functionalRequirements()));
    List<Requirement> nonFunctional = new ArrayList<>(nvl(spec.nonFunctionalRequirements()));
    List<Requirement> target = "functional".equals(requirementType) ? functional : nonFunctional;
    boolean updated = false;
    for (int i = 0; i < target.size(); i++) {
      Requirement requirement = target.get(i);
      if (requirementId.equals(requirement.id())) {
        target.set(
            i,
            new Requirement(
                requirement.id(),
                requirement.description(),
                status,
                reason,
                supersededBy,
                relatedAdr));
        updated = true;
        break;
      }
    }
    if (!updated) {
      throw new IllegalArgumentException("Unknown requirementId in spec " + spec.id() + ": " + requirementId);
    }
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
        spec.metadata(),
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
        spec.metadata(),
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
        spec.metadata(),
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
        spec.metadata(),
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
        spec.metadata(),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateAffectedComponent(Spec spec, ComponentRef affectedComponent) {
    List<ComponentRef> affectedComponents = new ArrayList<>(nvl(spec.affectedComponents()));
    affectedComponents.removeIf(c -> sameAffectedComponent(c, affectedComponent));
    affectedComponents.add(affectedComponent);
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
        affectedComponents,
        nvl(spec.outOfScope()),
        nvl(spec.openQuestions()),
        nvl(spec.repositoryChanges()),
        spec.metadata(),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateSpecStatus(Spec spec, String status) {
    return spec(
        spec.id(),
        spec.title(),
        status,
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
        nvl(spec.repositoryChanges()),
        spec.metadata(),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateSpecMetadata(Spec spec, SpecMetadata metadata) {
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
        nvl(spec.repositoryChanges()),
        metadata,
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private Spec updateSpecSummary(
      Spec spec, String title, String owner, String problem, String businessGoal) {
    return spec(
        spec.id(),
        title == null || title.isBlank() ? spec.title() : title,
        spec.status(),
        owner == null || owner.isBlank() ? spec.owner() : owner,
        problem == null || problem.isBlank() ? spec.problem() : problem,
        businessGoal == null || businessGoal.isBlank() ? spec.businessGoal() : businessGoal,
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
        nvl(spec.repositoryChanges()),
        spec.metadata(),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private boolean sameAffectedComponent(ComponentRef left, ComponentRef right) {
    return Objects.equals(left.repositoryId(), right.repositoryId())
        && Objects.equals(left.componentId(), right.componentId())
        && Objects.equals(left.path(), right.path());
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
      SpecMetadata metadata,
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
        metadata,
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

  private Path findAdrPath(String adrId) {
    if (!Files.isDirectory(adrsDir())) return null;
    try (var paths = Files.list(adrsDir())) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        YamlDocuments doc = yaml.read(path);
        if (doc.adr != null && adrId.equals(doc.adr.id())) {
          return path;
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    return null;
  }

  private Path findGuidelinePath(String guidelineId) {
    if (!Files.isDirectory(guidelinesDir())) return null;
    try (var paths = Files.list(guidelinesDir())) {
      for (Path path :
          paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
        YamlDocuments doc = yaml.read(path);
        if (doc.guideline != null && guidelineId.equals(doc.guideline.id())) {
          return path;
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
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
    validationCache = null;
  }

  private void reindexSpec(Path specFile, String previousSpecId) {
    validationCache = null;
    pendingSpecIndexing =
        SPEC_INDEX_EXECUTOR.submit(
            () -> {
              try {
                importService.importSpecFile(root, specFile, previousSpecId);
              } catch (RuntimeException e) {
                System.err.println("ArchContext spec index refresh failed: " + e.getMessage());
              }
            });
  }

  void awaitPendingSpecIndexing() {
    try {
      pendingSpecIndexing.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for spec indexing.", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Spec indexing failed.", e);
    }
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

  private Path solutionFile() {
    return archContextDir.resolve("solution.yaml");
  }

  private Path specsDir() {
    return archContextDir.resolve("specs");
  }

  private Path adrsDir() {
    return archContextDir.resolve("adrs");
  }

  private Path guidelinesDir() {
    return archContextDir.resolve("guidelines");
  }

  private Path specFile(Spec spec) {
    return specsDir().resolve(slug(spec.id()) + ".yaml");
  }

  private Path adrFile(Adr adr) {
    return adrsDir().resolve(slug(adr.id()) + ".yaml");
  }

  private Path guidelineFile(String guidelineId) {
    return guidelinesDir().resolve(slug(guidelineId) + ".yaml");
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

  private WorkspaceFingerprint fingerprint() {
    long count = 0;
    long size = 0;
    long modified = 0;
    if (!Files.isDirectory(archContextDir)) return new WorkspaceFingerprint(0, 0, 0);
    try (var paths = Files.walk(archContextDir)) {
      for (Path path :
          paths
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".yaml"))
              .sorted()
              .toList()) {
        count++;
        size += Files.size(path);
        modified = Math.max(modified, Files.getLastModifiedTime(path).toMillis());
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot fingerprint workspace: " + e.getMessage(), e);
    }
    return new WorkspaceFingerprint(count, size, modified);
  }

  private record SpecFile(Path path, YamlDocuments document) {}

  private record WorkspaceFingerprint(long fileCount, long totalSize, long maxModifiedMillis) {}

  private record ValidationCache(
      boolean strict, WorkspaceFingerprint fingerprint, WriteValidation validation) {}

  @FunctionalInterface
  private interface SpecUpdater {
    Spec update(Spec spec);
  }

  @FunctionalInterface
  private interface RepositoryUpdater {
    RepositoryDefinition update(RepositoryDefinition repository);
  }
}
