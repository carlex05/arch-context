package dev.archcontext.yaml;
import dev.archcontext.domain.Models.*;
import java.util.*;
public class YamlDocuments {
  public String schemaVersion = "1.0";
  public Solution solution;
  public List<Principle> principles = new ArrayList<>();
  public List<RepositoryDefinition> repositories = new ArrayList<>();
  public Map<String, LocalRepositoryOverride> localRepositories = new LinkedHashMap<>();
  public Spec spec;
  public Adr adr;
  public Guideline guideline;
}
