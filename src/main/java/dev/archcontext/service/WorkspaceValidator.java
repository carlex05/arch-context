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

  public WriteValidation validateWorkspace(Path root, boolean strict) {
    List<String> errors = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    validateSchemaVersions(root, strict, errors, warnings);
    try {
      Map<String, RepositoryDefinition> repositories =
          repositoryService.list(root).stream()
              .collect(Collectors.toMap(RepositoryDefinition::id, r -> r, (a, b) -> a));
      Set<String> adrIds = adrIds(root);
      for (Spec spec : specs(root)) {
        WriteValidation specValidation = validateSpec(root, spec);
        errors.addAll(specValidation.errors());
        warnings.addAll(specValidation.warnings());
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
    } catch (IOException e) {
      errors.add("Cannot validate workspace: " + e.getMessage());
    }
    return new WriteValidation(distinct(errors), distinct(warnings));
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

  private void validateSchemaVersions(
      Path root, boolean strict, List<String> errors, List<String> warnings) {
    Path dir = root.resolve(".archcontext");
    List<Path> files = new ArrayList<>();
    for (String name : List.of("solution.yaml", "repositories.yaml")) {
      Path file = dir.resolve(name);
      if (Files.exists(file)) files.add(file);
    }
    for (String subdir : List.of("specs", "adrs", "guidelines")) {
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
