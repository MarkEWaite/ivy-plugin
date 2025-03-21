/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Kohsuke Kawaguchi, Jorg Heymans, Peter Hayes, Red Hat, Inc., Stephen Connolly, id:cactusman, Timothy Bingaman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.ivy;

import static hudson.Util.fixEmpty;
import static hudson.model.ItemGroupMixIn.loadChildren;

import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.ivy.builder.AntIvyBuilderType;
import hudson.ivy.builder.IvyBuilderType;
import hudson.ivy.builder.NAntIvyBuilderType;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildableItemWithBuildWrappers;
import hudson.model.DependencyGraph;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.PersistentDescriptor;
import hudson.model.Queue;
import hudson.model.Queue.Task;
import hudson.model.ResourceActivity;
import hudson.model.SCMedItem;
import hudson.model.Saveable;
import hudson.model.TopLevelItem;
import hudson.model.queue.CauseOfBlockage;
import hudson.search.CollectionSearchIndex;
import hudson.search.SearchIndexBuilder;
import hudson.tasks.Ant.AntInstallation;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrappers;
import hudson.tasks.Publisher;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.util.CopyOnWriteMap;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jakarta.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.verb.POST;

/**
 * Group of {@link IvyModule}s.
 * <p>
 * This corresponds to the group of Ivy module descriptors that constitute a single
 * branch of projects.
 *
 * @author Timothy Bingaman
 */
public final class IvyModuleSet extends AbstractIvyProject<IvyModuleSet, IvyModuleSetBuild>
        implements TopLevelItem, ItemGroup<IvyModule>, SCMedItem, Saveable, BuildableItemWithBuildWrappers {
    /**
     * All {@link IvyModule}s, keyed by their {@link IvyModule#getModuleName()} module name}s.
     */
    transient /*final*/ Map<ModuleName, IvyModule> modules = new CopyOnWriteMap.Tree<>();

    /**
     * Topologically sorted list of modules. This only includes live modules,
     * since archived ones usually don't have consistent history.
     */
    @CopyOnWrite
    transient List<IvyModule> sortedActiveModules;

    private String ivyFilePattern;

    private String ivyFileExcludesPattern;

    private String targets;

    private String ivyBranch;

    private String relativePathToDescriptorFromModuleRoot;

    private String ivySettingsFile;

    private String settings;

    private String ivySettingsPropertyFiles;

    private IvyBuilderType ivyBuilderType;

    /**
     * Identifies {@link AntInstallation} to be used.
     */
    private String antName;

    /**
     * ANT_OPTS if not null.
     */
    private String antOpts;

    /**
     * Optional build script path relative to the workspace.
     * Used for the Ant '-f' option.
     */
    private String buildFile;

    /**
     * Optional properties to be passed to Ant. Follows {@link Properties} syntax.
     */
    private String antProperties;

    /**
     * If true, the build will be aggregator style, meaning all the modules are
     * executed in a single Ant invocation, as in CLI. False otherwise, meaning
     * each module is built separately and possibly in parallel.
     */
    private boolean aggregatorStyleBuild = true;

    /**
     * If true, and if aggregatorStyleBuild is false, the build will check the
     * changeset before building, and if there are changes, only those modules
     * which have changes or those modules which failed or were unstable in the
     * previous build will be built directly. Any modules depending on the
     * directly built modules will also be built.
     */
    private boolean incrementalBuild = false;

    /**
     * The name of the property used to pass the names of the changed modules
     * to the build when both incremental build and aggregated build options are
     * selected.
     */
    private String changedModulesProperty;

    /**
     * If true, do not automatically schedule a build when one of the project
     * dependencies is built.
     */
    private boolean ignoreUpstreamChanges = false;

    /**
     * If true, allow this project to trigger downstream projects based on
     * Ivy dependencies.
     */
    private Boolean allowedToTriggerDownstream = true;

    /**
     * If true properties this build will use parameters specified on the triggering build
     */
    private boolean useUpstreamParameters = false;

    @Override
    public boolean isUseUpstreamParameters() {
        return useUpstreamParameters;
    }

    public void setUseUpstreamParameters(boolean useUpstreamParameters) {
        this.useUpstreamParameters = useUpstreamParameters;
    }

    /**
     * If true, do not archive artifacts to the master.
     */
    private boolean archivingDisabled = false;

    /**
     * List of active {@link Publisher}s configured for this project.
     */
    private DescribableList<Publisher, Descriptor<Publisher>> publishers = new DescribableList<>(this);

    /**
     * List of active {@link BuildWrapper}s configured for this project.
     */
    private DescribableList<BuildWrapper, Descriptor<BuildWrapper>> buildWrappers = new DescribableList<>(this);

    public IvyModuleSet(String name) {
        this(Jenkins.get(), name);
    }

    public IvyModuleSet(ItemGroup parent, String name) {
        super(parent, name);
    }

    @Override
    public String getUrlChildPrefix() {
        // seemingly redundant "./" is used to make sure that ':' is not interpreted as the scheme identifier
        return ".";
    }

    @Override
    public Collection<IvyModule> getItems() {
        return modules.values();
    }

    @Exported
    public Collection<IvyModule> getModules() {
        return getItems();
    }

    @Override
    public IvyModule getItem(String name) {
        try {
            return modules.get(ModuleName.fromString(name));
        } catch (IllegalArgumentException iae) {
            return null;
        }
    }

    public IvyModule getModule(String name) {
        return getItem(name);
    }

    public String getSettings() {
        return settings;
    }

    @Override // to make this accessible from IvyModuleSetBuild
    protected void updateTransientActions() {
        super.updateTransientActions();
    }

    @Override
    protected List<Action> createTransientActions() {
        List<Action> r = super.createTransientActions();
        for (IvyModule module : modules.values()) {
            module.updateTransientActions();
        }
        if (publishers != null) { // this method can be loaded from within the onLoad method, where this might be null
            for (BuildStep step : publishers) {
                r.addAll(step.getProjectActions(this));
            }
        }

        if (buildWrappers != null) {
            for (BuildWrapper step : buildWrappers) {
                r.addAll(step.getProjectActions(this));
            }
        }

        return r;
    }

    @Override
    protected void addTransientActionsFromBuild(IvyModuleSetBuild build, List<Action> collection, Set<Class> added) {
        if (build == null) {
            return;
        }

        for (Action a : build.getActions()) {
            if (a instanceof IvyAggregatedReport) {
                if (added.add(a.getClass())) {
                    collection.add(((IvyAggregatedReport) a).getProjectAction(this));
                }
            }
        }

        List<IvyReporter> list = build.projectActionReporters;
        if (list == null) {
            return;
        }

        for (IvyReporter step : list) {
            if (!added.add(step.getClass())) {
                continue; // already added
            }
            Action a = step.getAggregatedProjectAction(this);
            if (a != null) {
                collection.add(a);
            }
        }
    }

    /**
     * Called by {@link IvyModule#doDoDelete(StaplerRequest2, StaplerResponse2)}.
     * Real deletion is done by the caller, and this method only adjusts the
     * data structure the parent maintains.
     */
    /*package*/ void onModuleDeleted(IvyModule module) {
        modules.remove(module.getModuleName());
    }

    /**
     * Returns true if there's any disabled module.
     */
    public boolean hasDisabledModule() {
        for (IvyModule m : modules.values()) {
            if (m.isDisabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Possibly empty list of all disabled modules (if disabled==true)
     * or all enabled modules (if disabled==false)
     */
    public List<IvyModule> getDisabledModules(boolean disabled) {
        if (!disabled && sortedActiveModules != null) {
            return sortedActiveModules;
        }

        List<IvyModule> r = new ArrayList<>();
        for (IvyModule m : modules.values()) {
            if (m.isDisabled() == disabled) {
                r.add(m);
            }
        }
        return r;
    }

    public boolean isIncrementalBuild() {
        return incrementalBuild;
    }

    public String getChangedModulesProperty() {
        return changedModulesProperty;
    }

    public boolean isAggregatorStyleBuild() {
        return aggregatorStyleBuild;
    }

    public boolean ignoreUpstreamChanges() {
        return ignoreUpstreamChanges;
    }

    public boolean isAllowedToTriggerDownstream() {
        return allowedToTriggerDownstream;
    }

    public void setAllowedToTriggerDownstream(boolean allowedToTriggerDownstream) {
        this.allowedToTriggerDownstream = allowedToTriggerDownstream;
    }

    public boolean isArchivingDisabled() {
        return archivingDisabled;
    }

    public void setIncrementalBuild(boolean incrementalBuild) {
        this.incrementalBuild = incrementalBuild;
    }

    public String getIvyFilePattern() {
        return ivyFilePattern;
    }

    public void setIvyFilePattern(String ivyFilePattern) {
        this.ivyFilePattern = ivyFilePattern;
    }

    public String getIvyFileExcludesPattern() {
        return ivyFileExcludesPattern;
    }

    public void setIvyFileExcludesPattern(String ivyFileExcludesPattern) {
        this.ivyFileExcludesPattern = ivyFileExcludesPattern;
    }

    public String getIvySettingsFile() {
        return ivySettingsFile;
    }

    public void setIvySettingsFile(String ivySettingsFile) {
        this.ivySettingsFile = ivySettingsFile;
    }

    public String getIvySettingsPropertyFiles() {
        return ivySettingsPropertyFiles;
    }

    public void setIvySettingsPropertyFiles(String ivySettingsPropertyFiles) {
        this.ivySettingsPropertyFiles = ivySettingsPropertyFiles;
    }

    public String getIvyBranch() {
        return ivyBranch;
    }

    public void setIvyBranch(String ivyBranch) {
        this.ivyBranch = ivyBranch;
    }

    public IvyBuilderType getIvyBuilderType() {
        return ivyBuilderType;
    }

    public void setAggregatorStyleBuild(boolean aggregatorStyleBuild) {
        this.aggregatorStyleBuild = aggregatorStyleBuild;
    }

    public void setIgnoreUpstreamChanges(boolean ignoreUpstreamChanges) {
        this.ignoreUpstreamChanges = ignoreUpstreamChanges;
    }

    public void setIsArchivingDisabled(boolean archivingDisabled) {
        this.archivingDisabled = archivingDisabled;
    }

    /**
     * List of active {@link Publisher}s that should be applied to all module builds.
     */
    public DescribableList<Publisher, Descriptor<Publisher>> getModulePublishers() {
        return aggregatorStyleBuild ? new DescribableList<>(this) : publishers;
    }

    /**
     * List of active {@link Publisher}s. Can be empty but never null.
     */
    public DescribableList<Publisher, Descriptor<Publisher>> getPublishers() {
        return publishers;
    }

    @Override
    public DescribableList<Publisher, Descriptor<Publisher>> getPublishersList() {
        return publishers;
    }

    @Override
    public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getBuildWrappersList() {
        return buildWrappers;
    }

    /**
     * List of active {@link BuildWrapper}s. Can be empty but never null.
     *
     * @deprecated as of 1.335
     * Use {@link #getBuildWrappersList()} to be consistent with other subtypes of {@link AbstractProject}.
     */
    @Deprecated
    public DescribableList<BuildWrapper, Descriptor<BuildWrapper>> getBuildWrappers() {
        return buildWrappers;
    }

    @Override
    public Object getDynamic(String token, StaplerRequest2 req, StaplerResponse2 rsp) {
        if (ModuleName.isValid(token)) {
            return getModule(token);
        }
        return super.getDynamic(token, req, rsp);
    }

    @Override
    public File getRootDirFor(IvyModule child) {
        return new File(getModulesDir(), child.getModuleName().toFileSystemName());
    }

    @Override
    public void onRenamed(IvyModule item, String oldName, String newName) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void onDeleted(IvyModule item) throws IOException {
        // noop
    }

    @Override
    public Collection<Job> getAllJobs() {
        Set<Job> jobs = new HashSet<>(getItems());
        jobs.add(this);
        return jobs;
    }

    @Override
    protected Class<IvyModuleSetBuild> getBuildClass() {
        return IvyModuleSetBuild.class;
    }

    @Override
    protected SearchIndexBuilder makeSearchIndex() {
        return super.makeSearchIndex()
                .add(
                        new CollectionSearchIndex<IvyModule>() { // for computers
                            @Override
                            protected IvyModule get(String key) {
                                for (IvyModule m : modules.values()) {
                                    if (m.getDisplayName().equals(key)) {
                                        return m;
                                    }
                                }
                                return null;
                            }

                            @Override
                            protected Collection<IvyModule> all() {
                                return modules.values();
                            }

                            @Override
                            protected String getName(IvyModule o) {
                                return o.getName();
                            }
                        });
    }

    @Override
    public boolean isFingerprintConfigured() {
        return true;
    }

    @Override
    public synchronized void save() throws IOException {
        super.save();

        if (!isAggregatorStyleBuild()) {
            for (IvyModule module : getModules()) {
                module.save();
            }
        }
    }

    @Override
    public void onLoad(ItemGroup<? extends Item> parent, String name) throws IOException {
        modules = Collections.emptyMap(); // needed during load
        super.onLoad(parent, name);

        modules = loadChildren(this, getModulesDir(), IvyModule::getModuleName);
        if (publishers == null) {
            publishers = new DescribableList<>(this);
        }
        publishers.setOwner(this);
        if (buildWrappers == null) {
            buildWrappers = new DescribableList<>(this);
            buildWrappers.setOwner(this);
        }

        updateTransientActions();
    }

    private File getModulesDir() {
        return new File(getRootDir(), "modules");
    }

    /**
     * To make it easy to grasp relationship among modules
     * and the module set, we'll align the build numbers of
     * all the modules.
     * <p>
     * This method is invoked from {@link Executor#run()},
     * and because of the mutual exclusion among {@link IvyModuleSetBuild}
     * and {@link IvyBuild}, we can safely touch all the modules.
     */
    @Override
    public synchronized int assignBuildNumber() throws IOException {
        // determine the next value
        updateNextBuildNumber();

        return super.assignBuildNumber();
    }

    @Override
    public void logRotate() throws IOException, InterruptedException {
        super.logRotate();
        // perform the log rotation of modules
        for (IvyModule m : modules.values()) {
            m.logRotate();
        }
    }

    /**
     * The next build of {@link IvyModuleSet} must have
     * the build number newer than any of the current module build.
     */
    /*package*/ void updateNextBuildNumber() throws IOException {
        int next = this.nextBuildNumber;
        for (IvyModule m : modules.values()) {
            next = Math.max(next, m.getNextBuildNumber());
        }

        if (this.nextBuildNumber != next) {
            this.nextBuildNumber = next;
            this.saveNextBuildNumber();
        }
    }

    @Override
    protected void buildDependencyGraph(DependencyGraph graph) {
        publishers.buildDependencyGraph(this, graph);
        buildWrappers.buildDependencyGraph(this, graph);
    }

    @Override
    protected Set<ResourceActivity> getResourceActivities() {
        final Set<ResourceActivity> activities = new HashSet<>();

        activities.addAll(super.getResourceActivities());
        activities.addAll(Util.filter(publishers, ResourceActivity.class));
        activities.addAll(Util.filter(buildWrappers, ResourceActivity.class));

        return activities;
    }

    /**
     * Because one of our own modules is currently building.
     */
    public static class BecauseOfModuleBuildInProgress extends CauseOfBlockage {
        public final IvyModule module;

        public BecauseOfModuleBuildInProgress(IvyModule module) {
            this.module = module;
        }

        @Override
        public String getShortDescription() {
            return Messages.IvyModuleSet_ModuleBuildInProgress(module.getName());
        }
    }

    @Override
    public CauseOfBlockage getCauseOfBlockage() {
        CauseOfBlockage cob = super.getCauseOfBlockage();
        if (cob != null) {
            return cob;
        }

        for (IvyModule module : modules.values()) {
            if (module.isBuilding() || module.isInQueue()) {
                return new BecauseOfModuleBuildInProgress(module);
            }
        }
        return null;
    }

    @Override
    public AbstractProject<?, ?> asProject() {
        return this;
    }

    public String getRelativePathToDescriptorFromModuleRoot() {
        return relativePathToDescriptorFromModuleRoot;
    }

    public void setRelativePathToDescriptorFromModuleRoot(String relativePathToDescriptorFromModuleRoot) {
        this.relativePathToDescriptorFromModuleRoot = relativePathToDescriptorFromModuleRoot;
    }

    /**
     * Returns the {@link IvyModule}s that are in the queue.
     */
    public List<Queue.Item> getQueueItems() {
        List<Queue.Item> r = new ArrayList<>();
        for (Queue.Item item : Jenkins.get().getQueue().getItems()) {
            Task t = item.task;
            if ((t instanceof IvyModule && ((IvyModule) t).getParent() == this) || t == this) {
                r.add(item);
            }
        }
        return r;
    }

    //
    //
    // Web methods
    //
    //

    @Override
    protected void submit(StaplerRequest2 req, StaplerResponse2 rsp)
            throws IOException, ServletException, FormException {
        super.submit(req, rsp);
        JSONObject json = req.getSubmittedForm();

        ignoreUpstreamChanges = !json.has("triggerByDependency");
        allowedToTriggerDownstream = json.has("allowedToTriggerDownstream");
        useUpstreamParameters = json.has("useUpstreamParameters");
        ivyFilePattern = Util.fixEmptyAndTrim(json.getString("ivyFilePattern"));
        ivyFileExcludesPattern = Util.fixEmptyAndTrim(json.getString("ivyFileExcludesPattern"));
        ivySettingsFile = Util.fixEmptyAndTrim(json.getString("ivySettingsFile"));
        settings = Util.fixEmptyAndTrim(json.getString("settings"));
        ivySettingsPropertyFiles = Util.fixEmptyAndTrim(json.getString("ivySettingsPropertyFiles"));
        ivyBranch = Util.fixEmptyAndTrim(json.getString("ivyBranch"));
        relativePathToDescriptorFromModuleRoot =
                Util.fixEmptyAndTrim(json.getString("relativePathToDescriptorFromModuleRoot"));
        JSONObject ivyBuilderTypeJson = json.getJSONObject("ivyBuilderType");
        try {
            ivyBuilderType = (IvyBuilderType)
                    req.bindJSON(Class.forName(ivyBuilderTypeJson.getString("stapler-class")), ivyBuilderTypeJson);
        } catch (ClassNotFoundException e) {
            throw new FormException("Error creating specified builder type.", e, "ivyBuilderType");
        }
        aggregatorStyleBuild = !req.hasParameter("perModuleBuild");
        incrementalBuild = req.hasParameter("incrementalBuild");
        if (incrementalBuild) {
            changedModulesProperty =
                    Util.fixEmptyAndTrim(json.getJSONObject("incrementalBuild").getString("changedModulesProperty"));
        }

        publishers.rebuildHetero(req, json, Publisher.all(), "publisher");
        buildWrappers.rebuild(req, json, BuildWrappers.getFor(this));

        if (!isAggregatorStyleBuild()) {
            for (IvyModule module : getModules()) {
                module.getBuildWrappersList().rebuild(req, json, BuildWrappers.getFor(module));
            }
        }
    }

    public Class<? extends AbstractProject> getModuleClass() {
        return IvyModule.class;
    }

    public AbstractTestResultAction<?> getTestResultAction() {
        IvyModuleSetBuild b = getLastBuild();
        return b != null ? b.getAction(AbstractTestResultAction.class) : null;
    }

    /**
     * Delete all disabled modules.
     */
    @POST
    public void doDoDeleteAllDisabledModules(StaplerResponse2 rsp) throws IOException, InterruptedException {
        checkPermission(DELETE);
        for (IvyModule m : getDisabledModules(true)) {
            m.delete();
        }
        rsp.sendRedirect2(".");
    }

    /**
     * Check the location of the ivy descriptor file, alternate settings file, etc - any file.
     */
    public FormValidation doCheckFileInWorkspace(@QueryParameter String value) throws IOException, ServletException {
        IvyModuleSetBuild lb = getLastBuild();
        if (lb != null) {
            FilePath ws = lb.getModuleRoot();
            if (ws != null) {
                return ws.validateRelativePath(value, true, true);
            }
        }
        return FormValidation.ok();
    }

    /**
     * Check that the provided file exists, just in case.
     */
    public FormValidation doCheckIvySettingsFile(@QueryParameter String value) throws IOException, ServletException {
        String v = fixEmpty(value);
        if ((v == null) || (v.length() == 0)) {
            // Null values are allowed.
            return FormValidation.ok();
        }

        IvyModuleSetBuild lb = getLastBuild();
        if (lb != null) {
            FilePath ws = lb.getWorkspace();
            if (ws != null) {
                if ((v.startsWith("/")) || (v.startsWith("\\")) || (v.matches("^\\w\\:\\\\.*"))) {
                    return validateAbsolutePath(ws, v);
                } else {
                    return ws.validateRelativePath(v, true, true);
                }
            }
        }
        return FormValidation.ok();
    }

    private FormValidation validateAbsolutePath(FilePath ws, String path) throws IOException {
        try {
            if (ws.child(path).exists()) {
                return FormValidation.ok();
            }
        } catch (InterruptedException ignore) {
        }
        return FormValidation.error("Error reading ivy settings file: " + path);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<Descriptor<IvyBuilderType>> getBuilderTypeDescriptors() {
        ArrayList<Descriptor<IvyBuilderType>> buildTypeDescriptors = new ArrayList<>();
        buildTypeDescriptors.add(Jenkins.get().getDescriptor(AntIvyBuilderType.class));
        if (Jenkins.get().getPlugin("nant") != null) {
            buildTypeDescriptors.add(Jenkins.get().getDescriptor(NAntIvyBuilderType.class));
        }
        return buildTypeDescriptors;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

    public static final class DescriptorImpl extends AbstractProjectDescriptor implements PersistentDescriptor {
        /**
         * Globally-defined ANT_OPTS.
         */
        private String globalAntOpts;

        public String getGlobalAntOpts() {
            return globalAntOpts;
        }

        @DataBoundSetter
        public void setGlobalAntOpts(String globalAntOpts) {
            this.globalAntOpts = Util.fixEmptyAndTrim(globalAntOpts);
            save();
        }

        public ListBoxModel doFillSettingsItems(@AncestorInPath ItemGroup context) {
            List<Config> configsInContext = ConfigFiles.getConfigsInContext(context, null);
            ListBoxModel lb = new ListBoxModel();
            lb.add("please select", "");
            for (Config config : configsInContext) {
                lb.add(config.name, config.id);
            }
            return lb;
        }

        @Override
        public String getDisplayName() {
            return Messages.IvyModuleSet_DisplayName();
        }

        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new IvyModuleSet(parent, name);
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject json) {
            req.bindJSON(this, json);
            save();

            return true;
        }
    }

    protected Object readResolve() {
        if (ivyBuilderType == null) {
            // Convert builder settings to new format
            ivyBuilderType = new AntIvyBuilderType(antName, buildFile, targets, antProperties, antOpts);
            // Wipe out old builder settings to avoid confusion
            antName = null;
            buildFile = null;
            targets = null;
            antProperties = null;
            antOpts = null;
        }
        if (allowedToTriggerDownstream == null) {
            allowedToTriggerDownstream = true;
        }
        return this;
    }
}
