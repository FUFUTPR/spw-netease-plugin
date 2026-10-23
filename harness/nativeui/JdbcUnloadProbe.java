import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Windows archive-handle reproduction using only an isolated JDBC copy. */
public class JdbcUnloadProbe {
  public static void main(String[] args)throws Exception {
    Path base=Path.of(args[1]).toAbsolutePath().normalize();
    Path root=Files.createTempDirectory(base,"jdbc-unload-");
    Path jar=Files.copy(Path.of(args[0]),root.resolve("sqlite.jar"));
    try(var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
      Driver driver=(Driver)Class.forName("org.sqlite.JDBC",true,loader).getConstructor().newInstance();
      try(var connection=driver.connect("jdbc:sqlite::memory:",new Properties())) {
        try(var statement=connection.createStatement()){statement.execute("select 1");}
      }
      if(args.length>2 && args[2].equals("fixed")) {
        try(var foreign=new URLClassLoader(new URL[0],loader)) {
          if(com.example.netease.core.JdbcResources.releaseCachedArchive(foreign)!=0)
            throw new AssertionError("Must not close another loader's archive");
        }
        int closed=com.example.netease.core.JdbcResources.releaseCachedArchive(loader);
        if(closed!=1)throw new AssertionError("Expected exactly one plugin-owned cached archive");
        try(var connection=driver.connect("jdbc:sqlite::memory:",new Properties());var statement=connection.createStatement()) {
          statement.execute("select 2");
        }
      }
    }
    try {
      Path dest=root.resolveSibling(root.getFileName()+"-previous").normalize();
      if(!root.startsWith(base)||!dest.startsWith(base))throw new AssertionError("Move escaped sandbox");
      Files.move(root,dest);
      System.out.println("JDBC_UNLOAD_RESULT=PASS");
    } catch(Exception e) {System.out.println("JDBC_UNLOAD_RESULT=FAIL "+e);throw e;}
  }
}
