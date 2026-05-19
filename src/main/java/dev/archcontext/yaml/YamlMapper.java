package dev.archcontext.yaml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;

public class YamlMapper {
  private final ObjectMapper mapper =
      new ObjectMapper(new YAMLFactory())
          .findAndRegisterModules()
          .setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  public YamlDocuments read(Path path) throws IOException {
    return mapper.readValue(path.toFile(), YamlDocuments.class);
  }

  public void write(Path path, YamlDocuments doc) throws IOException {
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), doc);
  }

  public ObjectMapper objectMapper() {
    return mapper;
  }
}
