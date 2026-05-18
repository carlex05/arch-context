package dev.archcontext.service;

import dev.archcontext.domain.Models.*;import dev.archcontext.yaml.*;import java.io.*;import java.nio.file.*;import java.util.*;

public class RepositoryService {
  private final YamlMapper yaml = new YamlMapper();
  public List<RepositoryDefinition> list(Path root) throws IOException { Path p=root.resolve(".archcontext/repositories.yaml"); if(!Files.exists(p)) return List.of(); YamlDocuments d=yaml.read(p); return d.repositories==null?List.of():d.repositories; }
  public Map<String, LocalRepositoryOverride> localOverrides(Path root)throws IOException{ Path p=root.resolve(".archcontext/local.yaml"); if(!Files.exists(p)) return Map.of(); YamlDocuments d=yaml.read(p); return d.localRepositories==null?Map.of():d.localRepositories; }
  public Path resolvePath(Path root, RepositoryDefinition repo, Map<String, LocalRepositoryOverride> overrides){ LocalRepositoryOverride o=overrides.get(repo.id()); String raw=o!=null?o.path():repo.path(); if(raw==null||raw.isBlank()) return null; Path p=Path.of(raw); return p.isAbsolute()?p.normalize():root.resolve(p).normalize(); }
  public void add(Path root, RepositoryDefinition repo) throws IOException { Path p=root.resolve(".archcontext/repositories.yaml"); YamlDocuments d=Files.exists(p)?yaml.read(p):new YamlDocuments(); if(d.repositories==null)d.repositories=new ArrayList<>(); d.repositories.removeIf(r -> r.id().equals(repo.id())); d.repositories.add(repo); yaml.write(p,d); }
}
