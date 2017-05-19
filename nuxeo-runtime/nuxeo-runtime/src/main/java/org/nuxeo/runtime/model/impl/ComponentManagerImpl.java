/*
 * (C) Copyright 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
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
 *
 * Contributors:
 *     Bogdan Stefanescu
 *     Florent Guillaume
 */

package org.nuxeo.runtime.model.impl;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.collections.ListenerList;
import org.nuxeo.runtime.ComponentEvent;
import org.nuxeo.runtime.ComponentListener;
import org.nuxeo.runtime.RuntimeService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.ComponentManager;
import org.nuxeo.runtime.model.ComponentName;
import org.nuxeo.runtime.model.Extension;
import org.nuxeo.runtime.model.RegistrationInfo;

/**
 * @author Bogdan Stefanescu
 * @author Florent Guillaume
 */
public class ComponentManagerImpl implements ComponentManager {

    private static final Log log = LogFactory.getLog(ComponentManagerImpl.class);
    private static final Log infoLog = LogFactory.getLog(ComponentManager.class);

    // must use an ordered Set to avoid loosing the order of the pending
    // extensions
    protected final ConcurrentMap<ComponentName, Set<Extension>> pendingExtensions;

    private ListenerList compListeners;

    /**
     * Manager listeners. Listen too events like start stop restart etc.
     */
    private MyListeners listeners;

    private final ConcurrentMap<String, RegistrationInfoImpl> services;

    protected volatile Set<String> blacklist;

    /**
     *  the list of started components (sorted according to the start order).
     *  this list is null if the components were not yet started or were stopped
     */
    protected volatile List<RegistrationInfoImpl> started;
    /**
     * the list of standby components (sorted according to the start order)
     * this list is null if component were not yet started or not yet put in standby
     * When putting components in standby all started components are stopped and the {@link #started} list is assigned to {@link #standby} list then the {@link #started} field is nullified.
     * When resuming standby components the started list is restored from the standby list and the standby field is nullified
     */
    protected volatile List<RegistrationInfoImpl> standby;

    /**
     * A list of registrations that were deployed while the manager was started.
     */
    protected volatile List<RegistrationInfoImpl> stash;

    protected volatile ComponentRegistry reg;

    protected volatile ComponentRegistry snapshot;

    protected volatile boolean isFlushingStash = false;
    protected volatile boolean changed = false;

    public ComponentManagerImpl(RuntimeService runtime) {
        reg = new ComponentRegistry();
        pendingExtensions = new ConcurrentHashMap<ComponentName, Set<Extension>>();
        compListeners = new ListenerList();
        listeners = new MyListeners();
        services = new ConcurrentHashMap<String, RegistrationInfoImpl>();
        blacklist = new HashSet<String>();
        stash = new ArrayList<RegistrationInfoImpl>();
    }

    public final ComponentRegistry getRegistry() {
    	return reg;
    }

    @Override
    public Collection<RegistrationInfo> getRegistrations() {
        return reg.getComponents();
    }

    @Override
    public Collection<ComponentName> getResolvedRegistrations() {
    	return reg.getResolvedNames();
    }

    @Override
    public synchronized Map<ComponentName, Set<ComponentName>> getPendingRegistrations() {
        Map<ComponentName, Set<ComponentName>> pending = new HashMap<>();
        for (Map.Entry<ComponentName, Set<ComponentName>> p : reg.getPendingComponents().entrySet()) {
            pending.put(p.getKey(), new LinkedHashSet<>(p.getValue()));
        }
        return pending;
    }

    @Override
    public synchronized Map<ComponentName, Set<Extension>> getMissingRegistrations() {
        Map<ComponentName, Set<Extension>> missing = new HashMap<>();
        // also add pending extensions, not resolved because of missing target extension point
        for (Set<Extension> p : pendingExtensions.values()) {
            for (Extension e : p) {
                missing.computeIfAbsent(e.getComponent().getName(), k -> new LinkedHashSet<>()).add(e);
            }
        }
        return missing;
    }

    /**
     * Get the needed component names. The returned set is not a copy
     * @return
     */
    public Set<ComponentName> getNeededRegistrations() {
        return pendingExtensions.keySet();
    }

    /**
     * Get the pending extensions. The returned set is not a copy
     * @param name
     * @return
     */
    public Set<Extension> getPendingExtensions(ComponentName name) {
        return pendingExtensions.get(name);
    }

    @Override
    public RegistrationInfo getRegistrationInfo(ComponentName name) {
        return reg.getComponent(name);
    }

    @Override
    public boolean isRegistered(ComponentName name) {
        return reg.contains(name);
    }

    @Override
    public int size() {
        return reg.size();
    }

    @Override
    public ComponentInstance getComponent(ComponentName name) {
        RegistrationInfo ri = reg.getComponent(name);
        return ri != null ? ri.getComponent() : null;
    }

    @Override
    public synchronized void shutdown() {
        //ShutdownTask.shutdown(this);
        stop();
        compListeners = null;
        reg.destroy();
        reg = null;
        snapshot = null;
    }

    @Override
    public Set<String> getBlacklist() {
        return Collections.unmodifiableSet(blacklist);
    }

    @Override
    public void setBlacklist(Set<String> blacklist) {
        this.blacklist = blacklist;
    }

    @Override
    public synchronized void register(RegistrationInfo regInfo) {
        RegistrationInfoImpl ri = (RegistrationInfoImpl) regInfo;
        ComponentName name = ri.getName();
        if (blacklist.contains(name.getName())) {
            log.warn("Component " + name.getName() + " was blacklisted. Ignoring.");
            return;
        }
        if (reg.contains(name)) {
            if (name.getName().startsWith("org.nuxeo.runtime.")) {
                // XXX we hide the fact that nuxeo-runtime bundles are
                // registered twice
                // TODO fix the root cause and remove this
                return;
            }
            handleError("Duplicate component name: " + name, null);
            return;
        }
        for (ComponentName n : ri.getAliases()) {
            if (reg.contains(n)) {
                handleError("Duplicate component name: " + n + " (alias for " + name + ")", null);
                return;
            }
        }

        if (shouldStash()) { // stash the registration
        	// should stash before calling ri.attach.
        	stash.add(ri);
        	return;
        }

        if (hasSnapshot()) {
            // we are modifying the registry after the snapshot was created
            changed = true;
        }

        ri.attach(this);

        try {
            log.info("Registering component: " + name);
            if (!reg.addComponent(ri)) {
                log.info("Registration delayed for component: " + name + ". Waiting for: "
                        + reg.getMissingDependencies(ri.getName()));
            }
        } catch (RuntimeException e) {
            // don't raise this exception,
            // we want to isolate component errors from other components
            handleError("Failed to register component: " + name + " (" + e.toString() + ')', e);
            return;
        }
    }

    @Override
    public synchronized void unregister(RegistrationInfo regInfo) {
        unregister(regInfo.getName());
    }

    @Override
    public synchronized void unregister(ComponentName name) {
        try {
            log.info("Unregistering component: " + name);
            reg.removeComponent(name);
        } catch (RuntimeException e) {
            log.error("Failed to unregister component: " + name, e);
        }
    }

    @Override
    public synchronized boolean unregisterByLocation(String sourceId) {
    	ComponentName name = reg.deployedFiles.remove(sourceId);
    	if (name != null) {
    		unregister(name);
    		return true;
    	} else {
    		return false;
    	}
    }

    @Override
    public boolean hasComponentFromLocation(String sourceId) {
    	return reg.deployedFiles.containsKey(sourceId);
    }

    @Override
    public void addComponentListener(ComponentListener listener) {
        compListeners.add(listener);
    }

    @Override
    public void removeComponentListener(ComponentListener listener) {
        compListeners.remove(listener);
    }

    @Override
    public void addListener(ComponentManager.Listener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(ComponentManager.Listener listener) {
        listeners.remove(listener);
    }

    @Override
    public ComponentInstance getComponentProvidingService(Class<?> serviceClass) {
        RegistrationInfoImpl ri = services.get(serviceClass.getName());
        if (ri == null) {
            return null;
        }
        ComponentInstance ci = ri.getComponent();
        if (ci == null) {
            log.debug("The component exposing the service " + serviceClass + " is not resolved or not started");
        }
        return ci;
    }

    @Override
    public <T> T getService(Class<T> serviceClass) {
        ComponentInstance comp = getComponentProvidingService(serviceClass);
        return comp != null ? comp.getAdapter(serviceClass) : null;
    }

    @Override
    public Collection<ComponentName> getActivatingRegistrations() {
        return getRegistrations(RegistrationInfo.ACTIVATING);
    }

    @Override
    public Collection<ComponentName> getStartFailureRegistrations() {
        return getRegistrations(RegistrationInfo.START_FAILURE);
    }

    protected Collection<ComponentName> getRegistrations(int state) {
        RegistrationInfo[] comps = reg.getComponentsArray();
        Collection<ComponentName> ret = new ArrayList<ComponentName>();
        for (RegistrationInfo ri : comps) {
            if (ri.getState() == state) {
                ret.add(ri.getName());
            }
        }
        return ret;
    }

    void sendEvent(ComponentEvent event) {
        log.debug("Dispatching event: " + event);
        Object[] listeners = this.compListeners.getListeners();
        for (Object listener : listeners) {
            ((ComponentListener) listener).handleEvent(event);
        }
    }

    public synchronized void registerExtension(Extension extension) {
        ComponentName name = extension.getTargetComponent();
        RegistrationInfoImpl ri = reg.getComponent(name);
        if (ri != null && ri.component != null) {
            if (log.isDebugEnabled()) {
                log.debug("Register contributed extension: " + extension);
            }
            loadContributions(ri, extension);
            ri.component.registerExtension(extension);
            sendEvent(new ComponentEvent(ComponentEvent.EXTENSION_REGISTERED,
                    ((ComponentInstanceImpl) extension.getComponent()).ri, extension));
        } else { // put the extension in the pending queue
            if (log.isDebugEnabled()) {
                log.debug("Enqueue contributed extension to pending queue: " + extension);
            }
            Set<Extension> extensions = pendingExtensions.get(name);
            if (extensions == null) {
                extensions = new LinkedHashSet<Extension>(); // must keep order
                                                             // in which
                                                             // extensions are
                                                             // contributed
                pendingExtensions.put(name, extensions);
            }
            extensions.add(extension);
            sendEvent(new ComponentEvent(ComponentEvent.EXTENSION_PENDING,
                    ((ComponentInstanceImpl) extension.getComponent()).ri, extension));
        }
    }

    public synchronized void unregisterExtension(Extension extension) {
        // TODO check if framework is shutting down and in that case do nothing
        if (log.isDebugEnabled()) {
            log.debug("Unregister contributed extension: " + extension);
        }
        ComponentName name = extension.getTargetComponent();
        RegistrationInfo ri = reg.getComponent(name);
        if (ri != null) {
            ComponentInstance co = ri.getComponent();
            if (co != null) {
                co.unregisterExtension(extension);
            }
        } else { // maybe it's pending
            Set<Extension> extensions = pendingExtensions.get(name);
            if (extensions != null) {
                // FIXME: extensions is a set of Extensions, not ComponentNames.
                extensions.remove(name);
                if (extensions.isEmpty()) {
                    pendingExtensions.remove(name);
                }
            }
        }
        sendEvent(new ComponentEvent(ComponentEvent.EXTENSION_UNREGISTERED,
                ((ComponentInstanceImpl) extension.getComponent()).ri, extension));
    }

    public static void loadContributions(RegistrationInfoImpl ri, Extension xt) {
        ExtensionPointImpl xp = ri.getExtensionPoint(xt.getExtensionPoint());
        if (xp != null && xp.contributions != null) {
            try {
                Object[] contribs = xp.loadContributions(ri, xt);
                xt.setContributions(contribs);
            } catch (RuntimeException e) {
                handleError("Failed to load contributions for component " + xt.getComponent().getName(), e);
            }
        }
    }

    public synchronized void registerServices(RegistrationInfoImpl ri) {
        if (ri.serviceDescriptor == null) {
            return;
        }
        for (String service : ri.serviceDescriptor.services) {
            log.info("Registering service: " + service);
            services.put(service, ri);
            // TODO: send notifications
        }
    }

    public synchronized void unregisterServices(RegistrationInfoImpl ri) {
        if (ri.serviceDescriptor == null) {
            return;
        }
        for (String service : ri.serviceDescriptor.services) {
            services.remove(service);
            // TODO: send notifications
        }
    }

    @Override
    public String[] getServices() {
        return services.keySet().toArray(new String[services.size()]);
    }

    protected static void handleError(String message, Exception e) {
        log.error(message, e);
        Framework.getRuntime().getWarnings().add(message);
    }

    /**
     * Activate all the resolved components and return the list of activated components in the activation order
     * @return the list of the activated components in the activation order
     */
    protected List<RegistrationInfoImpl> activateComponents() {
        listeners.beforeActivation();
        // make sure we start with a clean pending registry
        pendingExtensions.clear();

        List<RegistrationInfoImpl> ris = new ArrayList<RegistrationInfoImpl>();
        // first activate resolved components
        for (RegistrationInfoImpl ri : reg.resolved.values()) {
            // TODO catch and handle errors
            ri.activate();
            ris.add(ri);
        }
        listeners.afterActivation();
        return ris;
    }

    /**
     * Deactivate all active components in the reverse resolve order
     */
    protected void deactivateComponents() {
        listeners.beforeDeactivation();
        RegistrationInfoImpl[] reverseResolved = reg.resolved.values().toArray(new RegistrationInfoImpl[reg.resolved.size()]);
        for (int i=reverseResolved.length-1;i>=0;i--) {
            RegistrationInfoImpl ri = reverseResolved[i];
            if (ri.isActivated()) {
                ri.deactivate(false);
            }
        }
        // make sure the pending extension map is empty (since we didn't unregistered extensions by calling ri.deactivate(false)
        pendingExtensions.clear();
        listeners.afterDeactivation();
    }

    /**
     * Start all given components
     * @param ris
     */
    protected void startComponents(List<RegistrationInfoImpl> ris, boolean isResume) {
        listeners.beforeStart(isResume);
        for (RegistrationInfoImpl ri : ris) {
            ri.start();
        }
        this.started = ris;
        listeners.afterStart(isResume);
    }

    /**
     * Stop all started components. Stopping components is done in reverse start order
     */
    protected void stopComponents(boolean isStandby) {
        try {
            doStopComppnents(isStandby);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while stopping components", e);
        }
    }

    private void doStopComppnents(boolean isStandby) throws InterruptedException {
        listeners.beforeStop(isStandby);
        List<RegistrationInfoImpl> list = this.started;
        for (int i=list.size()-1;i>=0;i--) {
            RegistrationInfoImpl ri = list.get(i);
            if (ri.isStarted()) {
                ri.stop();
            }
        }
        listeners.afterStop(isStandby);
    }

    @Override
    public synchronized boolean start() {
    	if (this.started != null) {
    		return false;
    	}

    	double tm0 = System.currentTimeMillis();
    	infoLog.info("Starting Nuxeo Components");

    	List<RegistrationInfoImpl> ris = activateComponents();

        // TODO we sort using the old start order sorter (see OSGiRuntimeService.RIApplicationStartedComparator)
        Collections.sort(ris, new RIApplicationStartedComparator());

    	// then start activated components
    	startComponents(ris, false);

    	double tm1 = System.currentTimeMillis();
    	infoLog.info("Nuxeo Components Started. Took: "+new DecimalFormat("#.00").format((tm1-tm0)/1000)+"s");

    	return true;
    }

    @Override
    public synchronized boolean stop() {
    	if (this.started == null) {
    		return false;
    	}

        double tm0 = System.currentTimeMillis();
    	infoLog.info("Stopping Nuxeo Components");

    	try {
    	    stopComponents(false);
            // now deactivate all active components
            deactivateComponents();
        } finally {
            this.started = null;
        }

    	double tm1 = System.currentTimeMillis();
    	infoLog.info("Nuxeo Components Stopped. Took: "+new DecimalFormat("#.00").format((tm1-tm0)/1000)+"s");

    	return true;
    }

    @Override
    public void stop(int timeoutInSeconds) {
        try {
            runWihtinTimeout(timeoutInSeconds, TimeUnit.SECONDS, "Timed out on stop, blocking", this::stop);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while stopping components", e);
        }
    }

    @Override
    public synchronized void standby() {
        if (this.started != null) {
            try {
                stopComponents(true);
            } finally {
                this.standby = this.started;
                this.started = null;
            }
        }
    }

    @Override
    public void standby(int timeoutInSeconds) {
        try {
            runWihtinTimeout(timeoutInSeconds, TimeUnit.SECONDS, "Timed out on standby, blocking", this::standby);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while stopping components", e);
        }
    }

    @Override
    public synchronized void resume() {
        if (this.standby != null) {
            try {
                startComponents(this.standby, true);
            } finally {
                this.started = this.standby;
                this.standby = null;
            }
        }
    }

    @Override
    public boolean isStarted() {
    	return this.started != null;
    }

    @Override
    public boolean isStandby() {
        return this.standby != null;
    }

    @Override
    public boolean isRunning() {
        return this.started == null && this.standby == null;
    }

    @Override
    public boolean hasSnapshot() {
    	return this.snapshot != null;
    }

    @Override
    public boolean hasChanged() {
    	return this.changed;
    }

    @Override
    public synchronized void snapshot() {
    	this.snapshot = new ComponentRegistry(reg);
    }

    @Override
    public boolean isStashEmpty() {
    	return stash.isEmpty();
    }

    @Override
    public synchronized void restart(boolean reset) {
    	if (reset) {
    		this.reset();
    	} else {
    		this.stop();
    	}
    	this.start();
    }

    @Override
    public synchronized boolean reset() {
    	boolean r = this.stop();
    	restoreSnapshot();
    	return r;
    }

    @Override
    public synchronized boolean refresh() {
        return refresh(false);
    }

    @Override
	public synchronized boolean refresh(boolean reset) {
    	if (this.stash.isEmpty()) {
    		return false;
    	}
    	boolean requireStart;
    	if (reset) {
    		requireStart = reset();
    	} else {
    		requireStart = stop();
    	}
    	List<RegistrationInfoImpl> currentStash = this.stash;
    	this.stash = new ArrayList<RegistrationInfoImpl>();
    	applyStash(currentStash);
    	if (requireStart) {
    		start();
    	}
    	return true;
    }

    protected synchronized void restoreSnapshot() {
    	if (changed && snapshot != null) {
    	    log.info("Restoring components snapshot");
    		this.reg = new ComponentRegistry(snapshot);
    		changed = false;
    	}
    }

    /**
     * Tests whether new registrations should be stashed at registration time.
     * If the component manager was started then new components should be stashed otherwise they can be registered.
     * When in standby mode components are not stashed
     *
     * TODO: current implementation is stashing after the start completion. Should we also stashing while start is in progress?
     * @return
     */
    protected boolean shouldStash() {
        // isFlushingStash is not really needed since we never apply the stash while the component manager is started
        //return hasSnapshot() && !isFlushingStash;
        return this.started != null && !isFlushingStash;
    }

    protected synchronized void applyStash(List<RegistrationInfoImpl> stash) {
        log.info("Applying stashed components");
    	isFlushingStash = true;
    	try {
        	for (RegistrationInfoImpl ri : stash) {
        		register(ri);
        	}
    	} finally {
    		isFlushingStash = false;
    		//changed = true;
    	}
    }

    /**
     * Apply the stash without restarting components.
     * This operation is unsafe since it may contribute to component that are already started.
     * This may be used to speed up tests or for emulating the old hot reload mechanism
     * TODO Should we expose this method in the API?
     */
    public void unstash() {
        if (!this.stash.isEmpty()) {
            List<RegistrationInfoImpl> currentStash = this.stash;
            this.stash = new ArrayList<RegistrationInfoImpl>();
            applyStash(currentStash);
        }
    }

    /**
     * TODO we use for now the same sorter as OSGIRuntimeService - should be improved later.
     */
    protected static class RIApplicationStartedComparator implements Comparator<RegistrationInfo> {
        @Override
        public int compare(RegistrationInfo r1, RegistrationInfo r2) {
            int cmp = Integer.compare(r1.getApplicationStartedOrder(), r2.getApplicationStartedOrder());
            if (cmp == 0) {
                // fallback on name order, to be deterministic
                cmp = r1.getName().getName().compareTo(r2.getName().getName());
            }
            return cmp;
        }
    }


    protected class MyListeners {

        protected ListenerList listeners = new ListenerList();

        public void add(ComponentManager.Listener listener) {
            listeners.add(listener);
        }

        public void remove(ComponentManager.Listener listener) {
            listeners.remove(listener);
        }

        public void beforeActivation() {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).beforeActivation(ComponentManagerImpl.this);
            }
        }

        public void afterActivation() {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).afterActivation(ComponentManagerImpl.this);
            }
        }

        public void beforeDeactivation() {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).beforeDeactivation(ComponentManagerImpl.this);
            }
        }

        public void afterDeactivation() {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).afterDeactivation(ComponentManagerImpl.this);
            }
        }

        public void beforeStart(boolean isResume) {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).beforeStart(ComponentManagerImpl.this, isResume);
            }
        }

        public void afterStart(boolean isResume) {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).afterStart(ComponentManagerImpl.this, isResume);
            }
        }

        public void beforeStop(boolean isStandby) {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).beforeStop(ComponentManagerImpl.this, isStandby);
            }
        }

        public void afterStop(boolean isStandby) {
            for (Object listener : listeners.getListeners()) {
                ((ComponentManager.Listener)listener).afterStop(ComponentManagerImpl.this, isStandby);
            }
        }

    }


    /**
     * log a warning message if the timeout is reached while executing the given runnable
     * @param timeout
     * @param unit
     * @param runnable
     * @throws InterruptedException
     */
    static void runWihtinTimeout(long timeout, TimeUnit unit, String warn, Runnable runnable) throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                runnable.run();
            });
            executor.shutdown();
            try {
                try {
                    future.get(timeout, TimeUnit.MILLISECONDS);
                } catch (TimeoutException cause) {
                    log.warn(warn);
                    future.get();
                }
            } catch (ExecutionException cause) {
                throw new RuntimeException("Errors caught while stopping components, giving up", cause);
            }
        } finally {
            executor.shutdownNow();
        }
    }

}
