/*
 * Copyright 2015 Google Inc.
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

package com.google.template.soy.passes;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.template.soy.base.internal.IdGenerator;
import com.google.template.soy.base.internal.SoyFileKind;
import com.google.template.soy.base.internal.TriState;
import com.google.template.soy.conformance.ValidatedConformanceConfig;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.logging.ValidatedLoggingConfig;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.restricted.SoyPrintDirective;
import com.google.template.soy.soytree.SoyFileNode;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.types.SoyTypeRegistry;
import java.util.Map;
import javax.annotation.CheckReturnValue;

/**
 * Configures all compiler passes.
 *
 * <p>This arranges all compiler passes into four phases.
 *
 * <ul>
 *   <li>The single file passes. This includes AST rewriting passes such as {@link HtmlRewritePass}
 *       and {@link RewriteGendersPass} and other kinds of validation that doesn't require
 *       information about the full file set.
 *   <li>Cross template checking passes. This includes AST validation passes like the {@link
 *       CheckVisibilityPass}. Passes should run here if they need to check the relationships
 *       between templates.
 *   <li>The autoescaper. This runs in its own special phase because it can do special things like
 *       create synthetic templates and add them to the tree.
 *   <li>Simplification passes. This includes tree simplification passes like the optimizer. These
 *       should run last so that they can simplify code generated by any earlier pass.
 * </ul>
 *
 * <p>The reason things have been divided in this way is partially to create consistency and also to
 * enable other compiler features. For example, for in process (server side) compilation we can
 * cache the results of the single file passes to speed up edit-refresh flows. Also, theoretically,
 * we could run the single file passes for each file in parallel.
 *
 * <p>A note on ordering. There is no real structure to the ordering of the passes beyond what is
 * documented in comments. Many passes do rely on running before/after a different pass (e.g. {@link
 * ResolveExpressionTypesPass} needs to run after {@link ResolveNamesPass}), but there isn't any
 * dependency system in place.
 */
public final class PassManager {

  /**
   * Pass continuation rules.
   *
   * <p>These rules are used when running compile passes. You can stop compilation either before or
   * after a pass. By default, compilation continues after each pass without stopping.
   */
  public enum PassContinuationRule {
    CONTINUE,
    STOP_BEFORE_PASS,
    STOP_AFTER_PASS,
  };

  private final ImmutableList<CompilerFilePass> singleFilePasses;
  private final ImmutableList<CompilerFileSetPass> crossTemplateCheckingPasses;
  private final ErrorReporter errorReporter;
  private final ImmutableMap<String, PassContinuationRule> passContinuationRegistry;
  private boolean isPassManagerStopped = false;

  /**
   * Transform all the STOP_AFTER rules into STOP_BEFORE_PASS.
   *
   * <p>This greatly simplifies the logic inside {@link #runSingleFilePasses(SoyFileNode,
   * IdGenerator)} and {@link #runWholeFilesetPasses(SoyFileSetNode)}.
   */
  @VisibleForTesting
  static ImmutableMap<String, PassContinuationRule> remapPassContinuationRegistry(
      Map<String, PassContinuationRule> passContinuationRegistry,
      ImmutableList<CompilerFilePass> passList) {
    ImmutableMap.Builder<String, PassContinuationRule> remappedRegistry = ImmutableMap.builder();
    for (ImmutableMap.Entry<String, PassContinuationRule> entry :
        passContinuationRegistry.entrySet()) {
      switch (entry.getValue()) {
        case STOP_AFTER_PASS:
          final String passName = entry.getKey();
          int passIndex =
              Iterables.indexOf(
                  passList,
                  new Predicate<CompilerFilePass>() {
                    @Override
                    public boolean apply(CompilerFilePass input) {
                      return input.name().equals(passName);
                    }
                  });
          checkState(passIndex != -1);
          // Remap STOP_AFTER to STOP_BEFORE only if the STOP_AFTER rule is not on the last pass.
          if (passIndex < passList.size() - 1) {
            remappedRegistry.put(
                passList.get(passIndex + 1).name(), PassContinuationRule.STOP_BEFORE_PASS);
          }
          break;
        case STOP_BEFORE_PASS:
          remappedRegistry.put(entry);
          break;
        case CONTINUE:
          // The CONTINUE rule is a no-op.
          break;
      }
    }
    return remappedRegistry.build();
  }

  private PassManager(Builder builder) {
    SoyTypeRegistry registry = checkNotNull(builder.registry);
    this.errorReporter = checkNotNull(builder.errorReporter);
    SoyGeneralOptions options = checkNotNull(builder.opts);
    boolean allowUnknownGlobals = builder.allowUnknownGlobals;
    boolean disableAllTypeChecking = builder.disableAllTypeChecking;

    // Single file passes
    // These passes perform tree rewriting and all compiler checks that don't require information
    // about callees.
    // Note that we try to run all of the single file passes to report as many errors as possible,
    // meaning that errors reported in earlier passes do not prevent running subsequent passes.

    ImmutableList.Builder<CompilerFilePass> singleFilePassesBuilder =
        ImmutableList.<CompilerFilePass>builder()
            .add(new HtmlRewritePass(errorReporter))
            .add(new BasicHtmlValidationPass(errorReporter))
            // The check conformance pass needs to run on the rewritten html nodes, so it must
            // run after HtmlRewritePass
            .add(new SoyConformancePass(builder.conformanceConfig, errorReporter))
            // needs to run after htmlrewriting, before resolvenames and autoescaping
            .add(new ContentSecurityPolicyNonceInjectionPass(errorReporter))
            // Needs to run after HtmlRewritePass since it produces the HtmlTagNodes that we use to
            // create placeholders.
            .add(new InsertMsgPlaceholderNodesPass(errorReporter))
            .add(new RewriteRemaindersPass(errorReporter))
            .add(new RewriteGenderMsgsPass(errorReporter))
            // Needs to come after any pass that manipulates msg placeholders.
            .add(new CalculateMsgSubstitutionInfoPass(errorReporter))
            .add(new CheckNonEmptyMsgNodesPass(errorReporter))
            // Run before the RewriteGlobalsPass as it removes some globals.
            .add(new VeRewritePass())
            .add(new RewriteGlobalsPass(registry, options.getCompileTimeGlobals(), errorReporter))
            // needs to happen after rewrite globals
            .add(new XidPass(errorReporter))
            // Needs to be before ResolveNamesPass.
            .add(new V1ExpressionPass(builder.allowV1Expression, errorReporter))
            .add(new ResolveNamesPass(errorReporter))
            // needs to be after ResolveNames and MsgsPass
            .add(new MsgWithIdFunctionPass(errorReporter))
            // can run anywhere
            .add(new CheckEscapingSanityFilePass(errorReporter));
    // The StrictHtmlValidatorPass needs to run after ResolveNames.
    if (options.getExperimentalFeatures().contains("new_html_matcher")) {
      singleFilePassesBuilder.add(new StrictHtmlValidationPassNewMatcher(errorReporter));
    } else {
      singleFilePassesBuilder.add(new StrictHtmlValidationPass(errorReporter));
    }

    if (builder.addHtmlAttributesForDebugging) {
      // needs to run after MsgsPass (so we don't mess up the auto placeholder naming algorithm) and
      // before ResolveExpressionTypesPass (since we insert expressions).
      singleFilePassesBuilder.add(new AddDebugAttributesPass());
    }
    if (!disableAllTypeChecking) {
      singleFilePassesBuilder.add(new CheckDeclaredTypesPass(errorReporter));
      VeLogValidator veLogValidator = new VeLogValidator(builder.loggingConfig, errorReporter);
      singleFilePassesBuilder.add(
          new ResolveExpressionTypesPass(registry, errorReporter, veLogValidator));
      // needs to run after both resolve types and htmlrewrite pass
      singleFilePassesBuilder.add(new VeLogValidationPass(errorReporter, veLogValidator));
    }
    singleFilePassesBuilder.add(new ResolvePackageRelativeCssNamesPass(errorReporter));
    if (!allowUnknownGlobals) {
      // Must come after RewriteGlobalsPass since that is when values are substituted.
      // We should also run after the ResolveNamesPass which checks for global/param ambiguity and
      // may issue better error messages.
      singleFilePassesBuilder.add(new CheckGlobalsPass(errorReporter));
    }
    singleFilePassesBuilder.add(new ValidateAliasesPass(registry, errorReporter, options));
    // If requiring strict autoescaping, check and enforce it.
    if (options.isStrictAutoescapingRequired() == TriState.ENABLED) {
      singleFilePassesBuilder.add(new AssertStrictAutoescapingPass(errorReporter));
    }
    // Needs to run after HtmlRewritePass.
    singleFilePassesBuilder.add(new KeyCommandPass(errorReporter));
    // Needs to run after HtmlRewritePass and StrictHtmlValidationPass (for single root validation).
    singleFilePassesBuilder.add(new SoyElementPass(errorReporter));

    this.singleFilePasses = singleFilePassesBuilder.build();
    this.passContinuationRegistry =
        remapPassContinuationRegistry(builder.passContinuationRegistry, this.singleFilePasses);

    // Cross template checking passes

    // Fileset passes run on all sources files and have access to a template registry so they can
    // examine information about dependencies. These are naturally more expensive and should be
    // reserved for checks that require transitive call information (or full delegate sets).
    // Notably, the results of these passes cannot be cached in the AST cache.  So minimize their
    // use.
    ImmutableList.Builder<CompilerFileSetPass> crossTemplateCheckingPassesBuilder =
        ImmutableList.<CompilerFileSetPass>builder()
            .add(new CheckTemplateHeaderVarsPass(errorReporter));
    if (!disableAllTypeChecking) {
      crossTemplateCheckingPassesBuilder.add(new CheckTemplateCallsPass(errorReporter));
    }
    crossTemplateCheckingPassesBuilder
        .add(new CheckTemplateVisibilityPass(errorReporter))
        .add(new CheckDelegatesPass(errorReporter));
    // If disallowing external calls, perform the check.
    if (options.allowExternalCalls() == TriState.DISABLED) {
      crossTemplateCheckingPassesBuilder.add(new StrictDepsPass(errorReporter));
    }
    // if htmlrewriting is enabled, don't desugar because later passes want the nodes
    // we need to run this here, before the autoescaper because the autoescaper may choke on lots
    // of little raw text nodes.  The desguaring pass and rewrite passes above may produce empty
    // raw text nodes and lots of consecutive raw text nodes.  This will eliminate them
    crossTemplateCheckingPassesBuilder.add(new CombineConsecutiveRawTextNodesPass());

    if (builder.autoescaperEnabled) {
      crossTemplateCheckingPassesBuilder.add(
          new AutoescaperPass(errorReporter, builder.soyPrintDirectives));
      // Relies on information from the autoescaper and valid type information
      if (!disableAllTypeChecking) {
        crossTemplateCheckingPassesBuilder.add(new CheckBadContextualUsagePass(errorReporter));
      }
    }

    // Simplification Passes.
    // These tend to simplify or canonicalize the tree in order to simplify the task of code
    // generation.

    if (builder.desugarHtmlNodes) {
      // always desugar before the end since the backends (besides incremental dom) cannot handle
      // the nodes.
      crossTemplateCheckingPassesBuilder.add(new DesugarHtmlNodesPass());
    }
    // TODO(lukes): there should only be one way to disable the optimizer, not 2
    if (builder.optimize && options.isOptimizerEnabled()) {
      crossTemplateCheckingPassesBuilder.add(new OptimizationPass());
    }
    // A number of the passes above (desugar, htmlrewrite), may chop up raw text nodes, and the
    // Optimizer may produce additional RawTextNodes.
    // Stich them back together here.
    crossTemplateCheckingPassesBuilder.add(new CombineConsecutiveRawTextNodesPass());
    this.crossTemplateCheckingPasses = crossTemplateCheckingPassesBuilder.build();
  }

  public void runSingleFilePasses(SoyFileNode file, IdGenerator nodeIdGen) {
    if (isPassManagerStopped) {
      return;
    }
    for (CompilerFilePass pass : singleFilePasses) {
      // All single file passes only run on source files
      if (file.getSoyFileKind() != SoyFileKind.SRC) {
        continue;
      }
      PassContinuationRule rule =
          passContinuationRegistry.getOrDefault(pass.name(), PassContinuationRule.CONTINUE);
      // At this point, all the pass continuation rules have either been remapped to
      // STOP_BEFORE_PASS or removed as no-ops.
      if (rule == PassContinuationRule.STOP_BEFORE_PASS) {
        isPassManagerStopped = true;
        break;
      } else {
        pass.run(file, nodeIdGen);
      }
    }
  }

  /**
   * Runs all the fileset passes including the autoescaper and optimization passes if configured.
   *
   * @return a fully populated TemplateRegistry
   */
  @CheckReturnValue
  public TemplateRegistry runWholeFilesetPasses(final SoyFileSetNode soyTree) {
    final TemplateRegistry templateRegistry = new TemplateRegistry(soyTree, errorReporter);

    if (isPassManagerStopped) {
      return templateRegistry;
    }

    ImmutableList<SoyFileNode> sourceFiles = soyTree.getSourceFiles();
    IdGenerator idGenerator = soyTree.getNodeIdGenerator();
    for (CompilerFileSetPass pass : crossTemplateCheckingPasses) {
      PassContinuationRule rule =
          passContinuationRegistry.getOrDefault(pass.name(), PassContinuationRule.CONTINUE);
      // At this point, all the pass continuation rules have either been remapped to
      // STOP_BEFORE_PASS or removed as no-ops.
      if (rule == PassContinuationRule.STOP_BEFORE_PASS) {
        isPassManagerStopped = true;
      } else {
        isPassManagerStopped =
            pass.run(sourceFiles, idGenerator, templateRegistry) == CompilerFileSetPass.Result.STOP;
      }
      if (isPassManagerStopped) {
        break;
      }
    }
    return templateRegistry;
  }

  /** A builder for configuring the pass manager. */
  public static final class Builder {
    private SoyTypeRegistry registry;
    private ImmutableMap<String, ? extends SoyPrintDirective> soyPrintDirectives;
    private ErrorReporter errorReporter;
    private SoyGeneralOptions opts;
    private boolean allowUnknownGlobals;
    private boolean allowV1Expression;
    private boolean disableAllTypeChecking;
    private boolean desugarHtmlNodes = true;
    private boolean optimize = true;
    private ValidatedConformanceConfig conformanceConfig = ValidatedConformanceConfig.EMPTY;
    private ValidatedLoggingConfig loggingConfig = ValidatedLoggingConfig.EMPTY;
    private boolean autoescaperEnabled = true;
    private boolean addHtmlAttributesForDebugging = true;
    private final Map<String, PassContinuationRule> passContinuationRegistry = Maps.newHashMap();

    public Builder setErrorReporter(ErrorReporter errorReporter) {
      this.errorReporter = checkNotNull(errorReporter);
      return this;
    }

    public Builder setSoyPrintDirectiveMap(
        ImmutableMap<String, ? extends SoyPrintDirective> printDirectives) {
      this.soyPrintDirectives = checkNotNull(printDirectives);
      return this;
    }

    public Builder setTypeRegistry(SoyTypeRegistry registry) {
      this.registry = checkNotNull(registry);
      return this;
    }

    public Builder setGeneralOptions(SoyGeneralOptions opts) {
      this.opts = opts;
      return this;
    }

    /**
     * Configures the {@link PassManager} to run conformance passes only.
     *
     * <p>The conformance passes are {@link HtmlRewritePass} and {@link SoyConformancePass}.
     */
    public Builder forceConformanceOnly() {
      // Conformance tests only operate on single files.
      return addPassContinuationRule("SoyConformance", PassContinuationRule.STOP_AFTER_PASS);
    }

    /**
     * Disables all the passes which enforce and rely on type information.
     *
     * <p>This should only be used for things like message extraction which doesn't tend to be
     * configured with a type registry.
     */
    public Builder disableAllTypeChecking() {
      this.disableAllTypeChecking = true;
      return this;
    }

    /**
     * Allows unknown global references.
     *
     * <p>This option is only available for backwards compatibility with legacy js only templates
     * and for parseinfo generation.
     */
    public Builder allowUnknownGlobals() {
      this.allowUnknownGlobals = true;
      return this;
    }

    /**
     * Allows v1Expression().
     *
     * <p>This option is only available for backwards compatibility with legacy JS only templates.
     */
    public Builder allowV1Expression() {
      this.allowV1Expression = true;
      return this;
    }

    /**
     * Whether to turn all the html nodes back into raw text nodes before code generation.
     *
     * <p>The default is {@code true}.
     */
    public Builder desugarHtmlNodes(boolean desugarHtmlNodes) {
      this.desugarHtmlNodes = desugarHtmlNodes;
      return this;
    }

    /**
     * Whether to run any of the optimization passes.
     *
     * <p>The default is {@code true}.
     */
    public Builder optimize(boolean optimize) {
      this.optimize = optimize;
      return this;
    }

    public Builder addHtmlAttributesForDebugging(boolean addHtmlAttributesForDebugging) {
      this.addHtmlAttributesForDebugging = addHtmlAttributesForDebugging;
      return this;
    }

    /** Configures this passmanager to run the conformance pass using the given config object. */
    public Builder setConformanceConfig(ValidatedConformanceConfig conformanceConfig) {
      this.conformanceConfig = checkNotNull(conformanceConfig);
      return this;
    }

    public Builder setLoggingConfig(ValidatedLoggingConfig loggingConfig) {
      this.loggingConfig = checkNotNull(loggingConfig);
      return this;
    }

    /**
     * Can be used to enable/disable the autoescaper.
     *
     * <p>The autoescaper is enabled by default.
     */
    public Builder setAutoescaperEnabled(boolean autoescaperEnabled) {
      this.autoescaperEnabled = autoescaperEnabled;
      return this;
    }

    /**
     * Registers a pass continuation rule.
     *
     * <p>By default, compilation continues after each pass. You can stop compilation before or
     * after any pass. This is useful for testing, or for running certain kinds of passes, such as
     * conformance-only compilations.
     *
     * <p>This method overwrites any previously registered rule.
     *
     * @param passName the pass name is derived from the pass class name. For example, the {@link
     *     ResolveNamesPass} is named "ResolveNames". See {@link CompilerFilePass#name()}.
     */
    public Builder addPassContinuationRule(String passName, PassContinuationRule rule) {
      passContinuationRegistry.put(passName, rule);
      return this;
    }

    public PassManager build() {
      return new PassManager(this);
    }
  }
}
