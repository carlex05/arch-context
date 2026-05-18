package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import dev.archcontext.yaml.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class WorkspaceService {
  public static final String DIR = ".archcontext";
  private final YamlMapper yaml = new YamlMapper();

  public ArchContextWorkspace current() {
    Path root = Path.of("").toAbsolutePath().normalize();
    return new ArchContextWorkspace(root, root.resolve(DIR));
  }

  public void init(Path root) throws IOException {
    Path dir = root.resolve(DIR);
    Files.createDirectories(dir.resolve("specs"));
    Files.createDirectories(dir.resolve("adrs"));
    Files.createDirectories(dir.resolve("guidelines"));
    writeIfMissing(dir.resolve("solution.yaml"), defaultSolution());
    writeIfMissing(dir.resolve("repositories.yaml"), defaultRepos());
    writeIfMissing(dir.resolve("local.yaml"), "schemaVersion: \"1.0\"\nlocalRepositories: {}\n");
    Path gi = dir.resolve(".gitignore");
    Set<String> lines = new LinkedHashSet<>();
    if (Files.exists(gi)) lines.addAll(Files.readAllLines(gi));
    lines.add("local.yaml");
    lines.add("archcontext.db");
    Files.write(
        gi, String.join(System.lineSeparator(), lines).concat(System.lineSeparator()).getBytes());
  }

  private void writeIfMissing(Path p, String s) throws IOException {
    if (!Files.exists(p)) Files.writeString(p, s);
  }

  private String defaultSolution() {
    return "schemaVersion: \"1.0\"\n"
               + "solution:\n"
               + "  id: archcontext-solution\n"
               + "  name: ArchContext Solution\n"
               + "  description: >\n"
               + "    Describe the multi-repository solution here.\n"
               + "principles:\n"
               + "  - id: explicit-decisions\n"
               + "    title: Explicit architectural decisions\n"
               + "    description: >\n"
               + "      Relevant architectural decisions should be documented as ADRs.\n";
  }

  private String defaultRepos() {
    return "schemaVersion: \"1.0\"\nrepositories: []\n";
  }

  public void requireWorkspace(Path root) {
    if (!Files.isDirectory(root.resolve(DIR)))
      throw new IllegalStateException(
          "Missing .archcontext directory. Run archcontext init first.");
  }

  public Path dbPath(Path root) {
    return root.resolve(DIR).resolve("archcontext.db");
  }
}
