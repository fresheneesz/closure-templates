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

package com.google.template.soy.jbcsrc;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.lenientFormat;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Fact.simpleFact;
import static com.google.template.soy.data.SoyValueConverter.EMPTY_DICT;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.truth.FailureMetadata;
import com.google.common.truth.IterableSubject;
import com.google.common.truth.Subject;
import com.google.common.truth.ThrowableSubject;
import com.google.common.truth.Truth;
import com.google.template.soy.SoyFileSetParser;
import com.google.template.soy.SoyFileSetParser.ParseResult;
import com.google.template.soy.SoyFileSetParserBuilder;
import com.google.template.soy.basetree.CopyState;
import com.google.template.soy.data.LoggingAdvisingAppendable;
import com.google.template.soy.data.LoggingAdvisingAppendable.BufferingAppendable;
import com.google.template.soy.data.SoyRecord;
import com.google.template.soy.data.SoyValueConverter;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyError;
import com.google.template.soy.exprtree.FunctionNode;
import com.google.template.soy.jbcsrc.api.RenderResult;
import com.google.template.soy.jbcsrc.internal.ClassData;
import com.google.template.soy.jbcsrc.internal.MemoryClassLoader;
import com.google.template.soy.jbcsrc.shared.CompiledTemplate;
import com.google.template.soy.jbcsrc.shared.CompiledTemplates;
import com.google.template.soy.jbcsrc.shared.LegacyFunctionAdapter;
import com.google.template.soy.jbcsrc.shared.RenderContext;
import com.google.template.soy.msgs.SoyMsgBundle;
import com.google.template.soy.plugin.restricted.SoySourceFunction;
import com.google.template.soy.shared.SoyCssRenamingMap;
import com.google.template.soy.shared.SoyGeneralOptions;
import com.google.template.soy.shared.SoyIdRenamingMap;
import com.google.template.soy.shared.internal.InternalPlugins;
import com.google.template.soy.shared.internal.SoySimpleScope;
import com.google.template.soy.shared.restricted.SoyFunction;
import com.google.template.soy.shared.restricted.SoyJavaFunction;
import com.google.template.soy.shared.restricted.SoyJavaPrintDirective;
import com.google.template.soy.soytree.SoyFileSetNode;
import com.google.template.soy.soytree.SoyTreeUtils;
import com.google.template.soy.soytree.TemplateRegistry;
import com.google.template.soy.types.SoyTypeRegistry;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckReturnValue;

/** Utilities for testing compiled soy templates. */
public final class TemplateTester {

  private static RenderContext.Builder createDefaultBuilder() {
    return new RenderContext.Builder()
        .withSoyPrintDirectives(
            InternalPlugins.internalDirectiveMap(new SoySimpleScope())
                .entrySet()
                .stream()
                .filter(e -> e.getValue() instanceof SoyJavaPrintDirective)
                .collect(
                    toImmutableMap(Map.Entry::getKey, e -> (SoyJavaPrintDirective) e.getValue())));
  }

  static RenderContext getDefaultContext(CompiledTemplates templates) {
    return getDefaultContext(
        templates, Predicates.<String>alwaysFalse(), /* debugSoyTemplateInfo= */ false);
  }

  static RenderContext getDefaultContext(
      CompiledTemplates templates, Predicate<String> activeDelPackages) {
    return getDefaultContext(templates, activeDelPackages, /* debugSoyTemplateInfo= */ false);
  }

  static RenderContext getDefaultContext(
      CompiledTemplates templates,
      Predicate<String> activeDelPackages,
      boolean debugSoyTemplateInfo) {
    return createDefaultBuilder()
        .withActiveDelPackageSelector(activeDelPackages)
        .withCompiledTemplates(templates)
        .withDebugSoyTemplateInfo(debugSoyTemplateInfo)
        .build();
  }

  static RenderContext getDefaultContextWithDebugInfo(CompiledTemplates templates) {
    return getDefaultContext(
        templates, Predicates.<String>alwaysFalse(), /* debugSoyTemplateInfo= */ true);
  }

  private static final Subject.Factory<CompiledTemplateSubject, String> FACTORY =
      CompiledTemplateSubject::new;

  /**
   * Returns a truth subject that can be used to assert on an template given the template body.
   *
   * <p>The given body lines are wrapped in a template called {@code ns.foo} that has no params.
   */
  public static CompiledTemplateSubject assertThatTemplateBody(String... body) {
    String template = toTemplate(body);
    return assertThatFile(template);
  }

  /**
   * Returns a truth subject that can be used to assert on an element given the element body.
   *
   * <p>The given body lines are wrapped in a template called {@code ns.foo} that has no params.
   */
  public static CompiledTemplateSubject assertThatElementBody(String... body) {
    String template = toElement(body);
    return assertThatFile(template);
  }

  static CompiledTemplateSubject assertThatFile(String... template) {
    return Truth.assertAbout(FACTORY).that(Joiner.on('\n').join(template));
  }

  /**
   * Returns a {@link com.google.template.soy.jbcsrc.shared.CompiledTemplates} for the given
   * template body. Containing a single template {@code ns.foo} with the given body
   */
  public static CompiledTemplates compileTemplateBody(String... body) {
    return compileFile(toTemplate(body));
  }

  static SoyRecord asRecord(Map<String, ?> params) {
    return (SoyRecord) SoyValueConverter.INSTANCE.convert(params);
  }

  static final class CompiledTemplateSubject extends Subject<CompiledTemplateSubject, String> {
    private final List<SoyFunction> soyFunctions = new ArrayList<>();
    private final List<SoySourceFunction> soySourceFunctions = new ArrayList<>();
    private final RenderContext.Builder defaultContextBuilder = createDefaultBuilder();

    private Iterable<ClassData> classData;
    private CompiledTemplate.Factory factory;
    private SoyTypeRegistry typeRegistry = new SoyTypeRegistry();
    private SoyGeneralOptions generalOptions = new SoyGeneralOptions();
    private RenderContext defaultContext;

    private CompiledTemplateSubject(FailureMetadata failureMetadata, String subject) {
      super(failureMetadata, subject);
    }

    CompiledTemplateSubject withTypeRegistry(SoyTypeRegistry typeRegistry) {
      classData = null;
      factory = null;
      this.typeRegistry = typeRegistry;
      return this;
    }

    CompiledTemplateSubject withLegacySoyFunction(SoyFunction soyFunction) {
      classData = null;
      factory = null;
      this.soyFunctions.add(checkNotNull(soyFunction));
      return this;
    }

    CompiledTemplateSubject withSoySourceFunction(SoySourceFunction soySourceFunction) {
      classData = null;
      factory = null;
      this.soySourceFunctions.add(checkNotNull(soySourceFunction));
      return this;
    }

    CompiledTemplateSubject withGeneralOptions(SoyGeneralOptions options) {
      this.generalOptions = options;
      return this;
    }

    CompiledTemplateSubject withCssRenamingMap(SoyCssRenamingMap renamingMap) {
      this.defaultContextBuilder.withCssRenamingMap(renamingMap);
      return this;
    }

    CompiledTemplateSubject withXidRenamingMap(SoyIdRenamingMap renamingMap) {
      this.defaultContextBuilder.withXidRenamingMap(renamingMap);
      return this;
    }

    CompiledTemplateSubject logsOutput(String expected) {
      compile();
      return rendersAndLogs("", expected, EMPTY_DICT, EMPTY_DICT, defaultContext);
    }

    CompiledTemplateSubject rendersAs(String expected) {
      compile();
      return rendersAndLogs(expected, "", EMPTY_DICT, EMPTY_DICT, defaultContext);
    }

    CompiledTemplateSubject rendersAs(String expected, Map<String, ?> params) {
      compile();
      return rendersAndLogs(expected, "", asRecord(params), EMPTY_DICT, defaultContext);
    }

    CompiledTemplateSubject rendersAs(String expected, Map<String, ?> params, Map<String, ?> ij) {
      compile();
      return rendersAndLogs(expected, "", asRecord(params), asRecord(ij), defaultContext);
    }

    CompiledTemplateSubject failsToRenderWith(Class<? extends Throwable> expected) {
      return failsToRenderWith(expected, ImmutableMap.<String, Object>of());
    }

    CompiledTemplateSubject failsToRenderWith(
        Class<? extends Throwable> expected, Map<String, ?> params) {
      BufferingAppendable builder = LoggingAdvisingAppendable.buffering();
      compile();
      try {
        factory.create(asRecord(params), EMPTY_DICT).render(builder, defaultContext);
        failWithoutActual(
            simpleFact(
                String.format(
                    "Expected %s to fail to render with a %s, but it rendered '%s'",
                    actual(), expected, "")));
      } catch (Throwable t) {
        if (!expected.isInstance(t)) {
          failWithBadResults("failsToRenderWith", expected, "failed with", t);
        }
      }
      return this; // may be dead
    }

    @CheckReturnValue
    ThrowableSubject failsToRenderWithExceptionThat() {
      return failsToRenderWithExceptionThat(ImmutableMap.of());
    }

    @CheckReturnValue
    ThrowableSubject failsToRenderWithExceptionThat(Map<String, ?> params) {
      BufferingAppendable builder = LoggingAdvisingAppendable.buffering();
      compile();
      try {
        factory.create(asRecord(params), EMPTY_DICT).render(builder, defaultContext);
        failWithoutActual(
            simpleFact(
                String.format(
                    "Expected %s to fail to render, but it rendered '%s'.",
                    actual(), builder.toString())));
      } catch (Throwable t) {
        return check().that(t);
      }
      throw new AssertionError("unreachable");
    }

    @CheckReturnValue
    public IterableSubject failsToCompileWithErrorsThat() {
      SoyFileSetParserBuilder builder =
          SoyFileSetParserBuilder.forFileContents(actual())
              .enableExperimentalFeatures(ImmutableList.of("prop_vars"));
      for (SoyFunction function : soyFunctions) {
        builder.addSoyFunction(function);
      }
      builder.addSoySourceFunctions(soySourceFunctions);
      SoyFileSetParser parser =
          builder
              .typeRegistry(typeRegistry)
              .options(generalOptions)
              .errorReporter(ErrorReporter.exploding())
              .build();
      ParseResult parseResult = parser.parse();
      ErrorReporter errors = ErrorReporter.createForTest();
      Optional<CompiledTemplates> template =
          BytecodeCompiler.compile(
              parseResult.registry(),
              /* developmentMode= */ false,
              errors,
              parser.soyFileSuppliers(),
              typeRegistry);
      if (template.isPresent()) {
        failWithoutActual(
            simpleFact(
                String.format(
                    "Expected %s to fail to compile, but it compiled successfully.", actual())));
      }
      return check().that(Lists.transform(errors.getErrors(), SoyError::message));
    }

    private SoyRecord asRecord(Map<String, ?> params) {
      return (SoyRecord) SoyValueConverter.INSTANCE.convert(params);
    }

    private CompiledTemplateSubject rendersAndLogs(
        String expectedOutput,
        String expectedLogged,
        SoyRecord params,
        SoyRecord ij,
        RenderContext context) {
      CompiledTemplate template = factory.create(params, ij);
      BufferingAppendable builder = LoggingAdvisingAppendable.buffering();
      LogCapturer logOutput = new LogCapturer();
      RenderResult result;
      try (SystemOutRestorer restorer = logOutput.enter()) {
        result = template.render(builder, context);
      } catch (Throwable e) {
        // TODO(lukes): the fact that we are catching an exception means we have structured
        // this subject poorly.  The subject should be responsible for asserting, not actually
        // invoking the functionality under test.
        failWithCauseAndMessage(e, "Unexpected failure for %s", actualAsString());
        result = null;
      }
      if (result.type() != RenderResult.Type.DONE) {
        fail("renders to completion", result);
      }

      String output = builder.toString();
      if (!output.equals(expectedOutput)) {
        failWithBadResults("renders as", expectedOutput, "renders as", output);
      }
      if (!expectedLogged.equals(logOutput.toString())) {
        failWithBadResults("logs", expectedLogged, "logs", logOutput.toString());
      }
      return this;
    }

    @Override
    protected String actualCustomStringRepresentation() {
      if (classData == null) {
        // hasn't been compiled yet.  just use the source text
        return actual();
      }

      return "(<\n" + actual() + "\n Compiled as: \n" + Joiner.on('\n').join(classData) + "\n>)";
    }

    private void compile() {
      if (classData == null) {
        SoyFileSetParserBuilder builder = SoyFileSetParserBuilder.forFileContents(actual());
        for (SoyFunction function : soyFunctions) {
          builder.addSoyFunction(function);
        }
        builder.addSoySourceFunctions(soySourceFunctions);
        SoyFileSetNode fileSet =
            builder
                .typeRegistry(typeRegistry)
                .options(generalOptions)
                .errorReporter(ErrorReporter.exploding())
                .enableExperimentalFeatures(ImmutableList.of("prop_vars"))
                .parse()
                .fileSet();
        // Clone the tree, there tend to be bugs in the AST clone implementations that don't show
        // up until development time when we do a lot of AST cloning, so clone here to try to flush
        // them out.
        fileSet = fileSet.copy(new CopyState());

        Map<String, Supplier<Object>> pluginInstances = new LinkedHashMap<>();
        for (FunctionNode fnNode : SoyTreeUtils.getAllNodesOfType(fileSet, FunctionNode.class)) {
          if (fnNode.getSoyFunction() instanceof SoyJavaFunction) {
            pluginInstances.put(
                fnNode.getFunctionName(),
                Suppliers.ofInstance(
                    new LegacyFunctionAdapter((SoyJavaFunction) fnNode.getSoyFunction())));
          }
        }

        // N.B. we are reproducing some of BytecodeCompiler here to make it easier to look at
        // intermediate data structures.
        TemplateRegistry registry = new TemplateRegistry(fileSet, ErrorReporter.exploding());
        CompiledTemplateRegistry compilerRegistry = new CompiledTemplateRegistry(registry);
        String templateName =
            Iterables.getOnlyElement(registry.getTemplatesOrElementsMap().keySet());
        classData =
            new TemplateCompiler(
                    compilerRegistry,
                    compilerRegistry.getTemplateInfoByTemplateName(templateName),
                    ErrorReporter.exploding(),
                    typeRegistry)
                .compile();
        checkClasses(classData);
        CompiledTemplates compiledTemplates =
            new CompiledTemplates(
                compilerRegistry.getDelegateTemplateNames(), new MemoryClassLoader(classData));
        factory = compiledTemplates.getTemplateFactory(templateName);
        defaultContext =
            defaultContextBuilder
                .withPluginInstances(pluginInstances)
                .withCompiledTemplates(compiledTemplates)
                .withMessageBundle(SoyMsgBundle.EMPTY)
                .build();
      }
    }

    private static void checkClasses(Iterable<ClassData> classData2) {
      for (ClassData d : classData2) {
        d.checkClass();
      }
    }

    /*
     * Hack to get Truth to include a given exception as the cause of the failure. It works by
     * letting us delegate to a new Subject whose value under test is the exception. Because that
     * makes the assertion "about" the exception, Truth includes it as a cause.
     */

    private void failWithCauseAndMessage(Throwable cause, String format, Object... args) {
      check().about(UnexpectedFailureSubject::new).that(cause).doFail(format, args);
    }

    private static final class UnexpectedFailureSubject
        extends Subject<UnexpectedFailureSubject, Throwable> {
      UnexpectedFailureSubject(FailureMetadata metadata, Throwable actual) {
        super(metadata, actual);
      }

      void doFail(String format, Object... args) {
        failWithoutActual(simpleFact(lenientFormat(format, args)));
      }
    }
  }

  private interface SystemOutRestorer extends AutoCloseable {
    @Override
    public void close();
  }

  private static final class LogCapturer {
    private final ByteArrayOutputStream logOutput;
    private final PrintStream stream;

    LogCapturer() {
      this.logOutput = new ByteArrayOutputStream();
      try {
        this.stream = new PrintStream(logOutput, true, StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError("StandardCharsets must be supported", e);
      }
    }

    SystemOutRestorer enter() {
      final PrintStream prevStream = System.out;
      System.setOut(stream);
      return new SystemOutRestorer() {
        @Override
        public void close() {
          System.setOut(prevStream);
        }
      };
    }

    @Override
    public String toString() {
      return new String(logOutput.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static String toTemplate(String... body) {
    StringBuilder builder = new StringBuilder();
    builder.append("{namespace ns}\n").append("{template .foo}\n");
    Joiner.on("\n").appendTo(builder, body);
    builder.append("\n{/template}\n");
    return builder.toString();
  }

  private static String toElement(String... body) {
    StringBuilder builder = new StringBuilder();
    builder.append("{namespace ns}\n").append("{element .foo}\n");
    Joiner.on("\n").appendTo(builder, body);
    builder.append("\n{/element}\n");
    return builder.toString();
  }

  static CompiledTemplates compileFile(String... fileBody) {
    String file = Joiner.on('\n').join(fileBody);
    SoyFileSetParser parser = SoyFileSetParserBuilder.forFileContents(file).build();
    return BytecodeCompiler.compile(
            parser.parse().registry(),
            false,
            ErrorReporter.exploding(),
            parser.soyFileSuppliers(),
            parser.typeRegistry())
        .get();
  }
}
