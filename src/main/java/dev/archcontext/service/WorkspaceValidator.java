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

  public WriteValidation validateSpec(Path root, Spec spec) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    if (blank(spec.id())) errors.add("Spec id is required.");
    if (blank(spec.title())) errors.add("Spec title is required.");
    if (blank(spec.status())) errors.add("Spec status is required.");
    if (blank(spec.owner())) errors.add("Spec owner is required.");
    if (blank(spec.problem())) errors.add("Spec problem is required.");
    if (blank(spec.businessGoal())) errors.add("Spec businessGoal is required.");

    if ("active".equalsIgnoreCase(spec.status()) && nvl(spec.acceptanceCriteria()).isEmpty()) {
      errors.add("Active specs must have at least one acceptance criterion.");
    }

    try {
      Map<String, RepositoryDefinition> repositories =
          repositoryService.list(root).stream()
              .collect(Collectors.toMap(RepositoryDefinition::id, r -> r, (a, b) -> a));
      validateRepositoryRefs(spec, repositories, errors);
      validateComponentRefs(spec, repositories, errors);
    } catch (IOException e) {
      errors.add("Cannot read repository definitions: " + e.getMessage());
    }

    return new WriteValidation(errors, warnings);
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
    Path specsDir = archContextDir.resolve("specs").normalize();
    boolean knownRepositoriesFile = normalizedTarget.equals(repositoriesFile);
    boolean knownSpecFile =
        normalizedTarget.getParent() != null
            && normalizedTarget.getParent().normalize().equals(specsDir)
            && normalizedTarget.getFileName().toString().endsWith(".yaml");
    if (!knownRepositoriesFile && !knownSpecFile) {
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
      boolean componentExists =
          nvl(repository.components()).stream().anyMatch(c -> c.id().equals(ref.componentId()));
      if (!componentExists) {
        errors.add("Unknown component reference: " + ref.repositoryId() + ":" + ref.componentId());
      }
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static <T> List<T> nvl(List<T> value) {
    return value == null ? List.of() : value;
  }
}
