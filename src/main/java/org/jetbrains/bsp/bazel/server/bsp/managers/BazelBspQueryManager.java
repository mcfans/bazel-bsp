package org.jetbrains.bsp.bazel.server.bsp.managers;

import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.BuildTargetCapabilities;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;
import ch.epfl.scala.bsp4j.SourceItem;
import ch.epfl.scala.bsp4j.SourceItemKind;
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.jetbrains.bsp.bazel.commons.Constants;
import org.jetbrains.bsp.bazel.commons.Uri;
import org.jetbrains.bsp.bazel.server.bazel.BazelProcess;
import org.jetbrains.bsp.bazel.server.bazel.BazelRunner;
import org.jetbrains.bsp.bazel.server.bazel.data.BazelData;
import org.jetbrains.bsp.bazel.server.bazel.params.BazelQueryKindParameters;
import org.jetbrains.bsp.bazel.server.bazel.params.BazelRunnerFlag;
import org.jetbrains.bsp.bazel.server.bep.BepServer;
import org.jetbrains.bsp.bazel.server.bsp.config.BazelBspServerConfig;
import org.jetbrains.bsp.bazel.server.bsp.resolvers.QueryResolver;

public class BazelBspQueryManager {
  private static final Logger LOGGER = LogManager.getLogger(BazelBspQueryManager.class);

  private final BazelBspServerConfig serverConfig;
  private final BazelData bazelData;
  private final BazelRunner bazelRunner;
  private final BazelBspTargetManager bazelBspTargetManager;
  private BepServer bepServer;

  public BazelBspQueryManager(
      BazelBspServerConfig serverConfig,
      BazelData bazelData,
      BazelRunner bazelRunner,
      BazelBspTargetManager bazelBspTargetManager) {
    this.serverConfig = serverConfig;
    this.bazelData = bazelData;
    this.bazelRunner = bazelRunner;
    this.bazelBspTargetManager = bazelBspTargetManager;
  }

  public Either<ResponseError, WorkspaceBuildTargetsResult> getWorkspaceBuildTargets() {
    List<String> projectPaths = serverConfig.getTargetProjectPaths();
    // TODO (abrams27) simplify
    List<BuildTarget> targets = new ArrayList<>();

    for (String projectPath : projectPaths) {
      targets.addAll(getBuildTargetForProjectPath(projectPath));
    }
    return Either.forRight(new WorkspaceBuildTargetsResult(targets));
  }

  public List<SourceItem> getSourceItems(Build.Rule rule, BuildTargetIdentifier label) {
    List<SourceItem> srcs = getSrcs(rule, false);
    srcs.addAll(getSrcs(rule, true));
    // TODO (abrams27) fix updating
    bepServer.getBuildTargetsSources().put(label, srcs);
    return srcs;
  }

  private BuildTarget getBuildTargetForRule(Build.Rule rule) {
    String name = rule.getName();
    LOGGER.info("Getting targets for rule: " + name);

    LOGGER.info("Getting deps");
    List<BuildTargetIdentifier> deps =
        rule.getAttributeList().stream()
            .filter(attribute -> attribute.getName().equals("deps"))
            .flatMap(srcDeps -> srcDeps.getStringListValueList().stream())
            .map(BuildTargetIdentifier::new)
            .collect(Collectors.toList());
    BuildTargetIdentifier label = new BuildTargetIdentifier(name);
    LOGGER.info("Getting source items");
    List<SourceItem> sources = getSourceItems(rule, label);
    Set<String> extensions = new TreeSet<>();
    LOGGER.info("Got source items");
    for (SourceItem source : sources) {
      if (source.getUri().endsWith(Constants.SCALA_EXTENSION)) {
        extensions.add(Constants.SCALA);
      } else if (source.getUri().endsWith(Constants.JAVA_EXTENSION)) {
        extensions.add(Constants.JAVA);
      } else if (source.getUri().endsWith(Constants.KOTLIN_EXTENSION)) {
        extensions.add(Constants.KOTLIN);
        extensions.add(
            Constants.JAVA); // TODO(andrefmrocha): Remove this when kotlin is natively supported
      }
    }

    String ruleClass = rule.getRuleClass();
    BuildTarget target =
        new BuildTarget(
            label,
            new ArrayList<>(),
            new ArrayList<>(extensions),
            deps,
            new BuildTargetCapabilities(
                true,
                ruleClass.endsWith("_" + Constants.TEST_RULE_TYPE),
                ruleClass.endsWith("_" + Constants.BINARY_RULE_TYPE)));
    LOGGER.info("Target: " + target);
    target.setBaseDirectory(
        Uri.packageDirFromLabel(label.getUri(), bazelData.getWorkspaceRoot()).toString());
    target.setDisplayName(label.getUri());
    bazelBspTargetManager.fillTargetData(target, extensions, ruleClass, rule);
    return target;
  }

  private List<BuildTarget> getBuildTargetForProjectPath(String projectPath) {
    List<BazelQueryKindParameters> kindParameters =
        ImmutableList.of(
            BazelQueryKindParameters.fromPatternAndInput("binary", projectPath),
            BazelQueryKindParameters.fromPatternAndInput("library", projectPath),
            BazelQueryKindParameters.fromPatternAndInput("test", projectPath));

    BazelProcess bazelProcess =
        bazelRunner
            .commandBuilder()
            .query()
            .withFlag(BazelRunnerFlag.OUTPUT_PROTO)
            .withFlag(BazelRunnerFlag.NOHOST_DEPS)
            .withFlag(BazelRunnerFlag.NOIMPLICIT_DEPS)
            .withKinds(kindParameters)
            .executeBazelBesCommand();

    Build.QueryResult queryResult = QueryResolver.getQueryResultForProcess(bazelProcess);

    return queryResult.getTargetList().stream()
        .map(Build.Target::getRule)
        .filter(rule -> !rule.getRuleClass().equals("filegroup"))
        .map(this::getBuildTargetForRule)
        .collect(Collectors.toList());
  }

  public List<String> getResources(Build.Rule rule, Build.QueryResult queryResult) {
    return rule.getAttributeList().stream()
        .filter(
            attribute ->
                attribute.getName().equals("resources")
                    && attribute.hasExplicitlySpecified()
                    && attribute.getExplicitlySpecified())
        .flatMap(
            attribute -> {
              List<Build.Target> targetsRule =
                  attribute.getStringListValueList().stream()
                      .map(label -> isPackage(queryResult, label))
                      .filter(targets -> !targets.isEmpty())
                      .flatMap(Collection::stream)
                      .collect(Collectors.toList());
              List<String> targetsResources = getResourcesOutOfRule(targetsRule);

              List<String> resources =
                  attribute.getStringListValueList().stream()
                      .filter(label -> isPackage(queryResult, label).isEmpty())
                      .map(
                          label ->
                              Uri.fromFileLabel(label, bazelData.getWorkspaceRoot()).toString())
                      .collect(Collectors.toList());

              return Stream.concat(targetsResources.stream(), resources.stream());
            })
        .collect(Collectors.toList());
  }

  private List<? extends Build.Target> isPackage(Build.QueryResult queryResult, String label) {
    return queryResult.getTargetList().stream()
        .filter(target -> target.hasRule() && target.getRule().getName().equals(label))
        .collect(Collectors.toList());
  }

  private List<String> getResourcesOutOfRule(List<Build.Target> rules) {
    return rules.stream()
        .flatMap(resourceRule -> resourceRule.getRule().getAttributeList().stream())
        .filter((srcAttribute) -> srcAttribute.getName().equals("srcs"))
        .flatMap(resourceAttribute -> resourceAttribute.getStringListValueList().stream())
        .map(src -> Uri.fromFileLabel(src, bazelData.getWorkspaceRoot()).toString())
        .collect(Collectors.toList());
  }

  private List<SourceItem> getSrcs(Build.Rule rule, boolean isGenerated) {
    String srcType = isGenerated ? "generated_srcs" : "srcs";
    return getSrcsPaths(rule, srcType).stream()
        .map(uri -> new SourceItem(uri.toString(), SourceItemKind.FILE, isGenerated))
        .collect(Collectors.toList());
  }

  private List<Uri> getSrcsPaths(Build.Rule rule, String srcType) {
    return rule.getAttributeList().stream()
        .filter(attribute -> attribute.getName().equals(srcType))
        .flatMap(srcsSrc -> srcsSrc.getStringListValueList().stream())
        .flatMap(
            dep -> {
              if (isSourceFile(dep)) {
                return Lists.newArrayList(Uri.fromFileLabel(dep, bazelData.getWorkspaceRoot()))
                    .stream();
              }
              BazelProcess bazelProcess =
                  bazelRunner
                      .commandBuilder()
                      .query()
                      .withFlag(BazelRunnerFlag.OUTPUT_PROTO)
                      .withArgument(dep)
                      .executeBazelBesCommand();

              Build.QueryResult queryResult = QueryResolver.getQueryResultForProcess(bazelProcess);

              return queryResult.getTargetList().stream()
                  .map(Build.Target::getRule)
                  .flatMap(queryRule -> getSrcsPaths(queryRule, srcType).stream())
                  .collect(Collectors.toList())
                  .stream();
            })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private boolean isSourceFile(String dep) {
    return Constants.FILE_EXTENSIONS.stream().anyMatch(dep::endsWith) && !dep.startsWith("@");
  }

  public void setBepServer(BepServer bepServer) {
    this.bepServer = bepServer;
  }
}
