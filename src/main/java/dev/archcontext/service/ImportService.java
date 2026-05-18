package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import dev.archcontext.storage.Database;
import dev.archcontext.util.Json;
import dev.archcontext.yaml.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import java.util.stream.*;

public class ImportService {
  private final YamlMapper yaml = new YamlMapper();
  private final RepositoryService repoService = new RepositoryService();

  public void importWorkspace(Path root) {
    try {
      Path dir = root.resolve(".archcontext");
      Database db = new Database(dir.resolve("archcontext.db"));
      db.migrate();
      try (Connection c = db.connect()) {
        c.setAutoCommit(false);
        clear(c);
        importSolution(c, dir);
        importRepositories(c, root);
        importDocumentDir(c, dir.resolve("specs"), "spec");
        importDocumentDir(c, dir.resolve("adrs"), "adr");
        importDocumentDir(c, dir.resolve("guidelines"), "guideline");
        c.commit();
      }
    } catch (Exception e) {
      throw new IllegalStateException("Import failed: " + e.getMessage(), e);
    }
  }

  private void clear(Connection c) throws SQLException {
    for (String t :
        List.of(
            "principles",
            "solution",
            "repositories",
            "specs",
            "adrs",
            "guidelines",
            "documents",
            "document_chunks",
            "spec_repository_impact",
            "adr_repository_impact"))
      try (Statement s = c.createStatement()) {
        s.executeUpdate("DELETE FROM " + t);
      }
  }

  private void importSolution(Connection c, Path dir) throws Exception {
    Path p = dir.resolve("solution.yaml");
    if (!Files.exists(p)) return;
    YamlDocuments d = yaml.read(p);
    if (d.solution != null) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO solution(id,name,description,source_path) VALUES(?,?,?,?)")) {
        ps.setString(1, d.solution.id());
        ps.setString(2, d.solution.name());
        ps.setString(3, d.solution.description());
        ps.setString(4, p.toString());
        ps.executeUpdate();
      }
      upsertDoc(c, "solution", d.solution.id(), d.solution.name(), p, Files.readString(p));
    }
    if (d.principles != null)
      for (Principle pr : d.principles) {
        try (PreparedStatement ps =
            c.prepareStatement("INSERT INTO principles(id,title,description) VALUES(?,?,?)")) {
          ps.setString(1, pr.id());
          ps.setString(2, pr.title());
          ps.setString(3, pr.description());
          ps.executeUpdate();
        }
      }
  }

  private void importRepositories(Connection c, Path root) throws Exception {
    Map<String, LocalRepositoryOverride> overrides = repoService.localOverrides(root);
    for (RepositoryDefinition r : repoService.list(root)) {
      Path resolved = repoService.resolvePath(root, r, overrides);
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO"
                  + " repositories(id,name,path,resolved_path,type,language,bounded_context,description)"
                  + " VALUES(?,?,?,?,?,?,?,?)")) {
        ps.setString(1, r.id());
        ps.setString(2, r.name());
        ps.setString(3, r.path());
        ps.setString(4, resolved == null ? null : resolved.toString());
        ps.setString(5, r.type());
        ps.setString(6, r.language());
        ps.setString(7, r.boundedContext());
        ps.setString(8, r.description());
        ps.executeUpdate();
      }
    }
    Path p = root.resolve(".archcontext/repositories.yaml");
    if (Files.exists(p))
      upsertDoc(c, "repositories", "repositories", "Repositories", p, Files.readString(p));
  }

  private void importDocumentDir(Connection c, Path dir, String type) throws Exception {
    if (!Files.isDirectory(dir)) return;
    try (Stream<Path> paths = Files.list(dir)) {
      for (Path p :
          paths
              .filter(x -> x.toString().endsWith(".yaml") || x.toString().endsWith(".yml"))
              .sorted()
              .toList()) {
        YamlDocuments d = yaml.read(p);
        String content = Files.readString(p);
        if ("spec".equals(type) && d.spec != null) importSpec(c, d.spec, p, content);
        if ("adr".equals(type) && d.adr != null) importAdr(c, d.adr, p, content);
        if ("guideline".equals(type) && d.guideline != null)
          importGuideline(c, d.guideline, p, content);
      }
    }
  }

  private void importSpec(Connection c, Spec s, Path p, String content) throws Exception {
    upsertDoc(c, "spec", s.id(), s.title(), p, content);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO"
                + " specs(id,title,status,owner,problem,business_goal,affected_bounded_contexts,constraints_json,related_adrs_json,source_path)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, s.id());
      ps.setString(2, s.title());
      ps.setString(3, s.status());
      ps.setString(4, s.owner());
      ps.setString(5, s.problem());
      ps.setString(6, s.businessGoal());
      ps.setString(7, Json.write(nvl(s.affectedBoundedContexts())));
      ps.setString(8, Json.write(nvl(s.constraints())));
      ps.setString(9, Json.write(nvl(s.relatedAdrs())));
      ps.setString(10, p.toString());
      ps.executeUpdate();
    }
    for (String rid : nvl(s.affectedRepositories()))
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO spec_repository_impact(spec_id,repository_id) VALUES(?,?)")) {
        ps.setString(1, s.id());
        ps.setString(2, rid);
        ps.executeUpdate();
      }
  }

  private void importAdr(Connection c, Adr a, Path p, String content) throws Exception {
    upsertDoc(c, "adr", a.id(), a.title(), p, content);
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO"
                + " adrs(id,title,status,date,context,decision,consequences_json,related_specs_json,source_path)"
                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
      ps.setString(1, a.id());
      ps.setString(2, a.title());
      ps.setString(3, a.status());
      ps.setString(4, a.date());
      ps.setString(5, a.context());
      ps.setString(6, a.decision());
      ps.setString(7, Json.write(nvl(a.consequences())));
      ps.setString(8, Json.write(nvl(a.relatedSpecs())));
      ps.setString(9, p.toString());
      ps.executeUpdate();
    }
    for (String rid : nvl(a.affectedRepositories()))
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO adr_repository_impact(adr_id,repository_id) VALUES(?,?)")) {
        ps.setString(1, a.id());
        ps.setString(2, rid);
        ps.executeUpdate();
      }
  }

  private void importGuideline(Connection c, Guideline g, Path p, String content) throws Exception {
    upsertDoc(c, "guideline", g.id(), g.title(), p, content);
    List<String> langs = g.appliesTo() == null ? List.of() : nvl(g.appliesTo().languages());
    List<String> types = g.appliesTo() == null ? List.of() : nvl(g.appliesTo().repositoryTypes());
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO"
                + " guidelines(id,title,languages_json,repository_types_json,rules_json,source_path)"
                + " VALUES(?,?,?,?,?,?)")) {
      ps.setString(1, g.id());
      ps.setString(2, g.title());
      ps.setString(3, Json.write(langs));
      ps.setString(4, Json.write(types));
      ps.setString(5, Json.write(nvl(g.rules())));
      ps.setString(6, p.toString());
      ps.executeUpdate();
    }
  }

  private void upsertDoc(
      Connection c, String type, String key, String title, Path path, String content)
      throws Exception {
    long id;
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO documents(type,document_key,title,path,content,hash) VALUES(?,?,?,?,?,?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, type);
      ps.setString(2, key);
      ps.setString(3, title);
      ps.setString(4, path.toString());
      ps.setString(5, content);
      ps.setString(6, hash(content));
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        rs.next();
        id = rs.getLong(1);
      }
    }
    int i = 0;
    for (String chunk : chunks(content)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO"
                  + " document_chunks(document_id,type,document_key,title,path,chunk_index,content)"
                  + " VALUES(?,?,?,?,?,?,?)")) {
        ps.setLong(1, id);
        ps.setString(2, type);
        ps.setString(3, key);
        ps.setString(4, title);
        ps.setString(5, path.toString());
        ps.setInt(6, i++);
        ps.setString(7, chunk);
        ps.executeUpdate();
      }
    }
  }

  private List<String> chunks(String content) {
    List<String> out = new ArrayList<>();
    String[] parts = content.split("\\n\\s*\\n");
    StringBuilder b = new StringBuilder();
    for (String p : parts) {
      if (b.length() + p.length() > 1200) {
        out.add(b.toString());
        b.setLength(0);
      }
      if (!b.isEmpty()) b.append("\n\n");
      b.append(p);
    }
    if (!b.isEmpty()) out.add(b.toString());
    return out.isEmpty() ? List.of(content) : out;
  }

  private String hash(String s) throws Exception {
    byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
    StringBuilder b = new StringBuilder();
    for (byte x : d) b.append(String.format("%02x", x));
    return b.toString();
  }

  private static <T> List<T> nvl(List<T> l) {
    return l == null ? List.of() : l;
  }
}
