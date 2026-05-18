package dev.archcontext.storage;

import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.sql.*;import java.util.*;

public class Database {
  private final Path dbPath;
  public Database(Path dbPath){this.dbPath=dbPath;}
  public Connection connect() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath()); }
  public void migrate(){
    try { Files.createDirectories(dbPath.getParent()); } catch(IOException e){ throw new IllegalStateException(e); }
    try(Connection c=connect(); Statement s=c.createStatement()){
      s.execute("PRAGMA foreign_keys=ON");
      run(c, "V001__initial_schema.sql");
    } catch(Exception e){ throw new IllegalStateException("Cannot migrate SQLite database at "+dbPath+": "+e.getMessage(), e); }
  }
  private void run(Connection c, String resource) throws Exception{
    try (InputStream in=getClass().getClassLoader().getResourceAsStream("db/migration/"+resource)){
      if(in==null) throw new FileNotFoundException(resource);
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      try(Statement st=c.createStatement()) { for(String part: sql.split(";\\s*(?=\\n|$)")){ if(!part.isBlank()) st.execute(part); } }
      try(PreparedStatement ps=c.prepareStatement("INSERT OR IGNORE INTO schema_migrations(version) VALUES (?)")){ps.setString(1, resource); ps.executeUpdate();}
    }
  }
  public Path path(){return dbPath;}
}
