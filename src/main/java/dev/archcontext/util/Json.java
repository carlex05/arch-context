package dev.archcontext.util;
import com.fasterxml.jackson.databind.*;import java.util.*;
public final class Json { private Json(){} public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
 public static String write(Object o){ try{return MAPPER.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);} }
 public static <T> T read(String s, Class<T> type){ try{return MAPPER.readValue(s,type);}catch(Exception e){throw new IllegalStateException(e);} }
 public static List<String> stringList(String s){ if(s==null||s.isBlank()) return List.of(); try{return MAPPER.readValue(s, MAPPER.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){return List.of();} }
}
