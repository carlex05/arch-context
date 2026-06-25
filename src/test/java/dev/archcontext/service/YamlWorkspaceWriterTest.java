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
  void upsertSolutionWritesPrinciplesAndGlossary() throws Exception {
    Solution solution =
        new Solution(
            "front9",
            "Front 9",
            "Frontend workspace.",
            "Enable autonomous delivery.",
            List.of(new CrossCuttingConcern("i18n", "Internationalization", "Spanish specs.")),
            List.of(new GlossaryTerm("Front_9", "Current frontend workspace.")));

    WriteResult result =
        writer.upsertSolution(
            solution,
            List.of(
                new Principle(
                    "tdd",
                    "TDD",
                    "Use red-green-refactor.",
                    "Keep changes testable.",
                    List.of("*"))),
            false);

    assertTrue(result.changed());
    var doc = yaml.read(root.resolve(".archcontext/solution.yaml"));
    assertEquals("Enable autonomous delivery.", doc.solution.vision());
    assertEquals("tdd", doc.principles.getFirst().id());
    assertEquals("Front_9", doc.solution.glossary().getFirst().term());
  }

  @Test
  void upsertSolutionPrincipleUpdatesById() throws Exception {
    writer.upsertSolutionPrinciple(
        new Principle("hexagonal", "Hexagonal", "Old.", "Old rationale.", List.of("*")), false);

    WriteResult result =
        writer.upsertSolutionPrinciple(
            new Principle(
                "hexagonal",
                "Hexagonal architecture",
                "Keep domain isolated.",
                "Ports and adapters preserve boundaries.",
                List.of("booking-api")),
            false);

    assertTrue(result.changed());
    var principle =
        yaml.read(root.resolve(".archcontext/solution.yaml")).principles.stream()
            .filter(p -> p.id().equals("hexagonal"))
            .findFirst()
            .orElseThrow();
    assertEquals("Hexagonal architecture", principle.title());
    assertEquals(List.of("booking-api"), principle.appliesTo());
  }

  @Test
  void upsertSolutionGlossaryTermUpdatesByTerm() throws Exception {
    writer.upsertSolutionGlossaryTerm(new GlossaryTerm("dbt_test", "Old."), false);

    WriteResult result =
        writer.upsertSolutionGlossaryTerm(
            new GlossaryTerm("dbt_test", "Database test.", List.of("DBT"), List.of("qc_type")),
            false);

    assertTrue(result.changed());
    List<GlossaryTerm> glossary = yaml.read(root.resolve(".archcontext/solution.yaml")).solution.glossary();
    assertEquals(1, glossary.size());
    assertEquals("Database test.", glossary.getFirst().definition());
  }

  @Test
  void upsertRepositoryComponentAddsComponent() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result =
        writer.upsertRepositoryComponent(
            "booking-api",
            new Component(
                "application-use-case",
                "Application Use Case",
                "layer",
                "src/main/java/app/application",
                "Application orchestration layer.",
                List.of("RESP-001"),
                List.of("booking-domain")),
            false);

    assertTrue(result.changed());
    Component component =
        yaml.read(root.resolve(".archcontext/repositories.yaml"))
            .repositories
            .getFirst()
            .components()
            .stream()
            .filter(c -> c.id().equals("application-use-case"))
            .findFirst()
            .orElseThrow();
    assertEquals("src/main/java/app/application", component.path());
  }

  @Test
  void upsertRepositoryResponsibilityAddsResponsibility() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result =
        writer.upsertRepositoryResponsibility(
            "booking-api",
            new Responsibility("RESP-002", "Own Flyway schema migrations.", "persistence"),
            false);

    assertTrue(result.changed());
    assertEquals(
        "persistence",
        yaml.read(root.resolve(".archcontext/repositories.yaml"))
            .repositories
            .getFirst()
            .responsibilities()
            .getLast()
            .category());
  }

  @Test
  void createGuidelineWritesGuidelineFile() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result = writer.createGuideline(guideline("guideline-testing-be"), false);

    assertTrue(result.changed());
    Guideline guideline =
        yaml.read(root.resolve(".archcontext/guidelines/guideline-testing-be.yaml")).guideline;
    assertEquals("testing", guideline.category());
    assertEquals(List.of("booking-api"), guideline.appliesTo().repositoryIds());
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
  void createAdrWritesUnderAdrsDirectory() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result = writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);

    assertTrue(result.changed());
    assertTrue(Files.exists(root.resolve(".archcontext/adrs/adr-001.yaml")));
    assertEquals(List.of(".archcontext/adrs/adr-001.yaml"), result.updatedFiles());
    assertEquals("ADR-001", yaml.read(root.resolve(".archcontext/adrs/adr-001.yaml")).adr.id());
  }

  @Test
  void createAdrRejectsUnknownRepositoryReference() {
    WriteResult result = writer.createAdr(adr("ADR-001", List.of("unknown-api"), List.of()), false);

    assertFalse(result.changed());
    assertTrue(
        result.validation().errors().stream()
            .anyMatch(e -> e.contains("Unknown affected repository")));
    assertFalse(Files.exists(root.resolve(".archcontext/adrs/adr-001.yaml")));
  }

  @Test
  void createAdrRejectsUnknownRelatedSpec() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result =
        writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of("SPEC-404")), false);

    assertFalse(result.changed());
    assertTrue(
        result.validation().errors().stream().anyMatch(e -> e.contains("Unknown related spec")));
  }

  @Test
  void upsertAdrUpdatesExistingAdrById() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);

    WriteResult result =
        writer.upsertAdr(
            new Adr(
                "ADR-001",
                "Use layered architecture",
                "accepted",
                "2026-05-28",
                "Need clear module boundaries.",
                "Use layered architecture.",
                List.of("Controllers must not contain business logic."),
                List.of("booking-api"),
                List.of(),
                null),
            false);

    assertTrue(result.changed());
    Adr adr = yaml.read(root.resolve(".archcontext/adrs/adr-001.yaml")).adr;
    assertEquals("Use layered architecture", adr.title());
  }

  @Test
  void createAdrDryRunDoesNotWriteFile() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    WriteResult result = writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), true);

    assertTrue(result.changed());
    assertTrue(result.dryRun());
    assertFalse(Files.exists(root.resolve(".archcontext/adrs/adr-001.yaml")));
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
  void deprecateSpecRequirementKeepsRequirementButMarksItNonImplementable() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.deprecateSpecRequirement(
            "SPEC-001",
            "functional",
            "FR-001",
            "superseded",
            "Backend contract now uses the replacement flow.",
            "FR-002",
            "ADR-002",
            false);

    assertTrue(result.changed());
    Requirement requirement =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.functionalRequirements().getFirst();
    assertEquals("FR-001", requirement.id());
    assertEquals("superseded", requirement.status());
    assertEquals("Backend contract now uses the replacement flow.", requirement.obsoleteReason());
    assertEquals("FR-002", requirement.supersededBy());
    assertFalse(requirement.implementable());
  }

  @Test
  void deprecateSpecRequirementRejectsUnknownRequirement() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.deprecateSpecRequirement(
            "SPEC-001",
            "functional",
            "FR-404",
            "obsolete",
            "No longer needed.",
            null,
            null,
            false);

    assertFalse(result.changed());
    assertTrue(result.validation().errors().stream().anyMatch(e -> e.contains("FR-404")));
  }

  @Test
  void upsertSpecRequirementReindexesOnlyChangedSpec() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.createSpec(spec("SPEC-002", List.of("booking-api")), false);

    writer.upsertSpecRequirement(
        "SPEC-001", "functional", new Requirement("FR-002", "Allow partial cancellation."), false);
    writer.awaitPendingSpecIndexing();

    try (Connection c = new Database(root.resolve(".archcontext/archcontext.db")).connect()) {
      assertEquals(1, count(c, "SELECT COUNT(*) FROM specs WHERE id = 'SPEC-001'"));
      assertEquals(1, count(c, "SELECT COUNT(*) FROM specs WHERE id = 'SPEC-002'"));
      assertTrue(
          text(c, "SELECT content FROM documents WHERE type = 'spec' AND document_key = 'SPEC-001'")
              .contains("FR-002"));
      assertFalse(
          text(c, "SELECT content FROM documents WHERE type = 'spec' AND document_key = 'SPEC-002'")
              .contains("FR-002"));
    }
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
  void deprecateSpecAcceptanceCriterionKeepsCriterionButMarksItNonImplementable()
      throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.deprecateSpecAcceptanceCriterion(
            "SPEC-001",
            "AC-001",
            "superseded",
            "UI flow now validates the replacement acceptance criterion.",
            "AC-002",
            "ADR-002",
            false);

    assertTrue(result.changed());
    AcceptanceCriterion criterion =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.acceptanceCriteria().getFirst();
    assertEquals("AC-001", criterion.id());
    assertEquals("superseded", criterion.status());
    assertEquals("UI flow now validates the replacement acceptance criterion.", criterion.obsoleteReason());
    assertEquals("AC-002", criterion.supersededBy());
    assertFalse(criterion.implementable());
  }

  @Test
  void deprecateSpecAcceptanceCriterionRejectsUnknownCriterion() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.deprecateSpecAcceptanceCriterion(
            "SPEC-001", "AC-404", "obsolete", "No longer needed.", null, null, false);

    assertFalse(result.changed());
    assertTrue(result.validation().errors().stream().anyMatch(e -> e.contains("AC-404")));
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
  void deprecateSpecConstraintKeepsConstraintButMarksItNonImplementable() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.deprecateSpecConstraint(
            "SPEC-001",
            "CON-001",
            "obsolete",
            "Payment ownership is now covered by a broader platform guideline.",
            null,
            "ADR-002",
            false);

    assertTrue(result.changed());
    Constraint constraint =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.structuredConstraints().getFirst();
    assertEquals("CON-001", constraint.id());
    assertEquals("obsolete", constraint.status());
    assertEquals("Payment ownership is now covered by a broader platform guideline.", constraint.obsoleteReason());
    assertEquals("ADR-002", constraint.relatedAdr());
    assertFalse(constraint.implementable());
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
  void upsertSpecAffectedComponentAddsPathBreadcrumb() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecAffectedComponent(
            "SPEC-001",
            new ComponentRef(
                "booking-api",
                null,
                "src/main/java/BookingController.java",
                50,
                65,
                "modify",
                "Add cancellation audit status."),
            false);

    assertTrue(result.changed());
    ComponentRef ref =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.affectedComponents().getLast();
    assertEquals("src/main/java/BookingController.java", ref.path());
    assertEquals(50, ref.lineStart());
  }

  @Test
  void updateSpecStatusChangesExistingSpecStatus() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result = writer.updateSpecStatus("SPEC-001", "review", "Implementation done.", false);

    assertTrue(result.changed());
    assertEquals("review", yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.status());
  }

  @Test
  void upsertSpecMetadataAddsPlanningMetadata() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecMetadata(
            "SPEC-001", new SpecMetadata("high", 8.5, "S-42", "review", List.of("front9")), false);

    assertTrue(result.changed());
    SpecMetadata metadata = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.metadata();
    assertEquals("high", metadata.priority());
    assertEquals(8.5, metadata.effortHours());
  }

  @Test
  void upsertSpecSummaryUpdatesProvidedFieldsOnly() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.upsertSpecSummary("SPEC-001", "Updated title", null, null, "Updated goal.", false);

    assertTrue(result.changed());
    Spec spec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    assertEquals("Updated title", spec.title());
    assertEquals("architecture-team", spec.owner());
    assertEquals("Updated goal.", spec.businessGoal());
  }

  @Test
  void appendSpecChangeAddsStructuredChangeLogEntry() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);

    WriteResult result =
        writer.appendSpecChange(
            "SPEC-001",
            new ChangeLogEntry(
                "CHG-001",
                "2026-06-25",
                "Superseded old workflow constraint.",
                "Backend contract changed.",
                "ADR-002",
                "architecture-agent"),
            false);

    assertTrue(result.changed());
    ChangeLogEntry change =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.changeLog().getFirst();
    assertEquals("CHG-001", change.id());
    assertEquals("Backend contract changed.", change.reason());
  }

  @Test
  void appendSpecChangeUpdatesExistingEntryById() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.appendSpecChange(
        "SPEC-001",
        new ChangeLogEntry("CHG-001", "2026-06-25", "Old summary.", "Old reason.", null, null),
        false);

    WriteResult result =
        writer.appendSpecChange(
            "SPEC-001",
            new ChangeLogEntry("CHG-001", "2026-06-25", "New summary.", "New reason.", null, null),
            false);

    assertTrue(result.changed());
    List<ChangeLogEntry> changeLog =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.changeLog();
    assertEquals(1, changeLog.size());
    assertEquals("New summary.", changeLog.getFirst().summary());
  }

  @Test
  void supersedeSpecLinksOldAndNewSpecs() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.createSpec(spec("SPEC-002", List.of("booking-api")), false);

    WriteResult result =
        writer.supersedeSpec(
            "SPEC-001", "SPEC-002", "Replacement scope is clearer.", "ADR-002", false);

    assertTrue(result.changed());
    Spec oldSpec = yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec;
    Spec newSpec = yaml.read(root.resolve(".archcontext/specs/spec-002.yaml")).spec;
    assertEquals("superseded", oldSpec.status());
    assertEquals("SPEC-002", oldSpec.supersededBy());
    assertEquals("Replacement scope is clearer.", oldSpec.statusNote());
    assertEquals(List.of("SPEC-001"), newSpec.supersedes());
    assertTrue(
        newSpec.relatedSpecs().stream()
            .anyMatch(r -> r.specId().equals("SPEC-001") && r.type().equals("supersedes")));
  }

  @Test
  void upsertAndDeprecateSpecRelatedAdr() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of("SPEC-001")), false);

    writer.upsertSpecRelatedAdr("SPEC-001", "ADR-001", "decision", "Initial decision.", false);
    WriteResult result =
        writer.deprecateSpecRelatedAdr("SPEC-001", "ADR-001", "No longer applicable.", false);

    assertTrue(result.changed());
    AdrRelation relation =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.relatedAdrLinks().getFirst();
    assertEquals("ADR-001", relation.adrId());
    assertEquals("deprecated", relation.status());
    assertEquals("No longer applicable.", relation.note());
  }

  @Test
  void upsertAndDeprecateSpecRelatedSpec() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.createSpec(spec("SPEC-002", List.of("booking-api")), false);

    writer.upsertSpecRelatedSpec("SPEC-001", "SPEC-002", "informs", "Read before work.", false);
    WriteResult result =
        writer.deprecateSpecRelatedSpec("SPEC-001", "SPEC-002", "No longer related.", false);

    assertTrue(result.changed());
    SpecRelation relation =
        yaml.read(root.resolve(".archcontext/specs/spec-001.yaml")).spec.relatedSpecs().getFirst();
    assertEquals("SPEC-002", relation.specId());
    assertEquals("deprecated", relation.status());
    assertEquals("No longer related.", relation.note());
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
  void validateWorkspaceIgnoresDeprecatedRequirementsForCoverage() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    writer.deprecateSpecRequirement(
        "SPEC-001",
        "nonFunctional",
        "NFR-001",
        "obsolete",
        "Latency requirement moved to platform standard.",
        null,
        null,
        false);
    writer.upsertSpecRepositoryChange(
        "SPEC-001", repositoryChange("booking-api", List.of("FR-001"), List.of("AC-001")), false);

    WriteValidation result = writer.validateWorkspace(true);

    assertFalse(
        result.errors().stream()
            .anyMatch(e -> e.contains("Requirement is not assigned") && e.contains("NFR-001")));
  }

  @Test
  void validateWorkspaceIgnoresDeprecatedAcceptanceCriteriaForCoverage() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    writer.deprecateSpecAcceptanceCriterion(
        "SPEC-001",
        "AC-001",
        "obsolete",
        "Criterion moved to another story.",
        null,
        null,
        false);
    writer.upsertSpecRepositoryChange(
        "SPEC-001", repositoryChange("booking-api", List.of("FR-001", "NFR-001"), List.of()), false);

    WriteValidation result = writer.validateWorkspace(true);

    assertFalse(
        result.errors().stream()
            .anyMatch(
                e -> e.contains("Acceptance criterion is not assigned") && e.contains("AC-001")));
  }

  @Test
  void validateSpecConsistencyWarnsWhenRepositoryChangeUsesDeprecatedItems() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    writer.upsertSpecRepositoryChange(
        "SPEC-001", repositoryChange("booking-api", List.of("FR-001"), List.of("AC-001")), false);
    writer.deprecateSpecRequirement(
        "SPEC-001", "functional", "FR-001", "obsolete", "No longer needed.", null, null, false);

    WriteValidation result = writer.validateSpecConsistency("SPEC-001", false);

    assertTrue(
        result.warnings().stream()
            .anyMatch(w -> w.contains("non-implementable requirement") && w.contains("FR-001")));
  }

  @Test
  void validateSpecConsistencyErrorsInStrictModeForSupersededAdrReference() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(spec("SPEC-001", List.of("booking-api")), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of("SPEC-001")), false);
    writer.upsertSpecRelatedAdr("SPEC-001", "ADR-001", "decision", "Initial decision.", false);
    writer.updateAdrStatus("ADR-001", "superseded", "ADR-002", "Replaced.", false);

    WriteValidation result = writer.validateSpecConsistency("SPEC-001", true);

    assertTrue(result.errors().stream().anyMatch(e -> e.contains("superseded ADR")));
  }

  @Test
  void suggestNextIdsUseExistingNumericSuffixes() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createSpec(specWithImplementationScope("SPEC-001", List.of("booking-api")), false);
    writer.upsertSpecRequirement(
        "SPEC-001", "functional", new Requirement("FR-02", "Second requirement."), false);
    writer.upsertSpecAcceptanceCriterion(
        "SPEC-001", new AcceptanceCriterion("AC-02", "Second criterion."), false);
    writer.upsertSpecConstraint(
        "SPEC-001", new Constraint("CON-02", "Second", "Second constraint."), false);

    assertEquals("FR-03", writer.suggestNextRequirementId("SPEC-001", "functional").get("nextId"));
    assertEquals("AC-03", writer.suggestNextAcceptanceCriterionId("SPEC-001").get("nextId"));
    assertEquals("CON-03", writer.suggestNextConstraintId("SPEC-001").get("nextId"));
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
    writer.awaitPendingSpecIndexing();

    try (Connection c = new Database(root.resolve(".archcontext/archcontext.db")).connect();
        ResultSet rs =
            c.createStatement()
                .executeQuery("SELECT content FROM documents WHERE document_key='SPEC-001'")) {
      assertTrue(rs.next());
      assertTrue(rs.getString(1).contains("Remaining items stay active"));
    }
  }

  @Test
  void importIndexRunsAfterSuccessfulAdrWrite() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);

    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);

    try (Connection c = new Database(root.resolve(".archcontext/archcontext.db")).connect();
        ResultSet rs =
            c.createStatement().executeQuery("SELECT count(*) FROM adrs WHERE id='ADR-001'")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void upsertAdrConsequenceAddsConsequenceToExistingAdr() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);

    WriteResult result =
        writer.upsertAdrConsequence(
            "ADR-001",
            "Canonical binding format is yyyy-MM-dd HH:mm:ss.SSSSSSXXX UTC.",
            false);

    assertTrue(result.changed());
    assertTrue(
        yaml.read(root.resolve(".archcontext/adrs/adr-001.yaml")).adr.consequences().stream()
            .anyMatch(c -> c.contains("yyyy-MM-dd")));
  }

  @Test
  void updateAdrStatusMarksAdrAsSuperseded() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);

    WriteResult result =
        writer.updateAdrStatus(
            "ADR-001",
            "superseded",
            "ADR-002",
            "Replaced by the new contract boundary decision.",
            false);

    assertTrue(result.changed());
    Adr adr = yaml.read(root.resolve(".archcontext/adrs/adr-001.yaml")).adr;
    assertEquals("superseded", adr.status());
    assertEquals("ADR-002", adr.supersededBy());
    assertEquals("Replaced by the new contract boundary decision.", adr.statusNote());
  }

  @Test
  void appendAdrChangeAddsStructuredChangeLogEntry() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);

    WriteResult result =
        writer.appendAdrChange(
            "ADR-001",
            new ChangeLogEntry(
                "CHG-ADR-001",
                "2026-06-25",
                "Marked decision as superseded.",
                "A newer decision replaced this integration boundary.",
                "ADR-002",
                "architecture-agent"),
            false);

    assertTrue(result.changed());
    ChangeLogEntry change =
        yaml.read(root.resolve(".archcontext/adrs/adr-001.yaml")).adr.changeLog().getFirst();
    assertEquals("CHG-ADR-001", change.id());
    assertEquals("ADR-002", change.relatedAdr());
  }

  @Test
  void supersedeAdrLinksOldAndNewAdrs() throws Exception {
    writer.upsertRepository(repository("booking-api", "Booking API"), false);
    writer.createAdr(adr("ADR-001", List.of("booking-api"), List.of()), false);
    writer.createAdr(adr("ADR-002", List.of("booking-api"), List.of()), false);

    WriteResult result =
        writer.supersedeAdr("ADR-001", "ADR-002", "Newer decision replaces old boundary.", false);

    assertTrue(result.changed());
    Adr oldAdr = yaml.read(root.resolve(".archcontext/adrs/adr-001.yaml")).adr;
    Adr newAdr = yaml.read(root.resolve(".archcontext/adrs/adr-002.yaml")).adr;
    assertEquals("superseded", oldAdr.status());
    assertEquals("ADR-002", oldAdr.supersededBy());
    assertEquals(List.of("ADR-001"), newAdr.supersedes());
    assertTrue(oldAdr.changeLog().stream().anyMatch(c -> c.id().equals("superseded-by-ADR-002")));
    assertTrue(newAdr.changeLog().stream().anyMatch(c -> c.id().equals("supersedes-ADR-001")));
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

  private static int count(Connection c, String sql) throws SQLException {
    try (Statement statement = c.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static String text(Connection c, String sql) throws SQLException {
    try (Statement statement = c.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static Guideline guideline(String id) {
    return new Guideline(
        id,
        "Backend testing guideline",
        "testing",
        new AppliesTo(List.of("booking-api"), List.of("java"), List.of("backend")),
        List.of(
            new GuidelineRule(
                "TEST-001",
                null,
                "Write focused JUnit 5 tests for behavior changes.",
                "Agents need executable guardrails.",
                new RuleExamples(List.of("Use AssertJ assertions."), List.of("Only test mocks.")))),
        List.of("https://junit.org"),
        List.of(),
        List.of(),
        null);
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

  private static Adr adr(String id, List<String> repositories, List<String> relatedSpecs) {
    return new Adr(
        id,
        "Use hexagonal architecture",
        "accepted",
        "2026-05-28",
        "Need to isolate business rules from delivery mechanisms.",
        "Use hexagonal architecture.",
        List.of("Domain logic must not depend on controllers."),
        repositories,
        relatedSpecs,
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
