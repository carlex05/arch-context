package dev.archcontext.service;
import java.nio.file.*;
public class ExportService { public void exportWorkspace(Path root){ if(!Files.exists(root.resolve(".archcontext/archcontext.db"))) throw new IllegalStateException("No SQLite index found. Run archcontext import first."); System.err.println("Export MVP: YAML source files are already the source of truth; no changes written."); }}
