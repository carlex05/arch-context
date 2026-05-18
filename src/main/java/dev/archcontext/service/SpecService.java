package dev.archcontext.service;

import dev.archcontext.domain.Models.*;
import java.nio.file.*;
import java.util.*;

public class SpecService {
  private final McpContextService c;

  public SpecService(Path r) {
    c = new McpContextService(r);
  }

  public Optional<Spec> find(String id) {
    try {
      return Optional.of(c.getSpecContext(id));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
