/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.entity.basic;

import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.config.BrooklynProperties;
import brooklyn.entity.Application;
import brooklyn.entity.Entity;
import brooklyn.entity.basic.ServiceStateLogic.ServiceProblemsLogic;
import brooklyn.entity.trait.StartableMethods;
import brooklyn.location.Location;
import brooklyn.management.ManagementContext;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.exceptions.RuntimeInterruptedException;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.time.Time;

/**
 * Users can extend this to define the entities in their application, and the relationships between
 * those entities. Users should override the {@link #init()} method, and in there should create 
 * their entities.
 */
public abstract class AbstractApplication extends AbstractEntity implements StartableApplication {
    public static final Logger log = LoggerFactory.getLogger(AbstractApplication.class);
    
    @SetFromFlag("mgmt")
    private volatile ManagementContext mgmt;
    
    private boolean deployed = false;

    BrooklynProperties brooklynProperties = null;

    private volatile Application application;
    
    public AbstractApplication() {
    }

    public void init() { 
        super.init();
        initApp();
    }
    
    protected void initApp() {}
    
    /**
     * 
     * @deprecated since 0.6; use EntitySpec so no-arg constructor
     */
    @Deprecated
    public AbstractApplication(Map properties) {
        super(properties);
    }

    /** 
     * Constructor for when application is nested inside another application
     * 
     * @deprecated Nesting applications is not currently supported
     */
    @Deprecated
    public AbstractApplication(Map properties, Entity parent) {
        super(properties, parent);
    }

    @Override
    public Application getApplication() {
        if (application!=null) {
            if (application.getId().equals(getId()))
                return (Application) getProxyIfAvailable();
            return application;
        }
        if (getParent()==null) return (Application)getProxyIfAvailable();
        return getParent().getApplication();
    }
    
    @Override
    protected synchronized void setApplication(Application app) {
        if (app.getId().equals(getId())) {
            application = getProxy()!=null ? (Application)getProxy() : app;
        } else {
            application = app;

            // Alex, Mar 2013: added some checks; 
            // i *think* these conditions should not happen, 
            // and so should throw but don't want to break things (yet)
            if (getParent()==null) {
                log.warn("Setting application of "+this+" to "+app+", but "+this+" is not parented");
            } else if (getParent().getApplicationId().equals(app.getParent())) {
                log.warn("Setting application of "+this+" to "+app+", but parent "+getParent()+" has different app "+getParent().getApplication());
            }
        }
        super.setApplication(app);
    }
    
    @Override
    public AbstractApplication setParent(Entity parent) {
        super.setParent(parent);
        return this;
    }
    
    /** as {@link AbstractEntity#initEnrichers()} but also adding default service not-up and problem indicators from children */
    @Override
    protected void initEnrichers() {
        super.initEnrichers();
        
        // default app logic; easily overridable by adding a different enricher with the same tag
        ServiceStateLogic.newEnricherFromChildren().checkChildrenAndMembers().addTo(this);
        ServiceStateLogic.ServiceNotUpLogic.updateNotUpIndicator(this, Attributes.SERVICE_STATE_ACTUAL, "Application created but not yet started, at "+Time.makeDateString());
    }
    
    /**
     * Default start will start all Startable children (child.start(Collection<? extends Location>)),
     * calling preStart(locations) first and postStart(locations) afterwards.
     */
    @Override
    public void start(Collection<? extends Location> locations) {
        this.addLocations(locations);
        Collection<? extends Location> locationsToUse = getLocations();
        ServiceProblemsLogic.clearProblemsIndicator(this, START);
        ServiceStateLogic.setExpectedState(this, Lifecycle.STARTING);
        ServiceStateLogic.ServiceNotUpLogic.updateNotUpIndicator(this, Attributes.SERVICE_STATE_ACTUAL, "Application starting");
        recordApplicationEvent(Lifecycle.STARTING);
        try {
            preStart(locationsToUse);
            // if there are other items which should block service_up, they should be done in preStart
            ServiceStateLogic.ServiceNotUpLogic.clearNotUpIndicator(this, Attributes.SERVICE_STATE_ACTUAL);
            
            doStart(locationsToUse);
            postStart(locationsToUse);
        } catch (Exception e) {
            // TODO should probably remember these problems then clear?  if so, do it here ... or on all effectors?
//            ServiceProblemsLogic.updateProblemsIndicator(this, START, e);
            
            recordApplicationEvent(Lifecycle.ON_FIRE);
            // no need to log here; the effector invocation should do that
            throw Exceptions.propagate(e);
        } finally {
            ServiceStateLogic.setExpectedState(this, Lifecycle.RUNNING);
        }
        
        deployed = true;
        recordApplicationEvent(Lifecycle.RUNNING);

        logApplicationLifecycle("Started");
    }

    protected void logApplicationLifecycle(String message) {
        log.info(message+" application " + this);
    }
    
    protected void doStart(Collection<? extends Location> locations) {
        StartableMethods.start(this, locations);        
    }

    /**
     * Default is no-op. Subclasses can override.
     * */
    public void preStart(Collection<? extends Location> locations) {
        //no-op
    }

    /**
     * Default is no-op. Subclasses can override.
     * */
    public void postStart(Collection<? extends Location> locations) {
        //no-op
    }

    /**
     * Default stop will stop all Startable children
     */
    @Override
    public void stop() {
        logApplicationLifecycle("Stopping");

        ServiceStateLogic.ServiceNotUpLogic.updateNotUpIndicator(this, Attributes.SERVICE_STATE_ACTUAL, "Application stopping");
        setAttribute(SERVICE_UP, false);
        ServiceStateLogic.setExpectedState(this, Lifecycle.STOPPING);
        recordApplicationEvent(Lifecycle.STOPPING);
        try {
            doStop();
        } catch (Exception e) {
            ServiceStateLogic.setExpectedState(this, Lifecycle.ON_FIRE);
            recordApplicationEvent(Lifecycle.ON_FIRE);
            log.warn("Error stopping application " + this + " (rethrowing): "+e);
            throw Exceptions.propagate(e);
        }
        ServiceStateLogic.ServiceNotUpLogic.updateNotUpIndicator(this, Attributes.SERVICE_STATE_ACTUAL, "Application stopping");
        ServiceStateLogic.setExpectedState(this, Lifecycle.STOPPED);
        recordApplicationEvent(Lifecycle.STOPPED);

        if (getParent()==null) {
            synchronized (this) {
                deployed = false;
                //TODO review mgmt destroy lifecycle
                //  we don't necessarily want to forget all about the app on stop, 
                //since operator may be interested in things recently stopped;
                //but that could be handled by the impl at management
                //(keeping recently unmanaged things)  
                //  however unmanaging must be done last, _after_ we stop children and set attributes 
                getEntityManager().unmanage(this);
            }
        }

        logApplicationLifecycle("Stopped");
    }

    protected void doStop() {
        StartableMethods.stop(this);
    }

    /** default impl invokes restart on all children simultaneously */
    @Override
    public void restart() {
        StartableMethods.restart(this);
    }

    @Override
    public void onManagementStopped() {
        super.onManagementStopped();
        if (getManagementContext().isRunning()) {
            recordApplicationEvent(Lifecycle.DESTROYED);
        }
    }
    
    private void recordApplicationEvent(Lifecycle state) {
        try {
            ((ManagementContextInternal)getManagementContext()).getUsageManager().recordApplicationEvent(this, state);
        } catch (RuntimeInterruptedException e) {
            throw e;
        } catch (RuntimeException e) {
            if (getManagementContext().isRunning()) {
                log.warn("Problem recording application event '"+state+"' for "+this, e);
            }
        }
    }
}
