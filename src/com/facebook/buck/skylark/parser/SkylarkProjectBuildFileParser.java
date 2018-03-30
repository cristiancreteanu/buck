/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.skylark.parser;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.log.Logger;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.events.ParseBuckFileEvent;
import com.facebook.buck.parser.exceptions.BuildFileParseException;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.skylark.function.Glob;
import com.facebook.buck.skylark.function.SkylarkNativeModule;
import com.facebook.buck.skylark.io.impl.SimpleGlobber;
import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.packages.PackageFactory;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.Environment.Extension;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParserInputSource;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkImport;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Parser for build files written using Skylark syntax.
 *
 * <p>NOTE: This parser is still a work in progress and does not support some functions provided by
 * Python DSL parser like {@code include_defs}, so use in production at your own risk.
 */
public class SkylarkProjectBuildFileParser implements ProjectBuildFileParser {

  private static final Logger LOG = Logger.get(SkylarkProjectBuildFileParser.class);

  // Dummy label used for resolving paths for other labels.
  private static final Label EMPTY_LABEL =
      Label.createUnvalidated(PackageIdentifier.EMPTY_PACKAGE_ID, "");

  private final FileSystem fileSystem;

  private final ProjectBuildFileParserOptions options;
  private final BuckEventBus buckEventBus;
  private final EventHandler eventHandler;
  private final LoadingCache<LoadImport, ExtensionData> extensionDataCache;
  private final BuckGlobals buckGlobals;

  private SkylarkProjectBuildFileParser(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler) {
    this.options = options;
    this.buckEventBus = buckEventBus;
    this.fileSystem = fileSystem;
    this.eventHandler = eventHandler;
    this.buckGlobals = buckGlobals;

    this.extensionDataCache =
        CacheBuilder.newBuilder()
            .build(
                new CacheLoader<LoadImport, ExtensionData>() {
                  @Override
                  public ExtensionData load(@Nonnull LoadImport loadImport) throws Exception {
                    return loadExtension(loadImport);
                  }
                });
  }

  /** Create an instance of Skylark project build file parser using provided options. */
  public static SkylarkProjectBuildFileParser using(
      ProjectBuildFileParserOptions options,
      BuckEventBus buckEventBus,
      FileSystem fileSystem,
      BuckGlobals buckGlobals,
      EventHandler eventHandler) {
    return new SkylarkProjectBuildFileParser(
        options, buckEventBus, fileSystem, buckGlobals, eventHandler);
  }

  @Override
  public ImmutableList<Map<String, Object>> getAll(Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    return parseBuildFile(buildFile).getRawRules();
  }

  @Override
  public ImmutableList<Map<String, Object>> getAllRulesAndMetaRules(
      Path buildFile, AtomicLong processedBytes)
      throws BuildFileParseException, InterruptedException, IOException {
    // TODO(ttsugrii): add metadata rules
    ParseResult parseResult = parseBuildFile(buildFile);
    // TODO(ttsugrii): find a way to reuse the same constants across Python DSL and Skylark parsers
    return ImmutableList.<Map<String, Object>>builder()
        .addAll(parseResult.getRawRules())
        .add(
            ImmutableMap.of(
                "__includes",
                parseResult
                    .getLoadedPaths()
                    .stream()
                    .map(Object::toString)
                    .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()))))
        // TODO(ttsugrii): implement once configuration options are exposed via Skylark API
        .add(ImmutableMap.of("__configs", ImmutableMap.of()))
        // TODO(ttsugrii): implement once environment variables are exposed via Skylark API
        .add(ImmutableMap.of("__env", ImmutableMap.of()))
        .build();
  }

  /**
   * Retrieves build files requested in {@code buildFile}.
   *
   * @param buildFile The build file to parse.
   * @return The {@link ParseResult} with build rules defined in {@code buildFile}.
   */
  private ParseResult parseBuildFile(Path buildFile)
      throws BuildFileParseException, InterruptedException, IOException {
    ImmutableList<Map<String, Object>> rules = ImmutableList.of();
    ParseBuckFileEvent.Started startEvent = ParseBuckFileEvent.started(buildFile);
    buckEventBus.post(startEvent);
    ParseResult parseResult;
    try {
      parseResult = parseBuildRules(buildFile);
      rules = parseResult.getRawRules();
    } finally {
      // TODO(ttsugrii): think about reporting processed bytes and profiling support
      buckEventBus.post(ParseBuckFileEvent.finished(startEvent, rules, 0L, Optional.empty()));
    }
    return parseResult;
  }

  /** @return The parsed build rules defined in {@code buildFile}. */
  private ParseResult parseBuildRules(Path buildFile)
      throws IOException, BuildFileParseException, InterruptedException {
    // TODO(ttsugrii): consider using a less verbose event handler. Also fancy handler can be
    // configured for terminals that support it.
    com.google.devtools.build.lib.vfs.Path buildFilePath = fileSystem.getPath(buildFile.toString());

    BuildFileAST buildFileAst =
        BuildFileAST.parseBuildFile(createInputSource(buildFilePath), eventHandler);
    if (buildFileAst.containsErrors()) {
      throw BuildFileParseException.createForUnknownParseError(
          "Cannot parse build file " + buildFile);
    }
    ParseContext parseContext = new ParseContext();
    try (Mutability mutability = Mutability.create("parsing " + buildFile)) {
      EnvironmentData envData =
          createBuildFileEvaluationEnvironment(buildFile, buildFileAst, mutability, parseContext);
      boolean exec = buildFileAst.exec(envData.getEnvironment(), eventHandler);
      if (!exec) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate build file " + buildFile);
      }
      ImmutableList<Map<String, Object>> rules = parseContext.getRecordedRules();
      LOG.verbose("Got rules: %s", rules);
      LOG.verbose("Parsed %d rules from %s", rules.size(), buildFile);
      return ParseResult.builder()
          .setRawRules(rules)
          .setLoadedPaths(
              ImmutableSortedSet.<com.google.devtools.build.lib.vfs.Path>naturalOrder()
                  .addAll(envData.getLoadedPaths())
                  .add(buildFilePath)
                  .build())
          .build();
    }
  }

  /** Creates an instance of {@link ParserInputSource} for a file at {@code buildFilePath}. */
  private ParserInputSource createInputSource(com.google.devtools.build.lib.vfs.Path buildFilePath)
      throws IOException {
    return ParserInputSource.create(
        FileSystemUtils.readWithKnownFileSize(buildFilePath, buildFilePath.getFileSize()),
        buildFilePath.asFragment());
  }

  /**
   * @return The environment that can be used for evaluating build files. It includes built-in
   *     functions like {@code glob} and native rules like {@code java_library}.
   */
  private EnvironmentData createBuildFileEvaluationEnvironment(
      Path buildFile, BuildFileAST buildFileAst, Mutability mutability, ParseContext parseContext)
      throws IOException, InterruptedException, BuildFileParseException {
    ImmutableList<ExtensionData> dependencies =
        loadExtensions(EMPTY_LABEL, buildFileAst.getImports());
    ImmutableMap<String, Environment.Extension> importMap = toImportMap(dependencies);
    Environment env =
        Environment.builder(mutability)
            .setImportedExtensions(importMap)
            .setGlobals(buckGlobals.getBuckBuildFileContextGlobals())
            .setPhase(Environment.Phase.LOADING)
            .useDefaultSemantics()
            .setEventHandler(eventHandler)
            .build();
    String basePath = getBasePath(buildFile);
    env.setupDynamic(Runtime.PKG_NAME, basePath);
    parseContext.setup(env);
    env.setup("glob", Glob.create());
    env.setup("package_name", SkylarkNativeModule.packageName);
    PackageContext packageContext =
        PackageContext.builder()
            .setGlobber(SimpleGlobber.create(fileSystem.getPath(buildFile.getParent().toString())))
            .setRawConfig(options.getRawConfig())
            .build();
    env.setupDynamic(PackageFactory.PACKAGE_CONTEXT, packageContext);
    return EnvironmentData.builder()
        .setEnvironment(env)
        .setLoadedPaths(toLoadedPaths(dependencies))
        .build();
  }

  private ImmutableList<com.google.devtools.build.lib.vfs.Path> toLoadedPaths(
      ImmutableList<ExtensionData> dependencies) {
    ImmutableList.Builder<com.google.devtools.build.lib.vfs.Path> loadedPathsBuilder =
        ImmutableList.builder();
    for (ExtensionData extensionData : dependencies) {
      loadedPathsBuilder.add(extensionData.getPath());
      loadedPathsBuilder.addAll(toLoadedPaths(extensionData.getDependencies()));
    }
    return loadedPathsBuilder.build();
  }

  /** Loads all extensions identified by corresponding {@link SkylarkImport}s. */
  private ImmutableList<ExtensionData> loadExtensions(
      Label containingLabel, ImmutableList<SkylarkImport> skylarkImports)
      throws BuildFileParseException, IOException, InterruptedException {
    try {
      return skylarkImports
          .stream()
          .map(
              skylarkImport ->
                  LoadImport.builder()
                      .setContainingLabel(containingLabel)
                      .setImport(skylarkImport)
                      .build())
          .map(extensionDataCache::getUnchecked)
          .collect(ImmutableList.toImmutableList());
    } catch (UncheckedExecutionException e) {
      return propagateRootCause(e);
    }
  }

  /**
   * Propagates underlying parse exception from {@link UncheckedExecutionException}.
   *
   * <p>This is an unfortunate consequence of having to use {@link
   * LoadingCache#getUnchecked(Object)} in when using stream transformations :(
   *
   * <p>TODO(ttsugrii): the logic of extracting root causes to make them user-friendly should be
   * happening somewhere in {@link com.facebook.buck.cli.Main#main(String[])}, since this behavior
   * is not unique to parsing.
   */
  private ImmutableList<ExtensionData> propagateRootCause(UncheckedExecutionException e)
      throws IOException, InterruptedException {
    Throwable rootCause = Throwables.getRootCause(e);
    if (rootCause instanceof BuildFileParseException) {
      throw (BuildFileParseException) rootCause;
    }
    if (rootCause instanceof IOException) {
      throw (IOException) rootCause;
    }
    if (rootCause instanceof InterruptedException) {
      throw (InterruptedException) rootCause;
    }
    throw e;
  }

  /**
   * @return The map from skylark import string like {@code //pkg:build_rules.bzl} to an {@link
   *     Environment.Extension} for provided {@code dependencies}.
   */
  private ImmutableMap<String, Environment.Extension> toImportMap(
      ImmutableList<ExtensionData> dependencies) {
    return dependencies
        .stream()
        .distinct() // it's possible to have multiple loads from the same extension file
        .collect(
            ImmutableMap.toImmutableMap(
                ExtensionData::getImportString, ExtensionData::getExtension));
  }

  /**
   * Creates an extension from a {@code path}.
   *
   * @param loadImport an import label representing an extension to load.
   */
  private ExtensionData loadExtension(LoadImport loadImport)
      throws IOException, BuildFileParseException, InterruptedException {
    Label label = loadImport.getLabel();
    com.google.devtools.build.lib.vfs.Path extensionPath =
        getImportPath(label, loadImport.getImport());
    ImmutableList<ExtensionData> dependencies = ImmutableList.of();
    Extension extension;
    try (Mutability mutability = Mutability.create("importing extension")) {
      BuildFileAST extensionAst =
          BuildFileAST.parseSkylarkFile(createInputSource(extensionPath), eventHandler);
      if (extensionAst.containsErrors()) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot parse extension file " + loadImport.getImport().getImportString());
      }
      Environment.Builder envBuilder =
          Environment.builder(mutability)
              .setEventHandler(eventHandler)
              .setGlobals(buckGlobals.getBuckLoadContextGlobals());
      if (!extensionAst.getImports().isEmpty()) {
        dependencies = loadExtensions(label, extensionAst.getImports());
        envBuilder.setImportedExtensions(toImportMap(dependencies));
      }
      Environment extensionEnv = envBuilder.useDefaultSemantics().build();
      boolean success = extensionAst.exec(extensionEnv, eventHandler);
      if (!success) {
        throw BuildFileParseException.createForUnknownParseError(
            "Cannot evaluate extension file " + loadImport.getImport().getImportString());
      }
      extension = new Extension(extensionEnv);
    }
    return ExtensionData.builder()
        .setExtension(extension)
        .setPath(extensionPath)
        .setDependencies(dependencies)
        .setImportString(loadImport.getImport().getImportString())
        .build();
  }

  /**
   * @return The path to a Skylark extension. For example, for {@code load("//pkg:foo.bzl", "foo")}
   *     import it would return {@code /path/to/repo/pkg/foo.bzl} and for {@code
   *     load("@repo//pkg:foo.bzl", "foo")} it would return {@code /repo/pkg/foo.bzl} assuming that
   *     {@code repo} is located at {@code /repo}.
   */
  private com.google.devtools.build.lib.vfs.Path getImportPath(
      Label containingLabel, SkylarkImport skylarkImport) throws BuildFileParseException {
    PathFragment relativeExtensionPath = containingLabel.toPathFragment();
    RepositoryName repository = containingLabel.getPackageIdentifier().getRepository();
    if (repository.isMain()) {
      return fileSystem.getPath(
          options.getProjectRoot().resolve(relativeExtensionPath.toString()).toString());
    }
    // Skylark repositories have an "@" prefix, but Buck roots do not, so ignore it
    String repositoryName = repository.getName().substring(1);
    @Nullable Path repositoryPath = options.getCellRoots().get(repositoryName);
    if (repositoryPath == null) {
      throw BuildFileParseException.createForUnknownParseError(
          skylarkImport.getImportString() + " references an unknown repository " + repositoryName);
    }
    return fileSystem.getPath(repositoryPath.resolve(relativeExtensionPath.toString()).toString());
  }

  /**
   * @return The path path of the provided {@code buildFile}. For example, for {@code
   *     /Users/foo/repo/src/bar/BUCK}, where {@code /Users/foo/repo} is the path to the repo, it
   *     would return {@code src/bar}.
   */
  private String getBasePath(Path buildFile) {
    return Optional.ofNullable(options.getProjectRoot().relativize(buildFile).getParent())
        .map(MorePaths::pathWithUnixSeparators)
        .orElse("");
  }

  @Override
  public void reportProfile() {
    // TODO(ttsugrii): implement
  }

  @Override
  public void close() throws BuildFileParseException, InterruptedException, IOException {
    // nothing to do
  }

  /**
   * A value object for information about load function import, since {@link SkylarkImport} does not
   * provide enough context. For instance, the same {@link SkylarkImport} can represent different
   * logical imports depending on which repository it is resolved in.
   */
  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractLoadImport {
    /** Returns a label of the file containing this import. */
    abstract Label getContainingLabel();

    /** Returns a Skylark import. */
    abstract SkylarkImport getImport();

    /** Returns a label of current import file. */
    Label getLabel() {
      return getImport().getLabel(getContainingLabel());
    }
  }
}
