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
  void upsertSpecAcceptanceCriterionAddsCriterion() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecAcceptanceCriterion(
            "SPEC-001", new AcceptanceCriterion("AC-001", "Remaining items stay active."), false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals("AC-001", spec.acceptanceCriteria().getFirst().id());
  }

  @Test
  void upsertSpecAcceptanceCriterionUpdatesById() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.upsertSpecAcceptanceCriterion(
        "SPEC-001", new AcceptanceCriterion("AC-001", "Old description."), false);

    WriteResult result =
        writer.upsertSpecAcceptanceCriterion(
            "SPEC-001", new AcceptanceCriterion("AC-001", "New description."), false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals(1, spec.acceptanceCriteria().size());
    assertEquals("New description.", spec.acceptanceCriteria().getFirst().description());
  }

  @Test
  void addSpecOutOfScopeItemAddsItem() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.addSpecOutOfScopeItem("SPEC-001", new OutOfScopeItem("Loyalty refunds."), false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals("Loyalty refunds.", spec.outOfScope().getLast().description());
  }

  @Test
  void duplicateOutOfScopeItemDoesNotCreateDuplication() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.addSpecOutOfScopeItem("SPEC-001", new OutOfScopeItem("Loyalty refunds."), false);

    WriteResult result =
        writer.addSpecOutOfScopeItem("SPEC-001", new OutOfScopeItem("loyalty refunds."), false);

    assertFalse(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals(2, spec.outOfScope().size());
  }

  @Test
  void upsertSpecConstraintAddsStructuredConstraint() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecConstraint(
            "SPEC-001", new Constraint("CON-002", "Payments", "Do not access payment DB."), false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals("CON-002", spec.structuredConstraints().getLast().id());
    assertTrue(spec.constraints().isEmpty());
  }

  @Test
  void upsertSpecConstraintUpdatesById() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.upsertSpecConstraint(
        "SPEC-001", new Constraint("CON-002", "Payments", "Old."), false);

    WriteResult result =
        writer.upsertSpecConstraint(
            "SPEC-001", new Constraint("CON-002", "Payments", "New."), false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals(2, spec.structuredConstraints().size());
    assertEquals("New.", spec.structuredConstraints().getLast().description());
  }

  @Test
  void upsertSpecRepositoryChangeAddsRepositoryScopedPlan() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecRepositoryChange(
            "SPEC-001",
            repositoryChange("booking-api", List.of("FR-001"), List.of("AC-001")),
            false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals("booking-api", spec.repositoryChanges().getFirst().repositoryId());
    assertEquals(List.of("FR-001"), spec.repositoryChanges().getFirst().requirements());
  }

  @Test
  void upsertSpecRepositoryChangeUpdatesByRepositoryId() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    writer.upsertSpecRepositoryChange(
        "SPEC-001", repositoryChange("booking-api", List.of("FR-001"), List.of("AC-001")), false);

    WriteResult result =
        writer.upsertSpecRepositoryChange(
            "SPEC-001", repositoryChange("booking-api", List.of("NFR-001"), List.of("AC-001")), false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals(1, spec.repositoryChanges().size());
    assertEquals(List.of("NFR-001"), spec.repositoryChanges().getFirst().requirements());
  }

  @Test
  void upsertSpecRepositoryChangeRejectsUnknownRequirement() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecRepositoryChange(
            "SPEC-001",
            repositoryChange("booking-api", List.of("FR-999"), List.of("AC-001")),
            false);

    assertFalse(result.changed());
    assertTrue(
        result.validation().errors().stream()
            .anyMatch(e -> e.contains("Unknown repositoryChange requirement")));
  }

  @Test
  void upsertSpecRepositoryChangeRejectsRepositoryOutsideAffectedRepositories() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.upsertRepository(repository("payment-service", "Payment Service"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecRepositoryChange(
            "SPEC-001",
            repositoryChange("payment-service", List.of("FR-001"), List.of("AC-001")),
            false);

    assertFalse(result.changed());
    assertTrue(
        result.validation().errors().stream()
            .anyMatch(e -> e.contains("affectedRepositories")));
  }

  @Test
  void dryRunRepositoryChangeDoesNotModifySpecFile() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    Path specPath = root.resolve(".archcontext/specs/spec-001.yaml");
    String before = Files.readString(specPath);

    WriteResult result =
        writer.upsertSpecRepositoryChange(
            "SPEC-001",
            repositoryChange("booking-api", List.of("FR-001"), List.of("AC-001")),
            true);

    assertTrue(result.changed());
    assertEquals(before, Files.readString(specPath));
  }

  @Test
  void validateWorkspaceWarnsWhenMultiRepositorySpecHasNoRepositoryChanges() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.upsertRepository(repository("payment-service", "Payment Service"), false);
    writer.createSpec(
        specWithImplementationScope("SPEC-001", List.of("booking-api", "payment-service")),
        false);

    WriteValidation result = writer.validateWorkspace(false);

    assertTrue(
        result.warnings().stream()
            .anyMatch(w -> w.contains("Multi-repository spec has no repositoryChanges")));
  }

  @Test
  void strictValidateWorkspaceErrorsWhenRequirementIsNotAssignedToRepositoryChange()
      throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    writer.upsertSpecRepositoryChange(
        "SPEC-001", repositoryChange("booking-api", List.of("FR-001"), List.of("AC-001")), false);

    WriteValidation result = writer.validateWorkspace(true);

    assertTrue(
        result.errors().stream()
            .anyMatch(e -> e.contains("Requirement is not assigned")));
  }

  @Test
  void validateWorkspaceDetectsUnknownRepositoryReference() throws Exception {
    writer.createSpec(spec("SPEC-001", List.of("unknown-api")), true);
    Path specPath = root.resolve(".archcontext/specs/spec-001.yaml");
    Files.createDirectories(specPath.getParent());
    YamlMapper mapper = new YamlMapper();
    var doc = new dev.archcontext.yaml.YamlDocuments();
    doc.schemaVersion = "1.1";
    doc.spec = spec("SPEC-001", List.of("unknown-api"));
    mapper.write(specPath, doc);

    WriteValidation result = writer.validateWorkspace(false);

    assertTrue(result.errors().stream().anyMatch(e -> e.contains("unknown-api")));
  }

  @Test
  void validateWorkspaceWarnsWhenActiveSpecHasNoAcceptanceCriteria() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    Spec active =
        new Spec(
            "SPEC-002",
            "Active spec",
            "active",
            "team",
            "Problem",
            "Goal",
            List.of("booking-api"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);
    Path specPath = root.resolve(".archcontext/specs/spec-002.yaml");
    Files.createDirectories(specPath.getParent());
    var doc = new dev.archcontext.yaml.YamlDocuments();
    doc.schemaVersion = "1.1";
    doc.spec = active;
    yaml.write(specPath, doc);

    WriteValidation result = writer.validateWorkspace(false);

    assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no acceptance criteria")));
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
  void dryRunSpecEnrichmentDoesNotModifyFiles() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    Path specPath = root.resolve(".archcontext/specs/spec-001.yaml");
    String before = Files.readString(specPath);

    WriteResult result =
        writer.upsertSpecAcceptanceCriterion(
            "SPEC-001", new AcceptanceCriterion("AC-001", "Preview only."), true);

    assertTrue(result.changed());
    assertEquals(before, Files.readString(specPath));
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
  void importIndexRunsAfterSuccessfulSpecEnrichment() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    writer.upsertSpecAcceptanceCriterion(
        "SPEC-001", new AcceptanceCriterion("AC-001", "Remaining items stay active."), false);

    try (Connection c = new Database(root.resolve(".archcontext/archcontext.db")).connect();
        ResultSet rs =
            c.createStatement()
                .executeQuery("SELECT content FROM documents WHERE document_key='SPEC-001'")) {
      assertTrue(rs.next());
      assertTrue(rs.getString(1).contains("Remaining items stay active"));
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

  private static Spec specWithImplementationScope(String id, List<String> repositories) {
    return new Spec(
        id,
        "Partial booking cancellation",
        "draft",
        "architecture-team",
        "Customers need partial cancellation.",
        "Reduce support intervention.",
        repositories,
        List.of("booking"),
        List.of(new Requirement("FR-001", "Cancel selected booking items.")),
        List.of(new Requirement("NFR-001", "Keep cancellation latency under 500ms.")),
        List.of(new AcceptanceCriterion("AC-001", "Remaining items stay active.")),
        List.of(),
        List.of(new Constraint("CON-001", "Payment ownership", "Do not access payment tables.")),
        List.of(new ComponentRef("booking-api", "booking-domain")),
        List.of(new OutOfScopeItem("Loyalty refunds are out of scope.")),
        List.of(new OpenQuestion("OQ-001", "Should provider fees be shown?")),
        List.of(),
        null);
  }

  private static RepositoryChange repositoryChange(
      String repositoryId, List<String> requirements, List<String> acceptanceCriteria) {
    return new RepositoryChange(
        repositoryId,
        "backend",
        "Implement repository-specific booking cancellation behavior.",
        requirements,
        acceptanceCriteria,
        List.of("REST POST /bookings/{id}/cancel"),
        List.of(),
        List.of("Do not change payment refund orchestration."));
  }
}
