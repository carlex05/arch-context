package dev.archcontext;

import static org.junit.jupiter.api.Assertions.*;

import dev.archcontext.domain.Models.*;
import dev.archcontext.service.*;
import dev.archcontext.storage.Database;
import dev.archcontext.yaml.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ArchContextCoreTest {
  Path dir;

  @BeforeEach
  void setup() throws Exception {
    dir = Files.createTempDirectory("archcontext-test");
  }

  @Test
  void initCreatesExpectedFilesAndDirectories() throws Exception {
    new WorkspaceService().init(dir);
    Path ac = dir.resolve(".archcontext");
    assertTrue(Files.exists(ac.resolve("solution.yaml")));
    assertTrue(Files.exists(ac.resolve("repositories.yaml")));
    assertTrue(Files.exists(ac.resolve("local.yaml")));
    assertTrue(Files.isDirectory(ac.resolve("specs")));
    assertTrue(Files.readString(ac.resolve(".gitignore")).contains("archcontext.db"));
  }

  @Test
  void yamlSolutionAndRepositoryParsingAndLocalOverride() throws Exception {
    writeWorkspace(dir);
    YamlMapper y = new YamlMapper();
    assertEquals(
        "booking-platform", y.read(dir.resolve(".archcontext/solution.yaml")).solution.id());
    RepositoryService rs = new RepositoryService();
    assertEquals("booking-api", rs.list(dir).getFirst().id());
    assertEquals(
        Path.of("/tmp/booking-api"),
        rs.resolvePath(dir, rs.list(dir).getFirst(), rs.localOverrides(dir)));
  }

  @Test
  void importCreatesSchemaAndImportsSpecsAdrsGuidelinesSearchAndContext() throws Exception {
    writeWorkspace(dir);
    new ImportService().importWorkspace(dir);
    try (Connection c = new Database(dir.resolve(".archcontext/archcontext.db")).connect();
        ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM specs")) {
      assertTrue(r.next());
      assertEquals(1, r.getInt(1));
    }
    McpContextService m = new McpContextService(dir);
    assertFalse(m.searchContext("idempotent", List.of("spec")).isEmpty());
    ImplementationContext ctx = m.getImplementationContextForSpec("SPEC-001", "booking-api");
    assertEquals("SPEC-001", ctx.spec().id());
    assertEquals("ADR-001", ctx.relatedAdrs().getFirst().id());
    assertEquals("booking-api", ctx.affectedRepositories().getFirst().id());
    assertEquals("java-backend-guidelines", ctx.applicableGuidelines().getFirst().id());
    assertEquals("CON-001", ctx.structuredConstraints().getFirst().id());
    RepositoryImplementationContext repoCtx =
        m.getRepositoryImplementationContextForSpec("SPEC-001", "booking-api");
    assertEquals("booking-api", repoCtx.repository().id());
    assertEquals("FR-001", repoCtx.applicableFunctionalRequirements().getFirst().id());
    assertTrue(repoCtx.applicableNonFunctionalRequirements().isEmpty());
    assertEquals("AC-001", repoCtx.applicableAcceptanceCriteria().getFirst().id());
    assertEquals(
        "REST POST /bookings/{id}/cancel",
        repoCtx.repositoryChange().contractsProvided().getFirst());
    assertEquals("booking-api", m.resolveRepositoryByPath("/tmp/booking-api/src/main").id());
    assertTrue(m.validateSpecCompleteness("SPEC-001").missingSections().isEmpty());
  }

  @Test
  void workspaceValidationRequiresImportedDatabase() throws Exception {
    new WorkspaceService().init(dir);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new WorkspaceService().requireImportedWorkspace(dir));

    assertTrue(e.getMessage().contains("archcontext.db"));
    assertTrue(e.getMessage().contains("archcontext import"));
  }

  private static void writeWorkspace(Path root) throws Exception {
    Path ac = root.resolve(".archcontext");
    Files.createDirectories(ac.resolve("specs"));
    Files.createDirectories(ac.resolve("adrs"));
    Files.createDirectories(ac.resolve("guidelines"));
    Files.writeString(
        ac.resolve("solution.yaml"),
        "schemaVersion: \"1.0\"\n"
            + "solution:\n"
            + "  id: booking-platform\n"
            + "  name: Booking Platform\n"
            + "  description: Platform responsible for booking.\n"
            + "principles:\n"
            + "  - id: domain-first\n"
            + "    title: Domain-first design\n"
            + "    description: Business capabilities guide boundaries.\n");
    Files.writeString(
        ac.resolve("repositories.yaml"),
        "schemaVersion: \"1.0\"\n"
            + "repositories:\n"
            + "  - id: booking-api\n"
            + "    name: Booking API\n"
            + "    path: ../booking-api\n"
            + "    type: backend\n"
            + "    language: java\n"
            + "    boundedContext: booking\n"
            + "    description: Handles booking lifecycle.\n");
    Files.writeString(
        ac.resolve("local.yaml"),
        "schemaVersion: \"1.0\"\nlocalRepositories:\n  booking-api:\n    path: /tmp/booking-api\n");
    Files.writeString(
        ac.resolve("specs/SPEC-001.yaml"),
        "schemaVersion: \"1.0\"\n"
            + "spec:\n"
            + "  id: SPEC-001\n"
            + "  title: Partial booking cancellation\n"
            + "  status: draft\n"
            + "  owner: architecture-team\n"
            + "  problem: Customers need partial cancellation.\n"
            + "  businessGoal: Reduce support intervention.\n"
            + "  affectedRepositories: [booking-api]\n"
            + "  affectedBoundedContexts: [booking]\n"
            + "  functionalRequirements:\n"
            + "    - id: FR-001\n"
            + "      description: Allow partial cancellation.\n"
            + "  nonFunctionalRequirements:\n"
            + "    - id: NFR-001\n"
            + "      description: The cancellation operation should be idempotent.\n"
            + "  acceptanceCriteria:\n"
            + "    - id: AC-001\n"
            + "      description: Remaining items stay active.\n"
            + "  structuredConstraints:\n"
            + "    - id: CON-001\n"
            + "      title: Payment ownership\n"
            + "      description: Booking API must not directly access payment database tables.\n"
            + "  repositoryChanges:\n"
            + "    - repositoryId: booking-api\n"
            + "      role: backend\n"
            + "      summary: Add backend cancellation behavior.\n"
            + "      requirements: [FR-001]\n"
            + "      acceptanceCriteria: [AC-001]\n"
            + "      contractsProvided:\n"
            + "        - REST POST /bookings/{id}/cancel\n"
            + "  relatedAdrs: [ADR-001]\n");
    Files.writeString(
        ac.resolve("adrs/ADR-001.yaml"),
        "schemaVersion: \"1.0\"\n"
            + "adr:\n"
            + "  id: ADR-001\n"
            + "  title: Use hexagonal architecture\n"
            + "  status: accepted\n"
            + "  date: \"2026-05-18\"\n"
            + "  context: Need to isolate business rules.\n"
            + "  decision: Use hexagonal architecture.\n"
            + "  consequences:\n"
            + "    - Domain logic must not depend on controllers.\n"
            + "  affectedRepositories: [booking-api]\n"
            + "  relatedSpecs: [SPEC-001]\n");
    Files.writeString(
        ac.resolve("guidelines/java.yaml"),
        "schemaVersion: \"1.0\"\n"
            + "guideline:\n"
            + "  id: java-backend-guidelines\n"
            + "  title: Java backend guidelines\n"
            + "  appliesTo:\n"
            + "    languages: [java]\n"
            + "    repositoryTypes: [backend]\n"
            + "  rules:\n"
            + "    - id: JAVA-001\n"
            + "      description: Keep domain logic independent.\n");
  }
}
