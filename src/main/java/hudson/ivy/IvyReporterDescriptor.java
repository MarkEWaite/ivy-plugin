/*
 * The MIT License
 *
 * Copyright (c) 2004-2011, Sun Microsystems, Inc., Kohsuke Kawaguchi, Timothy Bingaman
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

import hudson.model.Describable;
import hudson.model.Descriptor;
import java.util.Collection;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.jelly.JellyException;
import org.kohsuke.stapler.MetaClass;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.WebApp;
import org.kohsuke.stapler.jelly.JellyClassTearOff;

/**
 * {@link Descriptor} for {@link IvyReporter}.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class IvyReporterDescriptor extends Descriptor<IvyReporter> {
    protected IvyReporterDescriptor(Class<? extends IvyReporter> clazz) {
        super(clazz);
    }

    /**
     * Infers the type of the corresponding {@link Describable} from the outer class.
     * This version works when you follow the common convention, where a descriptor
     * is written as the static nested class of the describable class.
     *
     * @since 1.278
     */
    protected IvyReporterDescriptor() {}

    /**
     * Returns an instance used for automatic {@link IvyReporter} activation.
     *
     * <p>
     * Some {@link IvyReporter}s, such as {@code IvyArtifactArchiver}, can work
     * just with the configuration in the Ivy descriptor and don't need any
     * additional Jenkins configuration. They also don't need any explicit
     * enabling/disabling as they can activate themselves by listening to the
     * callback from the build (for example javadoc archiver can do the work in
     * response to the execution of the javadoc target.)
     *
     * <p>
     * Those {@link IvyReporter}s should return a valid instance from this
     * method. Such instance will then participate into the build and receive
     * event callbacks.
     */
    public IvyReporter newAutoInstance(IvyModule module) {
        return null;
    }

    /**
     * If {@link #hasConfigScreen() the reporter has no configuration screen},
     * this method can safely return null, which is the default implementation.
     */
    @Override
    public IvyReporter newInstance(StaplerRequest2 req, JSONObject formData) throws FormException {
        return null;
    }

    /**
     * Returns true if this descriptor has {@code config.jelly}.
     */
    public final boolean hasConfigScreen() {
        MetaClass c = WebApp.getCurrent().getMetaClass(getClass());
        try {
            JellyClassTearOff tearOff = c.loadTearOff(JellyClassTearOff.class);
            return tearOff.findScript(getConfigPage()) != null;
        } catch (JellyException e) {
            return false;
        }
    }

    /**
     * Lists all the currently registered instances of {@link IvyReporterDescriptor}.
     */
    public static Collection<IvyReporterDescriptor> all() {
        // use getDescriptorList and not getExtensionList to pick up legacy instances
        return Jenkins.get().getDescriptorList(IvyReporter.class);
    }
}
