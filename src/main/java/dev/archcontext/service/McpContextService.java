package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class McpContextService {
  private final Path root;
  private final ContextLoaders l;
  private final SearchService search;
  private SolutionContextCache solutionContextCache;

  public McpContextService(Path root) {
    this.root = root.toAbsolutePath().normalize();
    l = new ContextLoaders(root);
    search = new SearchService(root);
  }

  public void warmUp() {
    try {
      l.solution();
      l.principles();
      l.repositories();
      l.guidelines();
      dev.archcontext.util.Json.write(Map.of("status", "warm"));
    } catch (RuntimeException e) {
      // Warm-up is an optimization. Real tool calls should still surface precise failures.
    }
  }

  public SolutionContext getSolutionContext() {
    WorkspaceFingerprint fingerprint = fingerprint();
    if (solutionContextCache != null
        && solutionContextCache.fingerprint().equals(fingerprint)) {
      return solutionContextCache.context();
    }
    SolutionContext context = new SolutionContext(
        l.solution(),
        l.principles(),
        l.repositories(),
        listActiveSpecs().stream().map(this::summary).toList(),
        l.adrs().stream().filter(a -> "accepted".equalsIgnoreCase(a.status())).toList());
    solutionContextCache = new SolutionContextCache(fingerprint, context);
    return context;
  }

  public RepositoryContext getRepositoryContext(String id) {
    RepositoryDefinition r =
        l.repository(id)
            .orElseThrow(() -> new IllegalArgumentException("Unknown repositoryId: " + id));
    List<Spec> specs =
        l.specs().stream().filter(s -> nvl(s.affectedRepositories()).contains(id)).toList();
    List<Adr> adrs =
        l.adrs().stream().filter(a -> nvl(a.affectedRepositories()).contains(id)).toList();
    List<Guideline> gs = applicableGuidelines(r);
    List<String> constraints = specs.stream().flatMap(s -> nvl(s.constraints()).stream()).toList();
    return new RepositoryContext(r, specs, adrs, gs, constraints);
  }

  public List<DocumentChunk> searchContext(String q, List<String> types) {
    return search.search(q, types, 20);
  }

  public Spec getSpecContext(String id) {
    return l.spec(id).orElseThrow(() -> new IllegalArgumentException("Unknown specId: " + id));
  }

  public Adr getAdrContext(String id) {
    return l.adr(id).orElseThrow(() -> new IllegalArgumentException("Unknown adrId: " + id));
  }

  public List<Spec> listSpecs() {
    return l.specs();
  }

  public List<Adr> listAdrs() {
    return l.adrs();
  }

  public List<Guideline> listGuidelines() {
    return l.guidelines();
  }

  public List<Guideline> listGuidelines(String category, String appliesTo) {
    return l.guidelines().stream()
        .filter(g -> category == null || category.isBlank() || category.equals(g.category()))
        .filter(
            g -> {
              if (appliesTo == null || appliesTo.isBlank()) return true;
              AppliesTo a = g.appliesTo();
              return a == null
                  || nvl(a.repositoryIds()).contains("*")
                  || nvl(a.repositoryIds()).contains(appliesTo)
                  || nvl(a.languages()).contains(appliesTo)
                  || nvl(a.repositoryTypes()).contains(appliesTo);
            })
        .toList();
  }

  public Guideline getGuidelineContext(String id) {
    return l.guideline(id)
        .orElseThrow(() -> new IllegalArgumentException("Unknown guidelineId: " + id));
  }

  public List<RepositoryDefinition> listRepositories() {
    return l.repositories();
  }

  public ImplementationContext getImplementationContextForSpec(String specId, String repoId) {
    Spec s = getSpecContext(specId);
    List<RepositoryDefinition> affected =
        l.repositories().stream()
            .filter(r -> nvl(s.affectedRepositories()).contains(r.id()))
            .toList();
    RepositoryContext rc = repoId == null || repoId.isBlank() ? null : getRepositoryContext(repoId);
    List<Adr> adrs =
        l.adrs().stream()
            .filter(
                a ->
                    nvl(s.relatedAdrs()).contains(a.id()) || nvl(a.relatedSpecs()).contains(s.id()))
            .toList();
    List<Guideline> gs =
        repoId == null || repoId.isBlank()
            ? affected.stream().flatMap(r -> applicableGuidelines(r).stream()).distinct().toList()
            : rc.guidelines();
    return new ImplementationContext(
        summary(s),
        affected,
        rc,
        implementableRequirements(s.functionalRequirements()),
        implementableRequirements(s.nonFunctionalRequirements()),
        implementableAcceptanceCriteria(s.acceptanceCriteria()),
        nvl(s.constraints()),
        implementableConstraints(s.structuredConstraints()),
        recentChangeLog(s.changeLog()),
        adrs,
        gs);
  }

  public RepositoryImplementationContext getRepositoryImplementationContextForSpec(
      String specId, String repoId) {
    Spec spec = getSpecContext(specId);
    RepositoryDefinition repository =
        l.repository(repoId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown repositoryId: " + repoId));
    RepositoryChange repositoryChange =
        nvl(spec.repositoryChanges()).stream()
            .filter(c -> repoId.equals(c.repositoryId()))
            .findFirst()
            .orElse(null);
    Set<String> requirementIds =
        repositoryChange == null ? null : new LinkedHashSet<>(nvl(repositoryChange.requirements()));
    Set<String> acceptanceCriterionIds =
        repositoryChange == null
            ? null
            : new LinkedHashSet<>(nvl(repositoryChange.acceptanceCriteria()));
    List<RepositoryDefinition> otherAffectedRepositories =
        l.repositories().stream()
            .filter(r -> nvl(spec.affectedRepositories()).contains(r.id()))
            .filter(r -> !r.id().equals(repoId))
            .toList();
    List<Adr> adrs =
        l.adrs().stream()
            .filter(
                a ->
                    nvl(spec.relatedAdrs()).contains(a.id())
                        || nvl(a.relatedSpecs()).contains(spec.id()))
            .toList();
    return new RepositoryImplementationContext(
        summary(spec),
        repository,
        repositoryChange,
        otherAffectedRepositories,
        filterRequirements(spec.functionalRequirements(), requirementIds),
        filterRequirements(spec.nonFunctionalRequirements(), requirementIds),
        filterAcceptanceCriteria(spec.acceptanceCriteria(), acceptanceCriterionIds),
        nvl(spec.constraints()),
        implementableConstraints(spec.structuredConstraints()),
        recentChangeLog(spec.changeLog()),
        adrs,
        applicableGuidelines(repository));
  }

  public AgentBriefing getAgentBriefingForSpec(String specId, String repoId) {
    Spec spec = getSpecContext(specId);
    RepositoryImplementationContext repositoryContext =
        getRepositoryImplementationContextForSpec(specId, repoId);
    List<Principle> principles =
        l.principles().stream()
            .filter(
                p ->
                    nvl(p.appliesTo()).isEmpty()
                        || nvl(p.appliesTo()).contains("*")
                        || nvl(p.appliesTo()).contains(repoId))
            .toList();
    return new AgentBriefing(
        l.solution(),
        principles,
        l.solution() == null ? List.of() : l.solution().glossary(),
        repositoryContext.spec(),
        repositoryContext.repository(),
        repositoryContext.repositoryChange(),
        repositoryContext.otherAffectedRepositories(),
        nvl(spec.affectedComponents()).stream()
            .filter(c -> repoId.equals(c.repositoryId()))
            .toList(),
        repositoryContext.applicableFunctionalRequirements(),
        repositoryContext.applicableNonFunctionalRequirements(),
        repositoryContext.applicableAcceptanceCriteria(),
        repositoryContext.constraints(),
        repositoryContext.structuredConstraints(),
        repositoryContext.recentChanges(),
        repositoryContext.relatedAdrs(),
        repositoryContext.applicableGuidelines());
  }

  public RepositoryDefinition resolveRepositoryByPath(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      throw new IllegalArgumentException("Missing required argument: path");
    }
    Path target = Path.of(rawPath);
    target = (target.isAbsolute() ? target : root.resolve(target)).toAbsolutePath().normalize();
    RepositoryDefinition match = null;
    int matchLength = -1;
    for (RepositoryDefinition repository : l.repositories()) {
      if (repository.path() == null || repository.path().isBlank()) continue;
      Path repositoryPath = Path.of(repository.path()).toAbsolutePath().normalize();
      if ((target.equals(repositoryPath) || target.startsWith(repositoryPath))
          && repositoryPath.getNameCount() > matchLength) {
        match = repository;
        matchLength = repositoryPath.getNameCount();
      }
    }
    if (match == null) {
      throw new IllegalArgumentException("No repository found for path: " + rawPath);
    }
    return match;
  }

  public ValidationResult validateSpecCompleteness(String id) {
    Spec s = getSpecContext(id);
    List<String> m = new ArrayList<>(), w = new ArrayList<>(), sug = new ArrayList<>();
    if (blank(s.title())) m.add("title");
    if (blank(s.problem())) m.add("problem");
    if (blank(s.businessGoal())) m.add("businessGoal");
    if (nvl(s.affectedRepositories()).isEmpty() && nvl(s.affectedBoundedContexts()).isEmpty())
      m.add("affectedRepositories or affectedBoundedContexts");
    if (nvl(s.acceptanceCriteria()).isEmpty()) m.add("acceptanceCriteria");
    if (s.constraints() == null)
      w.add(
          "constraints section is missing; add constraints: [] when there are no known"
              + " constraints");
    if (!m.isEmpty()) sug.add("Complete missing sections before implementation planning.");
    return new ValidationResult(m, w, sug);
  }

  public List<Spec> listActiveSpecs() {
    Set<String> active = Set.of("draft", "active", "in-progress", "review");
    return l.specs().stream()
        .filter(s -> active.contains(String.valueOf(s.status()).toLowerCase(Locale.ROOT)))
        .toList();
  }

  public String readResource(String uri) {
    if (uri.equals("archcontext://solution"))
      return dev.archcontext.util.Json.write(getSolutionContext());
    if (uri.equals("archcontext://repositories"))
      return dev.archcontext.util.Json.write(listRepositories());
    if (uri.startsWith("archcontext://repositories/"))
      return dev.archcontext.util.Json.write(
          getRepositoryContext(uri.substring(uri.lastIndexOf('/') + 1)));
    if (uri.equals("archcontext://specs")) return dev.archcontext.util.Json.write(listSpecs());
    if (uri.startsWith("archcontext://specs/"))
      return dev.archcontext.util.Json.write(
          getSpecContext(uri.substring(uri.lastIndexOf('/') + 1)));
    if (uri.equals("archcontext://adrs")) return dev.archcontext.util.Json.write(listAdrs());
    if (uri.startsWith("archcontext://adrs/"))
      return dev.archcontext.util.Json.write(
          getAdrContext(uri.substring(uri.lastIndexOf('/') + 1)));
    if (uri.equals("archcontext://guidelines"))
      return dev.archcontext.util.Json.write(listGuidelines());
    throw new IllegalArgumentException("Unknown resource URI: " + uri);
  }

  private List<Guideline> applicableGuidelines(RepositoryDefinition r) {
    return l.guidelines().stream()
        .filter(
            g -> {
              var a = g.appliesTo();
              return a == null
                  || (nvl(a.repositoryIds()).isEmpty()
                          || nvl(a.repositoryIds()).contains("*")
                          || nvl(a.repositoryIds()).contains(r.id()))
                      && (nvl(a.languages()).isEmpty() || nvl(a.languages()).contains(r.language()))
                      && (nvl(a.repositoryTypes()).isEmpty()
                          || nvl(a.repositoryTypes()).contains(r.type()));
            })
        .toList();
  }

  private List<Requirement> filterRequirements(
      List<Requirement> requirements, Set<String> applicableIds) {
    Stream<Requirement> stream = nvl(requirements).stream().filter(Requirement::implementable);
    if (applicableIds == null) return stream.toList();
    return stream.filter(r -> applicableIds.contains(r.id())).toList();
  }

  private List<Requirement> implementableRequirements(List<Requirement> requirements) {
    return nvl(requirements).stream().filter(Requirement::implementable).toList();
  }

  private List<AcceptanceCriterion> filterAcceptanceCriteria(
      List<AcceptanceCriterion> acceptanceCriteria, Set<String> applicableIds) {
    Stream<AcceptanceCriterion> stream =
        nvl(acceptanceCriteria).stream().filter(AcceptanceCriterion::implementable);
    if (applicableIds == null) return stream.toList();
    return stream.filter(c -> applicableIds.contains(c.id())).toList();
  }

  private List<AcceptanceCriterion> implementableAcceptanceCriteria(
      List<AcceptanceCriterion> acceptanceCriteria) {
    return nvl(acceptanceCriteria).stream().filter(AcceptanceCriterion::implementable).toList();
  }

  private List<Constraint> implementableConstraints(List<Constraint> constraints) {
    return nvl(constraints).stream().filter(Constraint::implementable).toList();
  }

  private List<ChangeLogEntry> recentChangeLog(List<ChangeLogEntry> changeLog) {
    List<ChangeLogEntry> changes = nvl(changeLog);
    int from = Math.max(0, changes.size() - 5);
    return changes.subList(from, changes.size());
  }

  private SpecSummary summary(Spec spec) {
    return new SpecSummary(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.owner(),
        spec.problem(),
        spec.businessGoal(),
        nvl(spec.affectedRepositories()),
        nvl(spec.relatedAdrs()),
        spec.sourcePath());
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static <T> List<T> nvl(List<T> x) {
    return x == null ? List.of() : x;
  }

  private WorkspaceFingerprint fingerprint() {
    long count = 0;
    long size = 0;
    long modified = 0;
    Path dir = root.resolve(".archcontext");
    if (!Files.isDirectory(dir)) return new WorkspaceFingerprint(0, 0, 0);
    try (var paths = Files.walk(dir)) {
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
    } catch (Exception e) {
      throw new IllegalStateException("Cannot fingerprint workspace: " + e.getMessage(), e);
    }
    return new WorkspaceFingerprint(count, size, modified);
  }

  private record WorkspaceFingerprint(long fileCount, long totalSize, long maxModifiedMillis) {}

  private record SolutionContextCache(
      WorkspaceFingerprint fingerprint, SolutionContext context) {}
}
