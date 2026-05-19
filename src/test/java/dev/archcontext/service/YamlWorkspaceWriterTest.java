package dev.archcontext.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.archcontext.domain.Models.*;
import dev.archcontext.storage.Database;
import dev.archcontext.yaml.YamlMapper;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;

class YamlWorkspaceWriterTest {
  Path root;
  YamlWorkspaceWriter writer;
  YamlMapper yaml;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createTempDirectory("archcontext-write-test");
    new WorkspaceService().init(root);
    writer = new YamlWorkspaceWriter(root);
    yaml = new YamlMapper();
  }

  @Test
  void upsertRepositoryCreatesRepositoriesYamlEntry() throws Exception {
    WriteResult result = writer.upsertRepository(repository("booking-api", "Booking API"), false);

    assertTrue(result.changed());
    assertTrue(result.validation().errors().isEmpty());
    assertEquals(List.of(".archcontext/repositories.yaml"), result.updatedFiles());
    assertEquals(
        "booking-api",
        yaml.read(root.resolve(".archcontext/repositories.yaml")).repositories.getFirst().id());
  }

  @Test
  void upsertRepositoryUpdatesExistingRepository() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result =
        writer.upsertRepository(repository("booking-api", "Booking Service API"), false);

    assertTrue(result.changed());
    assertEquals(
        "Booking Service API",
        yaml.read(root.resolve(".archcontext/repositories.yaml")).repositories.getFirst().name());
  }

  @Test
  void createSpecWritesUnderSpecsDirectory() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result = writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    assertTrue(result.changed());
    assertTrue(Files.exists(root.resolve(".archcontext/specs/spec-001.yaml")));
    assertEquals(List.of(".archcontext/specs/spec-001.yaml"), result.updatedFiles());
  }

  @Test
  void createSpecRejectsUnknownRepositoryReference() {
    WriteResult result = writer.createSpec(spec("SPEC-001", List.of("unknown-api")), false);

    assertFalse(result.changed());
    assertFalse(result.validation().errors().isEmpty());
    assertTrue(result.validation().errors().getFirst().contains("Unknown affected repository"));
    assertFalse(Files.exists(root.resolve(".archcontext/specs/spec-001.yaml")));
  }

  @Test
  void upsertSpecRequirementAddsFunctionalRequirement() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecRequirement(
            "SPEC-001",
            "functional",
            new Requirement("FR-002", "Allow partial cancellation."),
            false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals("FR-002", spec.functionalRequirements().getFirst().id());
  }

  @Test
  void dryRunDoesNotModifyFiles() throws Exception {
    String before = Files.readString(root.resolve(".archcontext/repositories.yaml"));

    WriteResult result = writer.upsertRepository(repository("booking-api", "Booking API"), true);

    assertTrue(result.changed());
    assertTrue(result.dryRun());
    assertEquals(before, Files.readString(root.resolve(".archcontext/repositories.yaml")));
    assertFalse(Files.exists(root.resolve(".archcontext/archcontext.db")));
  }

  @Test
  void writerCannotWriteOutsideArchContext() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> writer.validateKnownWriteTarget(root.resolve("outside.yaml")));

    assertTrue(e.getMessage().contains("under"));
  }

  @Test
  void importIndexRunsAfterSuccessfulWrite() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    try (Connection c = new Database(root.resolve(".archcontext/archcontext.db")).connect();
        ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM repositories")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void existingYamlOnePointZeroSampleRemainsReadable() {
    Path sample = Path.of("examples/sample-workspace").toAbsolutePath().normalize();

    assertDoesNotThrow(() -> new ImportService().importWorkspace(sample));
    assertDoesNotThrow(() -> new McpContextService(sample).getSolutionContext());
  }

  private static RepositoryDefinition repository(String id, String name) {
    return new RepositoryDefinition(
        id,
        name,
        "../" + id,
        "backend",
        "java",
        "booking",
        "Handles booking.",
        List.of(new Responsibility("RESP-001", "Own booking behavior.")),
        List.of(
            new Component(
                "booking-domain",
                "Booking Domain",
                "domain",
                "Booking business rules.",
                List.of("RESP-001"))));
  }

  private static Spec spec(String id, List<String> repositories) {
    return new Spec(
        id,
        "Partial booking cancellation",
        "draft",
        "architecture-team",
        "Customers need partial cancellation.",
        "Reduce support intervention.",
        repositories,
        List.of("booking"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(new Constraint("CON-001", "Payment ownership", "Do not access payment tables.")),
        List.of(new ComponentRef("booking-api", "booking-domain")),
        List.of(new OutOfScopeItem("Loyalty refunds are out of scope.")),
        List.of(new OpenQuestion("OQ-001", "Should provider fees be shown?")),
        List.of(),
        null);
  }
}
