/*
 * Git Files Blame
 * Copyright (C) 2009-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scm.git.blame;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Ignore;
import org.junit.Test;

public class BlameGeneratorTest {
  private final Path projectDir = Paths.get("/home/meneses/git/sonar-enterprise").toAbsolutePath();

  private final Path resultDir = Paths.get("/tmp/results").toAbsolutePath();

  @Test
  public void testNewBlameGenerator() throws IOException, GitAPIException {
    try (Repository repo = loadRepository(projectDir)) {
      RepositoryBlameCommand repoBlameCmd = new RepositoryBlameCommand(repo)
        .setMultithreading(true)
       .setFilePaths(Set.of("server/sonar-ce-task/src/main/java/org/sonar/ce/task/setting/package-info.java"))
        .setTextComparator(RawTextComparator.WS_IGNORE_ALL);
      BlameResult result = repoBlameCmd.call();
      writeResults(resultDir.resolve("new.txt").toString(), result);
    }
  }

  @Test
  public void testOldImplementation() throws IOException, InterruptedException {
    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    ConcurrentLinkedQueue<BlameR> results = new ConcurrentLinkedQueue<>();
    try (Repository repo = loadRepository(projectDir)) {
      //Collection<String> paths = readFiles(repo);
      Collection<String> paths = List.of("server/sonar-ce-task/src/main/java/org/sonar/ce/task/setting/package-info.java");
      AtomicInteger i = new AtomicInteger(0);
      for (String p : paths) {
        executorService.submit(() -> {
          try {
            System.out.println(i.incrementAndGet() + "/" + paths.size()+ " " + p);
            org.eclipse.jgit.blame.BlameResult blame = Git.wrap(repo).blame()
              // Equivalent to -w command line option
              .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
              .setFilePath(p).call();
            if (blame != null) {
              BlameR b = new BlameR();
              b.hash = IntStream.range(0, blame.getResultContents().size()).mapToObj(j -> blame.getSourceCommit(j).getName()).collect(Collectors.toList());
              b.path = blame.getResultPath();
              results.add(b);
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        });
      }

      executorService.shutdown();
      executorService.awaitTermination(1, TimeUnit.HOURS);
      writeResultsOldImplementation(resultDir.resolve("old.txt").toString(), results);
    }
  }

  private static void writeResults(String filename, BlameResult result) throws IOException {
    Map<String, BlameResult.FileBlame> ordered = new TreeMap<>();
    result.getFileBlames().forEach(f -> ordered.put(f.getPath(), f));
    Path resultFile = Paths.get(filename);

    try (Writer w = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, BlameResult.FileBlame> e : ordered.entrySet()) {
        w.write(e.getKey() + "\n");
        for (int i = 0; i < e.getValue().lines(); i++) {
          String name = e.getValue().getCommitHashes()[i] != null ? e.getValue().getCommitHashes()[i] : "null";
          w.write(name + "\n");
        }
      }
    }
  }

  private static void writeResultsOldImplementation(String filename, Collection<BlameR> results) throws IOException {
    Map<String, BlameR> ordered = new TreeMap<>();
    results.forEach(b -> ordered.put(b.path, b));
    Path resultFile = Paths.get(filename);

    try (Writer w = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, BlameR> e : ordered.entrySet()) {
        w.write(e.getKey() + "\n");
        for (int i = 0; i < e.getValue().hash.size(); i++) {
          String name = e.getValue().hash.get(i);
          w.write(name + "\n");
        }
      }
    }
  }

  private static class BlameR {
    List<String> hash;
    String path;
  }

  private Collection<String> readFiles(Repository repository) throws IOException {
    RevCommit head = repository.parseCommit(repository.resolve(Constants.HEAD));
    return findFiles(repository.newObjectReader(), head);
  }

  private Repository loadRepository(Path dir) throws IOException {
    return new RepositoryBuilder()
      .findGitDir(dir.toFile())
      .setMustExist(true)
      .build();
  }

  private List<String> findFiles(ObjectReader objectReader, RevCommit commit) throws IOException {
    List<String> files = new LinkedList<>();

    TreeWalk treeWalk = new TreeWalk(objectReader);
    treeWalk.setRecursive(true);
    treeWalk.reset(commit.getTree());

    while (treeWalk.next()) {
      files.add(treeWalk.getPathString());
    }
    return files;
  }
}
