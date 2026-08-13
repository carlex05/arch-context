package dev.archcontext.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.archcontext.domain.Models.*;
import dev.archcontext.yaml.YamlMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ImplementationReviewWorkflowTest {
  private Path root;
  private YamlWorkspaceWriter writer;
  private YamlMapper yaml;

  @BeforeEach
  void setup() throws Exception {
    root = Files.createTempDirectory("archcontext-review-test");
    new WorkspaceService().init(root);
    writer = new YamlWorkspaceWriter(root);
    yaml = new YamlMapper();
    writer.upsertRepository(repository(), false);
    writer.createSpec(spec(), false);
  }

  @Test
  void createImplementationReviewPersistsTraceableReview() throws Exception {
    ImplementationReview review = review(List.of(finding("FIND-001", "open")));

    WriteResult result = writer.createImplementationReview(review, false);

    assertTrue(result.changed());
    assertTrue(result.validation().errors().isEmpty());
    assertEquals(List.of(".archcontext/reviews/rev-spec-001-booking-api.yaml"), result.updatedFiles());
    ImplementationReview stored =
        yaml.read(root.resolve(".archcontext/reviews/rev-spec-001-booking-api.yaml"))
            .implementationReview;
    assertEquals("a1b2c3d", stored.commit());
    assertEquals("create-adr", stored.findings().getFirst().proposedActions().getFirst().type());
  }

  @Test
  void createImplementationReviewRejectsUnknownReferencesAndDuplicateFindingIds() {
    ReviewFinding duplicate = finding("FIND-001", "open");
    ImplementationReview invalid =
        new ImplementationReview(
            "REV-INVALID",
            "SPEC-404",
            "unknown-repo",
            "feature/review",
            "a1b2c3d",
            "tech-lead",
            "2026-08-13",
            "changes-requested",
            "Invalid review.",
            null,
            List.of(duplicate, duplicate),
            null);

    WriteResult result = writer.createImplementationReview(invalid, false);

    assertFalse(result.changed());
    assertTrue(result.validation().errors().stream().anyMatch(e -> e.contains("Unknown specId")));
    assertTrue(
        result.validation().errors().stream().anyMatch(e -> e.contains("Unknown repositoryId")));
    assertTrue(
        result.validation().errors().stream().anyMatch(e -> e.contains("Duplicate review finding id")));
  }

  @Test
  void upsertFindingAndResolveItWithCreatedArchitectureContext() throws Exception {
    writer.createImplementationReview(review(List.of()), false);

    WriteResult upsert =
        writer.upsertReviewFinding(
            "REV-SPEC-001-BOOKING-API", finding("FIND-001", "open"), false);
    writer.upsertSpecConstraint(
        "SPEC-001", new Constraint("CON-002", "Transaction", "Define transaction ownership."), false);
    writer.createAdr(
        new Adr(
            "ADR-002",
            "Transaction ownership",
            "accepted",
            "2026-08-14",
            "Transaction ownership was implicit.",
            "The application service owns the transaction.",
            List.of(),
            List.of("booking-api"),
            List.of("SPEC-001"),
            null),
        false);
    FindingResolution resolution =
        new FindingResolution(
            "Use ADR-002 and CON-002.",
            "2026-08-14",
            "architecture-team",
            "ADR-002",
            "CON-002");
    WriteResult resolved =
        writer.updateReviewFindingStatus(
            "REV-SPEC-001-BOOKING-API", "FIND-001", "resolved", resolution, false);

    assertTrue(upsert.changed());
    assertTrue(resolved.changed());
    ReviewFinding stored =
        yaml.read(root.resolve(".archcontext/reviews/rev-spec-001-booking-api.yaml"))
            .implementationReview
            .findings()
            .getFirst();
    assertEquals("resolved", stored.status());
    assertEquals("ADR-002", stored.resolution().relatedAdr());
    assertEquals("CON-002", stored.resolution().relatedConstraint());
    DeveloperReviewBriefing briefing =
        new McpContextService(root)
            .getReviewBriefingForDeveloper("REV-SPEC-001-BOOKING-API", false);
    assertTrue(briefing.review().findings().isEmpty());
    assertEquals(List.of("ADR-002"), briefing.relatedAdrs().stream().map(Adr::id).toList());
  }

  @Test
  void developerBriefingReturnsOnlyActionableFindingsByDefault() {
    writer.createImplementationReview(
        review(List.of(finding("FIND-001", "open"), finding("FIND-002", "resolved"))), false);
    McpContextService service = new McpContextService(root);

    DeveloperReviewBriefing defaultBriefing =
        service.getReviewBriefingForDeveloper("REV-SPEC-001-BOOKING-API", false);
    DeveloperReviewBriefing historicalBriefing =
        service.getReviewBriefingForDeveloper("REV-SPEC-001-BOOKING-API", true);

    assertEquals("SPEC-001", defaultBriefing.spec().id());
    assertEquals("booking-api", defaultBriefing.repository().id());
    assertEquals(
        List.of("FIND-001"),
        defaultBriefing.review().findings().stream().map(ReviewFinding::id).toList());
    assertEquals(2, historicalBriefing.review().findings().size());
    assertEquals("CON-001", defaultBriefing.activeConstraints().getFirst().id());
  }

  @Test
  void reviewCannotBeApprovedWhileItHasActionableFindings() {
    writer.createImplementationReview(review(List.of(finding("FIND-001", "open"))), false);

    WriteResult prematureApproval =
        writer.updateImplementationReviewStatus(
            "REV-SPEC-001-BOOKING-API", "approved", "Ready to merge.", false);
    writer.updateReviewFindingStatus(
        "REV-SPEC-001-BOOKING-API",
        "FIND-001",
        "resolved",
        new FindingResolution("Implemented the requested correction.", "2026-08-14", "developer", null, null),
        false);
    WriteResult approval =
        writer.updateImplementationReviewStatus(
            "REV-SPEC-001-BOOKING-API", "approved", "Ready to merge.", false);

    assertFalse(prematureApproval.changed());
    assertTrue(
        prematureApproval.validation().errors().stream()
            .anyMatch(error -> error.contains("actionable findings")));
    assertTrue(approval.changed());
  }

  @Test
  void listImplementationReviewsSupportsSpecRepositoryAndStatusFilters() {
    writer.createImplementationReview(review(List.of()), false);
    McpContextService service = new McpContextService(root);

    List<ImplementationReviewSummary> reviews =
        service.listImplementationReviews("SPEC-001", "booking-api", "changes-requested");
    assertEquals(1, reviews.size());
    assertEquals(0, reviews.getFirst().actionableFindings());
    assertTrue(service.listImplementationReviews("SPEC-002", null, null).isEmpty());
  }

  @Test
  void implementationReviewsAreAddedToTheSearchIndex() {
    writer.createImplementationReview(review(List.of(finding("FIND-001", "open"))), false);
    writer.awaitPendingSpecIndexing();

    List<DocumentChunk> matches =
        new McpContextService(root).searchContext("transaction ownership", List.of("review"));

    assertFalse(matches.isEmpty());
    assertEquals("review", matches.getFirst().documentType());
  }

  private ImplementationReview review(List<ReviewFinding> findings) {
    return new ImplementationReview(
        "REV-SPEC-001-BOOKING-API",
        "SPEC-001",
        "booking-api",
        "feature/spec-001",
        "a1b2c3d",
        "tech-lead",
        "2026-08-13",
        "changes-requested",
        "One architecture issue must be addressed.",
        null,
        findings,
        null);
  }

  private ReviewFinding finding(String id, String status) {
    FindingResolution resolution =
        "resolved".equals(status)
            ? new FindingResolution("Fixed.", "2026-08-14", "developer", null, null)
            : null;
    return new ReviewFinding(
        id,
        "architecture-decision-required",
        "major",
        "architecture",
        status,
        "Transaction ownership is implicit",
        "The implementation introduces transaction ownership without a recorded decision.",
        List.of(new ReviewEvidence("src/main/java/BookingService.java", 42, 51, "New boundary.")),
        List.of("FR-001"),
        List.of("AC-001"),
        List.of("CON-001"),
        List.of(),
        "Record the ownership decision before approval.",
        List.of(
            new ProposedReviewAction(
                "ACTION-001",
                "create-adr",
                "proposed",
                "ADR-002",
                "Record transaction ownership",
                "Create an ADR defining transaction ownership.")),
        resolution);
  }

  private RepositoryDefinition repository() {
    return new RepositoryDefinition(
        "booking-api",
        "Booking API",
        "../booking-api",
        "backend",
        "java",
        "booking",
        "Handles booking.",
        List.of(),
        List.of());
  }

  private Spec spec() {
    return new Spec(
        "SPEC-001",
        "Booking review",
        "review",
        "booking-team",
        "Review the booking implementation.",
        "Ship a reliable implementation.",
        List.of("booking-api"),
        List.of("booking"),
        List.of(new Requirement("FR-001", "Implement booking.")),
        List.of(),
        List.of(new AcceptanceCriterion("AC-001", "Booking is persisted.")),
        List.of(),
        List.of(new Constraint("CON-001", "Boundary", "Keep domain independent.")),
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new RepositoryChange(
                "booking-api",
                "backend",
                "Implement booking.",
                List.of("FR-001"),
                List.of("AC-001"),
                List.of(),
                List.of(),
                List.of())),
        null,
        List.of(),
        null,
        List.of(),
        null,
        List.of(),
        List.of(),
        List.of(),
        null);
  }
}
