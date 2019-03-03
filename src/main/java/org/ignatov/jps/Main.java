package org.ignatov.jps;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;

public class Main {
  private static boolean PRINT_HEX = Boolean.parseBoolean(System.getProperty("print.hex"));
  private static boolean PRINT_DIR = Boolean.parseBoolean(System.getProperty("print.dir"));

  private static final int RATHER_SMALL_FILE_SIZE = 1_000_000;

  private static byte[] checksum(Path path, MessageDigest md, boolean readAllBytes) throws IOException {
    return readAllBytes ? md.digest(Files.readAllBytes(path)) : readChecksumWithBuffer(path, md);
  }

  private static byte[] readChecksumWithBuffer(Path path, MessageDigest md) throws IOException {
    try (InputStream is = Files.newInputStream(path); BufferedInputStream bis = new BufferedInputStream(is)) {
      byte[] buffer = new byte[RATHER_SMALL_FILE_SIZE];
      int bytesRead;
      while ((bytesRead = bis.read(buffer, 0, buffer.length)) != -1) {
        md.update(buffer, 0, bytesRead);
      }
      return md.digest();
    }
  }

  private final static char[] hexArray = "0123456789abcdef".toCharArray();

  private static String toHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = hexArray[v >>> 4];
      hexChars[j * 2 + 1] = hexArray[v & 0x0F];
    }
    return new String(hexChars);
  }

  private static boolean skip(Path dir) {
    return dir.endsWith(".git") ||
        dir.endsWith("community/build") ||
        dir.endsWith("build/jdk") ||
        dir.endsWith("out/classes") ||
        dir.endsWith("out/tests")
        ;
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 1) {
      System.out.println("Choose a directory where to go");
      return;
    }

    String path = args[0];
    Path realPath = Paths.get(path).toRealPath();
    System.out.println("Walking on " + realPath);
    RecursiveWalk w = new RecursiveWalk(realPath);
    ForkJoinPool p = new ForkJoinPool();
    long start = System.currentTimeMillis();
    p.invoke(w);
    long end = System.currentTimeMillis();
    System.out.println("Traversed " + w.counter.longValue() + " files in " + (end - start) + " ms");
  }

  private static class RecursiveWalk extends RecursiveAction {
    private final Path dir;
    private final MessageDigest md = MessageDigest.getInstance("MD5");
    private final LongAdder counter = new LongAdder();

    RecursiveWalk(Path dir) throws NoSuchAlgorithmException {
      this.dir = dir;
      if (PRINT_DIR) System.out.println(dir);
    }

    @Override
    protected void compute() {
      List<RecursiveWalk> walks = new ArrayList<>();
      try {
        Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, createVisitor(walks));
      } catch (IOException e) {
        e.printStackTrace();
      }
      for (RecursiveWalk w : walks) {
        w.join();
        counter.add(w.counter.longValue());
      }
    }

    private SimpleFileVisitor<Path> createVisitor(List<RecursiveWalk> walks) {
      return new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          if (skip(dir)) return FileVisitResult.SKIP_SUBTREE;

          if (dir.equals(RecursiveWalk.this.dir)) return FileVisitResult.CONTINUE;

          try {
            RecursiveWalk w = new RecursiveWalk(dir);
            w.fork();
            walks.add(w);
          } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
          }
          return FileVisitResult.SKIP_SUBTREE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;

          counter.add(1);
          boolean small = attrs.size() < RATHER_SMALL_FILE_SIZE;
          String hex = toHex(checksum(file, md, small));

          if (PRINT_HEX) {
            System.out.println(hex + " " + file);
          }

          return FileVisitResult.CONTINUE;
        }
      };
    }
  }
}
