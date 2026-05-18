package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import java.nio.file.*;
import java.util.*;

public class AdrService {
  private final McpContextService c;

  public AdrService(Path r) {
    c = new McpContextService(r);
  }

  public Optional<Adr> find(String id) {
    try {
      return Optional.of(c.getAdrContext(id));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
