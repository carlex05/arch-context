package dev.archcontext;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(
    String groupId,
    String artifactId,
    String version,
    String gitCommit,
    String buildTimestamp) {
  private static final String RESOURCE = "/archcontext-build.properties";
  private static final BuildInfo CURRENT = load();

  public static BuildInfo current() {
    return CURRENT;
  }

  public String displayVersion() {
    if (gitCommit == null || gitCommit.isBlank() || "unknown".equals(gitCommit)) {
      return version;
    }
    return version + "+" + gitCommit;
  }

  private static BuildInfo load() {
    Properties properties = new Properties();
    try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
      if (in != null) {
        properties.load(in);
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot load ArchContext build metadata.", e);
    }
    return new BuildInfo(
        value(properties, "groupId", "dev.archcontext"),
        value(properties, "artifactId", "archcontext"),
        value(properties, "version", "0.2.0"),
        value(properties, "gitCommit", "unknown"),
        value(properties, "buildTimestamp", "unknown"));
  }

  private static String value(Properties properties, String key, String fallback) {
    String value = properties.getProperty(key);
    return value == null || value.isBlank() ? fallback : value;
  }
}
