package dev.archcontext;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BuildInfoTest {
  @Test
  void buildInfoIsLoadedFromJarResources() {
    BuildInfo info = BuildInfo.current();

    assertEquals("dev.archcontext", info.groupId());
    assertEquals("archcontext", info.artifactId());
    assertFalse(info.version().isBlank());
    assertFalse(info.displayVersion().isBlank());
  }
}
