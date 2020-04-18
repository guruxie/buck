/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.js;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDepsQuery;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.query.Query;
import com.facebook.buck.rules.query.QueryUtils;
import com.facebook.buck.shell.ProvidesWorkerTool;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class JsLibraryDescription
    implements DescriptionWithTargetGraph<JsLibraryDescriptionArg>,
        Flavored,
        ImplicitDepsInferringDescription<JsLibraryDescription.AbstractJsLibraryDescriptionArg> {

  static final ImmutableSet<FlavorDomain<?>> FLAVOR_DOMAINS =
      ImmutableSet.of(
          JsFlavors.PLATFORM_DOMAIN,
          JsFlavors.OPTIMIZATION_DOMAIN,
          JsFlavors.TRANSFORM_PROFILE_DOMAIN);
  private final Cache<
          ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>>,
          ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>>
      sourcesToFlavorsCache =
          CacheBuilder.newBuilder()
              .weakKeys()
              .maximumWeight(1 << 16)
              .weigher(
                  (Weigher<ImmutableSet<?>, ImmutableBiMap<?, ?>>)
                      (sources, flavors) -> sources.size())
              .build();

  @Override
  public Class<JsLibraryDescriptionArg> getConstructorArgType() {
    return JsLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JsLibraryDescriptionArg args) {
    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor> sourcesToFlavors;
    try {
      sourcesToFlavors =
          sourcesToFlavorsCache.get(
              args.getSrcs(),
              () -> mapSourcesToFlavors(graphBuilder.getSourcePathResolver(), args.getSrcs()));
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
    Optional<Either<SourcePath, Pair<SourcePath, String>>> file =
        JsFlavors.extractSourcePath(
            sourcesToFlavors.inverse(), buildTarget.getFlavors().getSet().stream());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    CellPathResolver cellRoots = context.getCellPathResolver();
    BuildTarget workerTarget = args.getWorker();
    WorkerTool worker =
        graphBuilder.getRuleWithType(workerTarget, ProvidesWorkerTool.class).getWorkerTool();

    if (file.isPresent()) {
      return buildTarget.getFlavors().contains(JsFlavors.RELEASE)
          ? createReleaseFileRule(
              buildTarget, projectFilesystem, graphBuilder, cellRoots, args, worker)
          : createDevFileRule(
              buildTarget, projectFilesystem, graphBuilder, cellRoots, args, file.get(), worker);
    } else if (buildTarget.getFlavors().contains(JsFlavors.LIBRARY_FILES)) {
      return new LibraryFilesBuilder(graphBuilder, buildTarget, sourcesToFlavors)
          .setSources(args.getSrcs())
          .build(projectFilesystem, worker);
    } else {
      // We allow the `deps_query` to contain different kinds of build targets, but filter out
      // all targets that don't refer to a JsLibrary rule.
      // That prevents users from having to wrap every query into "kind(js_library, ...)".
      Stream<BuildTarget> queryDeps =
          args.getDepsQuery().map(Query::getResolvedQuery).orElseGet(ImmutableSortedSet::of)
              .stream()
              .filter(target -> JsUtil.isJsLibraryTarget(target, context.getTargetGraph()));
      Stream<BuildTarget> declaredDeps = args.getDeps().stream();
      Stream<BuildTarget> deps = Stream.concat(declaredDeps, queryDeps);
      return new LibraryBuilder(context.getTargetGraph(), graphBuilder, buildTarget)
          .setLibraryDependencies(deps)
          .build(projectFilesystem, worker);
    }
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    return JsFlavors.validateFlavors(flavors, FLAVOR_DOMAINS);
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(FLAVOR_DOMAINS);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractJsLibraryDescriptionArg arg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    if (arg.getDepsQuery().isPresent()) {
      extraDepsBuilder.addAll(
          QueryUtils.extractParseTimeTargets(buildTarget, cellRoots, arg.getDepsQuery().get())
              .iterator());
    }
  }

  @RuleArg
  interface AbstractJsLibraryDescriptionArg
      extends BuildRuleArg, HasDepsQuery, HasExtraJson, HasTests {
    ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> getSrcs();

    BuildTarget getWorker();

    @Hint(isDep = false, isInput = false)
    Optional<String> getBasePath();

    @Override
    default JsLibraryDescriptionArg withDepsQuery(Query query) {
      if (getDepsQuery().equals(Optional.of(query))) {
        return (JsLibraryDescriptionArg) this;
      }
      return JsLibraryDescriptionArg.builder().from(this).setDepsQuery(query).build();
    }
  }

  private static class LibraryFilesBuilder {

    private final ActionGraphBuilder graphBuilder;
    private final BuildTarget baseTarget;
    private final ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>
        sourcesToFlavors;
    private final BuildTarget fileBaseTarget;

    @Nullable private ImmutableList<JsFile<?>> jsFileRules;

    public LibraryFilesBuilder(
        ActionGraphBuilder graphBuilder,
        BuildTarget baseTarget,
        ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor> sourcesToFlavors) {
      this.graphBuilder = graphBuilder;
      this.baseTarget = baseTarget;
      this.sourcesToFlavors = sourcesToFlavors;

      // Platform information is only relevant when building release-optimized files.
      // Stripping platform targets from individual files allows us to use the base version of
      // every file in the build for all supported platforms, leading to improved cache reuse.
      // However, we preserve the transform profile flavor domain, because those flavors do
      // affect unoptimized builds.
      this.fileBaseTarget =
          !baseTarget.getFlavors().contains(JsFlavors.RELEASE)
              ? baseTarget.withFlavors(
                  Sets.intersection(
                      baseTarget.getFlavors().getSet(),
                      JsFlavors.TRANSFORM_PROFILE_DOMAIN.getFlavors()))
              : baseTarget;
    }

    private LibraryFilesBuilder setSources(
        ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> sources) {
      this.jsFileRules = ImmutableList.copyOf(sources.stream().map(this::requireJsFile).iterator());
      return this;
    }

    private JsFile<?> requireJsFile(Either<SourcePath, Pair<SourcePath, String>> file) {
      Flavor fileFlavor = sourcesToFlavors.get(file);
      BuildTarget target = fileBaseTarget.withAppendedFlavors(fileFlavor);
      graphBuilder.requireRule(target);
      return graphBuilder.getRuleWithType(target, JsFile.class);
    }

    private JsLibrary.Files build(ProjectFilesystem projectFileSystem, WorkerTool worker) {
      Objects.requireNonNull(jsFileRules, "No source files set");

      return new JsLibrary.Files(
          baseTarget.withAppendedFlavors(JsFlavors.LIBRARY_FILES),
          projectFileSystem,
          graphBuilder,
          jsFileRules.stream()
              .map(JsFile::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          worker);
    }
  }

  private static class LibraryBuilder {

    private final TargetGraph targetGraph;
    private final ActionGraphBuilder graphBuilder;
    private final BuildTarget baseTarget;

    @Nullable private ImmutableList<JsLibrary> libraryDependencies;

    private LibraryBuilder(
        TargetGraph targetGraph, ActionGraphBuilder graphBuilder, BuildTarget baseTarget) {
      this.targetGraph = targetGraph;
      this.baseTarget = baseTarget;
      this.graphBuilder = graphBuilder;
    }

    private LibraryBuilder setLibraryDependencies(Stream<BuildTarget> deps) {
      this.libraryDependencies =
          deps.map(hasFlavors() ? this::addFlavorsToLibraryTarget : Function.identity())
              // `requireRule()` needed for dependencies to flavored versions
              .map(graphBuilder::requireRule)
              .map(this::verifyIsJsLibraryRule)
              .collect(ImmutableList.toImmutableList());
      return this;
    }

    private JsLibrary build(ProjectFilesystem projectFilesystem, WorkerTool worker) {
      Objects.requireNonNull(libraryDependencies, "No library dependencies set");

      BuildTarget filesTarget = baseTarget.withAppendedFlavors(JsFlavors.LIBRARY_FILES);
      graphBuilder.requireRule(filesTarget);
      return new JsLibrary(
          baseTarget,
          projectFilesystem,
          graphBuilder,
          graphBuilder.getRuleWithType(filesTarget, JsLibrary.Files.class).getSourcePathToOutput(),
          libraryDependencies.stream()
              .map(JsLibrary::getSourcePathToOutput)
              .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural())),
          worker);
    }

    private boolean hasFlavors() {
      return !baseTarget.getFlavors().isEmpty();
    }

    private BuildTarget addFlavorsToLibraryTarget(BuildTarget unflavored) {
      return unflavored.withAppendedFlavors(baseTarget.getFlavors().getSet());
    }

    JsLibrary verifyIsJsLibraryRule(BuildRule rule) {
      if (!(rule instanceof JsLibrary)) {
        BuildTarget target = rule.getBuildTarget();
        throw new HumanReadableException(
            "js_library target '%s' can only depend on other js_library targets, but one of its "
                + "dependencies, '%s', is of type %s.",
            baseTarget, target, targetGraph.get(target).getRuleType().getName());
      }

      return (JsLibrary) rule;
    }
  }

  private static BuildRule createReleaseFileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      JsLibraryDescriptionArg args,
      WorkerTool worker) {
    BuildTarget devTarget = withFileFlavorOnly(buildTarget);
    graphBuilder.requireRule(devTarget);
    return JsFile.create(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, cellRoots),
        worker,
        graphBuilder.getRuleWithType(devTarget, JsFile.class).getSourcePathToOutput());
  }

  private static <A extends AbstractJsLibraryDescriptionArg> BuildRule createDevFileRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder,
      CellPathResolver cellRoots,
      A args,
      Either<SourcePath, Pair<SourcePath, String>> source,
      WorkerTool worker) {
    SourcePath sourcePath = source.transform(x -> x, Pair::getFirst);
    Optional<String> subPath = Optional.ofNullable(source.transform(x -> null, Pair::getSecond));

    Optional<Path> virtualPath =
        args.getBasePath()
            .map(
                basePath ->
                    changePathPrefix(
                            sourcePath,
                            basePath,
                            projectFilesystem,
                            graphBuilder.getSourcePathResolver(),
                            cellRoots,
                            buildTarget.getUnflavoredBuildTarget())
                        .resolve(subPath.orElse("")));

    return JsFile.create(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        JsUtil.getExtraJson(args, buildTarget, graphBuilder, cellRoots),
        worker,
        sourcePath,
        subPath,
        virtualPath);
  }

  private static BuildTarget withFileFlavorOnly(BuildTarget target) {
    return target.withFlavors(
        target.getFlavors().getSet().stream()
            .filter(JsFlavors::isFileFlavor)
            .toArray(Flavor[]::new));
  }

  private static ImmutableBiMap<Either<SourcePath, Pair<SourcePath, String>>, Flavor>
      mapSourcesToFlavors(
          SourcePathResolverAdapter sourcePathResolverAdapter,
          ImmutableSet<Either<SourcePath, Pair<SourcePath, String>>> sources) {

    ImmutableBiMap.Builder<Either<SourcePath, Pair<SourcePath, String>>, Flavor> builder =
        ImmutableBiMap.builder();
    for (Either<SourcePath, Pair<SourcePath, String>> source : sources) {
      Path relativePath =
          source.transform(
              sourcePathResolverAdapter::getRelativePath, pair -> Paths.get(pair.getSecond()));
      builder.put(source, JsFlavors.fileFlavorForSourcePath(relativePath));
    }
    return builder.build();
  }

  private static Path changePathPrefix(
      SourcePath sourcePath,
      String basePath,
      ProjectFilesystem projectFilesystem,
      SourcePathResolverAdapter sourcePathResolverAdapter,
      CellPathResolver cellPathResolver,
      UnflavoredBuildTarget target) {
    AbsPath directoryOfBuildFile =
        cellPathResolver.resolveCellRelativePath(target.getCellRelativeBasePath());
    AbsPath transplantTo = MorePaths.normalize(directoryOfBuildFile.resolve(basePath));
    AbsPath absolutePath =
        PathSourcePath.from(sourcePath)
            .map(
                pathSourcePath -> // for sub paths, replace the leading directory with the base path
                transplantTo.resolve(
                        MorePaths.relativize(
                            directoryOfBuildFile.getPath(),
                            sourcePathResolverAdapter.getAbsolutePath(sourcePath))))
            .orElse(transplantTo); // build target output paths are replaced completely

    return projectFilesystem
        .getPathRelativeToProjectRoot(absolutePath.getPath())
        .orElseThrow(
            () ->
                new HumanReadableException(
                    "%s: Using '%s' as base path for '%s' would move the file "
                        + "out of the project root.",
                    target, basePath, sourcePathResolverAdapter.getRelativePath(sourcePath)));
  }
}
