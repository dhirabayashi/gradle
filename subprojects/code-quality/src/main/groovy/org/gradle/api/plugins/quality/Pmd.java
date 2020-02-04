/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.plugins.quality;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.project.IsolatedAntBuilder;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.quality.internal.PmdAction;
import org.gradle.api.plugins.quality.internal.PmdReportsImpl;
import org.gradle.api.provider.Property;
import org.gradle.api.reporting.Reporting;
import org.gradle.api.reporting.SingleFileReport;
import org.gradle.api.reporting.internal.DefaultReportContainer;
import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.VerificationTask;
import org.gradle.internal.nativeintegration.console.ConsoleDetector;
import org.gradle.internal.nativeintegration.console.ConsoleMetaData;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.util.ClosureBackedAction;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.File;
import java.util.List;

/**
 * Runs a set of static code analysis rules on Java source code files and generates a report of problems found.
 *
 * @see PmdPlugin
 * @see PmdExtension
 */
@CacheableTask
public class Pmd extends SourceTask implements VerificationTask, Reporting<PmdReports> {

    private FileCollection pmdClasspath;
    private List<String> ruleSets;
    private TargetJdk targetJdk;
    private TextResource ruleSetConfig;
    private FileCollection ruleSetFiles;
    private final PmdReports reports;
    private boolean ignoreFailures;
    private int rulePriority;
    private boolean consoleOutput;
    private FileCollection classpath;
    private Property<Boolean> incrementalAnalysis;

    public Pmd() {
        ObjectFactory objects = getObjectFactory();
        reports = objects.newInstance(PmdReportsImpl.class, this);
        this.incrementalAnalysis = objects.property(Boolean.class);
    }

    @Inject
    protected ObjectFactory getObjectFactory() {
        throw new UnsupportedOperationException();
    }

    @Inject
    public IsolatedAntBuilder getAntBuilder() {
        throw new UnsupportedOperationException();
    }

    @Inject
    public WorkerExecutor getWorkExecutor() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    public void run() {
        WorkQueue queue = getWorkExecutor().processIsolation();
        queue.submit(PmdAction.class, parameters -> {
            parameters.getPmdClasspath().from(getPmdClasspath());
            parameters.getClasspath().from(getClasspath());
            parameters.getSource().from(getSource());

            parameters.getTargetJdk().set(getTargetJdk());

            if (reports.getHtml().isEnabled()) {
                parameters.getHtmlReportFile().set(reports.getHtml().getOutputLocation());
            }
            if (reports.getXml().isEnabled()) {
                parameters.getXmlReportFile().set(reports.getXml().getOutputLocation());
            }
            SingleFileReport firstEnabled = ((DefaultReportContainer<SingleFileReport>)reports).getFirstEnabled();
            if (firstEnabled != null) {
                parameters.getPreferredReportFile().set(firstEnabled.getOutputLocation());
            }

            parameters.getRuleSets().set(getRuleSets());
            parameters.getRuleSetFiles().from(getRuleSetFiles());
            if (getRuleSetConfig() != null) {
                parameters.getRuleSetConfig().set(getRuleSetConfig().asFile());
            }
            parameters.getRulePriority().set(getRulePriority());

            parameters.getConsoleOutput().set(isConsoleOutput());
            parameters.getStdOutIsAttachedToTerminal().set(isConsoleOutput() && stdOutIsAttachedToTerminal());

            parameters.getIgnoreFailures().set(getIgnoreFailures());

            parameters.getIncrementalAnalysis().set(getIncrementalAnalysis());
            parameters.getIncrementalCacheFile().set(getIncrementalCacheFile());
        });
    }

    public boolean stdOutIsAttachedToTerminal() {
        try {
            ConsoleDetector consoleDetector = NativeServices.getInstance().get(ConsoleDetector.class);
            ConsoleMetaData consoleMetaData = consoleDetector.getConsole();
            return consoleMetaData != null && consoleMetaData.isStdOut();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Configures the reports to be generated by this task.
     */
    @Override
    public PmdReports reports(@DelegatesTo(value = PmdReports.class, strategy = Closure.DELEGATE_FIRST) Closure closure) {
        return reports(new ClosureBackedAction<PmdReports>(closure));
    }

    /**
     * Configures the reports to be generated by this task.
     *
     * @since 3.0
     */
    @Override
    public PmdReports reports(Action<? super PmdReports> configureAction) {
        configureAction.execute(reports);
        return reports;
    }

    /**
     * Validates the value is a valid PMD RulePriority (1-5)
     *
     * @param value rule priority threshold
     */
    public static void validate(int value) {
        if (value > 5 || value < 1) {
            throw new InvalidUserDataException(String.format("Invalid rulePriority '%d'.  Valid range 1 (highest) to 5 (lowest).", value));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileTree getSource() {
        return super.getSource();
    }

    /**
     * The class path containing the PMD library to be used.
     */
    @Classpath
    public FileCollection getPmdClasspath() {
        return pmdClasspath;
    }

    /**
     * The class path containing the PMD library to be used.
     */
    public void setPmdClasspath(FileCollection pmdClasspath) {
        this.pmdClasspath = pmdClasspath;
    }

    /**
     * The built-in rule sets to be used. See the <a href="https://pmd.github.io/pmd-6.20.0/pmd_rules_java.html">official list</a> of built-in rule sets.
     *
     * <pre>
     *     ruleSets = ["basic", "braces"]
     * </pre>
     */
    @Input
    public List<String> getRuleSets() {
        return ruleSets;
    }

    /**
     * The built-in rule sets to be used. See the <a href="https://pmd.github.io/pmd-6.20.0/pmd_rules_java.html">official list</a> of built-in rule sets.
     *
     * <pre>
     *     ruleSets = ["basic", "braces"]
     * </pre>
     */
    public void setRuleSets(List<String> ruleSets) {
        this.ruleSets = ruleSets;
    }

    /**
     * The target JDK to use with PMD.
     */
    @Input
    public TargetJdk getTargetJdk() {
        return targetJdk;
    }

    /**
     * The target JDK to use with PMD.
     */
    public void setTargetJdk(TargetJdk targetJdk) {
        this.targetJdk = targetJdk;
    }

    /**
     * The custom rule set to be used (if any). Replaces {@code ruleSetFiles}, except that it does not currently support multiple rule sets.
     *
     * See the <a href="https://pmd.github.io/pmd-6.20.0/pmd_userdocs_making_rulesets.html">official documentation</a> for how to author a rule set.
     *
     * <pre>
     *     ruleSetConfig = resources.text.fromFile(resources.file("config/pmd/myRuleSets.xml"))
     * </pre>
     *
     * @since 2.2
     */
    @Nullable
    @Optional
    @Nested
    public TextResource getRuleSetConfig() {
        return ruleSetConfig;
    }

    /**
     * The custom rule set to be used (if any). Replaces {@code ruleSetFiles}, except that it does not currently support multiple rule sets.
     *
     * See the <a href="https://pmd.github.io/pmd-6.20.0/pmd_userdocs_making_rulesets.html">official documentation</a> for how to author a rule set.
     *
     * <pre>
     *     ruleSetConfig = resources.text.fromFile(resources.file("config/pmd/myRuleSets.xml"))
     * </pre>
     *
     * @since 2.2
     */
    public void setRuleSetConfig(@Nullable TextResource ruleSetConfig) {
        this.ruleSetConfig = ruleSetConfig;
    }

    /**
     * The custom rule set files to be used. See the <a href="https://pmd.github.io/pmd-6.20.0/pmd_userdocs_making_rulesets.html">official documentation</a> for how to author a rule set file.
     * If you want to only use custom rule sets, you must clear {@code ruleSets}.
     *
     * <pre>
     *     ruleSetFiles = files("config/pmd/myRuleSet.xml")
     * </pre>
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public FileCollection getRuleSetFiles() {
        return ruleSetFiles;
    }

    /**
     * The custom rule set files to be used. See the <a href="https://pmd.github.io/pmd-6.20.0/pmd_userdocs_making_rulesets.html">official documentation</a> for how to author a rule set file.
     * This adds to the default rule sets defined by {@link #getRuleSets()}.
     *
     * <pre>
     *     ruleSetFiles = files("config/pmd/myRuleSets.xml")
     * </pre>
     */
    public void setRuleSetFiles(FileCollection ruleSetFiles) {
        this.ruleSetFiles = ruleSetFiles;
    }

    /**
     * The reports to be generated by this task.
     */
    @Override
    @Nested
    public final PmdReports getReports() {
        return reports;
    }

    /**
     * Whether or not to allow the build to continue if there are warnings.
     *
     * <pre>
     *     ignoreFailures = true
     * </pre>
     */
    @Override
    public boolean getIgnoreFailures() {
        return ignoreFailures;
    }


    /**
     * Whether or not to allow the build to continue if there are warnings.
     *
     * <pre>
     *     ignoreFailures = true
     * </pre>
     */
    @Override
    public void setIgnoreFailures(boolean ignoreFailures) {
        this.ignoreFailures = ignoreFailures;
    }

    /**
     * Specifies the rule priority threshold.
     *
     * @since 2.8
     * @see PmdExtension#getRulePriority()
     */
    @Input
    public int getRulePriority() {
        return rulePriority;
    }

    /**
     * Sets the rule priority threshold.
     *
     * @since 2.8
     */
    public void setRulePriority(int intValue) {
        validate(intValue);
        rulePriority = intValue;

    }

    /**
     * Whether or not to write PMD results to {@code System.out}.
     *
     * @since 2.1
     */
    @Input
    public boolean isConsoleOutput() {
        return consoleOutput;
    }

    /**
     * Whether or not to write PMD results to {@code System.out}.
     *
     * @since 2.1
     */
    public void setConsoleOutput(boolean consoleOutput) {
        this.consoleOutput = consoleOutput;
    }

    /**
     * Compile class path for the classes to be analyzed.
     *
     * The classes on this class path are used during analysis but aren't analyzed themselves.
     *
     * This is only well supported for PMD 5.2.1 or better.
     *
     * @since 2.8
     */
    @Nullable
    @Optional
    @Classpath
    public FileCollection getClasspath() {
        return classpath;
    }

    /**
     * Compile class path for the classes to be analyzed.
     *
     * The classes on this class path are used during analysis but aren't analyzed themselves.
     *
     * This is only well supported for PMD 5.2.1 or better.
     *
     * @since 2.8
     */
    public void setClasspath(@Nullable FileCollection classpath) {
        this.classpath = classpath;
    }

    /**
     * Controls whether to use incremental analysis or not.
     *
     * This is only supported for PMD 6.0.0 or better. See <a href="https://pmd.github.io/pmd-6.20.0/pmd_userdocs_incremental_analysis.html"></a> for more details.
     *
     * @since 5.6
     */
    @Incubating
    @Internal
    public Property<Boolean> getIncrementalAnalysis() {
        return incrementalAnalysis;
    }

    /**
     * Path to the incremental cache file, if incremental analysis is used.
     *
     * @since 5.6
     */
    @LocalState
    @Incubating
    public File getIncrementalCacheFile() {
        return new File(getTemporaryDir(), "incremental.cache");
    }
}
