/*
 * Copyright (c) 2008-2016 Haulmont.
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
 */
package com.haulmont.cuba.security.app;

import com.haulmont.chile.core.datatypes.Datatypes;
import com.haulmont.chile.core.model.Instance;
import com.haulmont.chile.core.model.MetaClass;
import com.haulmont.chile.core.model.MetaProperty;
import com.haulmont.cuba.core.EntityManager;
import com.haulmont.cuba.core.Persistence;
import com.haulmont.cuba.core.Transaction;
import com.haulmont.cuba.core.TypedQuery;
import com.haulmont.cuba.core.entity.EmbeddableEntity;
import com.haulmont.cuba.core.entity.Entity;
import com.haulmont.cuba.core.entity.HasUuid;
import com.haulmont.cuba.core.global.*;
import com.haulmont.cuba.core.sys.AppContext;
import com.haulmont.cuba.core.sys.persistence.EntityAttributeChanges;
import com.haulmont.cuba.security.entity.*;
import org.apache.commons.lang.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component(EntityLogAPI.NAME)
public class EntityLog implements EntityLogAPI {

    protected Logger log = LoggerFactory.getLogger(EntityLog.class);

    private volatile boolean loaded;

    private EntityLogConfig config;

    private Map<String, Set<String>> entitiesManual;
    private Map<String, Set<String>> entitiesAuto;

    private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ThreadLocal<Boolean> entityLogSwitchedOn = new ThreadLocal<>();

    @Inject
    protected TimeSource timeSource;

    @Inject
    protected Persistence persistence;

    @Inject
    protected Metadata metadata;

    @Inject
    protected MetadataTools metadataTools;

    @Inject
    protected UserSessionSource userSessionSource;

    @Inject
    public EntityLog(Configuration configuration) {
        config = configuration.getConfig(EntityLogConfig.class);
    }

    @Override
    public void processLoggingForCurrentThread(boolean enabled) {
        entityLogSwitchedOn.set(enabled);
    }

    @Override
    public boolean isLoggingForCurrentThread() {
        return !Boolean.FALSE.equals(entityLogSwitchedOn.get());
    }

    @Override
    public synchronized boolean isEnabled() {
        return config.getEnabled() && isLoggingForCurrentThread();
    }

    @Override
    public synchronized void setEnabled(boolean enabled) {
        if (enabled != config.getEnabled()) {
            config.setEnabled(enabled);
        }
    }

    @Override
    public void invalidateCache() {
        lock.writeLock().lock();
        try {
            log.debug("Invalidating cache");
            entitiesManual = null;
            entitiesAuto = null;
            loaded = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected Set<String> getLoggedAttributes(String entity, boolean auto) {
        lock.readLock().lock();
        try {
            if (!loaded) {
                // upgrade lock
                lock.readLock().unlock();
                lock.writeLock().lock();
                try {
                    if (!loaded) { // recheck because we unlocked for a while
                        loadEntities();
                        loaded = true;
                    }
                } finally {
                    // downgrade lock
                    lock.writeLock().unlock();
                    lock.readLock().lock();
                }
            }

            Set<String> attributes;
            if (auto)
                attributes = entitiesAuto.get(entity);
            else
                attributes = entitiesManual.get(entity);

            return attributes == null ? null : Collections.unmodifiableSet(attributes);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void loadEntities() {
        log.debug("Loading entities");
        entitiesManual = new HashMap<>();
        entitiesAuto = new HashMap<>();
        Transaction tx = persistence.createTransaction();
        try {
            EntityManager em = persistence.getEntityManager();
            TypedQuery<LoggedEntity> q = em.createQuery(
                    "select e from sec$LoggedEntity e where e.auto = true or e.manual = true",
                    LoggedEntity.class);
//            q.setView(null);
            List<LoggedEntity> list = q.getResultList();
            for (LoggedEntity loggedEntity : list) {
                if (loggedEntity.getName() == null) {
                    throw new IllegalStateException("Unable to initialize EntityLog: empty LoggedEntity.name");
                }
                Set<String> attributes = new HashSet<>();
                for (LoggedAttribute loggedAttribute : loggedEntity.getAttributes()) {
                    if (loggedAttribute.getName() == null) {
                        throw new IllegalStateException("Unable to initialize EntityLog: empty LoggedAttribute.name");
                    }
                    attributes.add(loggedAttribute.getName());
                }
                if (BooleanUtils.isTrue(loggedEntity.getAuto()))
                    entitiesAuto.put(loggedEntity.getName(), attributes);
                if (BooleanUtils.isTrue(loggedEntity.getManual()))
                    entitiesManual.put(loggedEntity.getName(), attributes);
            }
            tx.commit();
        } finally {
            tx.end();
        }
        log.debug("Loaded: entitiesAuto=" + entitiesAuto.size() + ", entitiesManual=" + entitiesManual.size());
    }

    private String getEntityName(Entity entity) {
        MetaClass metaClass = metadata.getSession().getClassNN(entity.getClass());
        MetaClass originalMetaClass = metadata.getExtendedEntities().getOriginalMetaClass(metaClass);
        return originalMetaClass != null ? originalMetaClass.getName() : metaClass.getName();
    }

    protected boolean doNotRegister(Entity entity) {
        return entity == null || !(entity instanceof HasUuid) || entity instanceof EntityLogItem || !isEnabled();
    }

    @Override
    public void registerCreate(Entity entity) {
        if (entity == null)
            return;
        registerCreate(entity, false);
    }

    @Override
    public void registerCreate(Entity entity, boolean auto) {
        try {
            if (doNotRegister(entity))
                return;

            String entityName = getEntityName(entity);
            Set<String> attributes = getLoggedAttributes(entityName, auto);
            if (attributes != null && attributes.contains("*")) {
                attributes = getAllAttributes(entity);
            }
            if (attributes == null) {
                return;
            }
            String storeName = metadata.getTools().getStoreName(metadata.getClassNN(entityName));
            if (Stores.isMain(storeName)) {
                internalRegisterCreate(entity, entityName, attributes);
            } else {
                // Create a new transaction in main DB if we are saving an entity from additional data store
                try (Transaction tx = persistence.createTransaction()) {
                    internalRegisterCreate(entity, entityName, attributes);
                    tx.commit();
                }
            }
        } catch (Exception e) {
            logError(entity, e);
        }
    }

    protected void internalRegisterCreate(Entity entity, String entityName, Set<String> attributes) throws IOException {
        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        EntityLogItem item = metadata.create(EntityLogItem.class);
        item.setEventTs(ts);
        item.setUser(findUser(em));
        item.setType(EntityLogItem.Type.CREATE);
        item.setEntity(entityName);
        item.setEntityId(((HasUuid) entity).getUuid());

        Properties properties = new Properties();
        for (String attr : attributes) {
            writeAttribute(properties, entity, attr);
        }
        item.setChanges(getChanges(properties));

        em.persist(item);
    }

    protected User findUser(EntityManager em) {
        if (AppContext.isStarted())
            return em.getReference(User.class, userSessionSource.getUserSession().getUser().getId());
        else {
            String login = AppContext.getProperty("cuba.jmxUserLogin");
            TypedQuery<User> query = em.createQuery("select u from sec$User u where u.loginLowerCase = ?1", User.class);
            query.setParameter(1, login);
            User user = query.getFirstResult();
            if (user != null)
                return user;
            else
                throw new RuntimeException("The user '" + login + "' specified in cuba.jmxUserLogin does not exist");
        }
    }

    @Override
    public void registerModify(Entity entity) {
        registerModify(entity, false);
    }

    @Override
    public void registerModify(Entity entity, boolean auto) {
        registerModify(entity, auto, null);
    }

    @Override
    public void registerModify(Entity entity, boolean auto, @Nullable EntityAttributeChanges changes) {
        try {
            if (doNotRegister(entity))
                return;

            String entityName = getEntityName(entity);
            Set<String> attributes = getLoggedAttributes(entityName, auto);
            if (attributes != null && attributes.contains("*")) {
                attributes = getAllAttributes(entity);
            }
            if (attributes == null) {
                return;
            }

            MetaClass metaClass = metadata.getClassNN(entityName);
            String storeName = metadataTools.getStoreName(metaClass);
            if (Stores.isMain(storeName)) {
                internalRegisterModify(entity, changes, metaClass, storeName, attributes);
            } else {
                // Create a new transaction in main DB if we are saving an entity from additional data store
                try (Transaction tx = persistence.createTransaction()) {
                    internalRegisterModify(entity, changes, metaClass, storeName, attributes);
                    tx.commit();
                }
            }
        } catch (Exception e) {
            logError(entity, e);
        }
    }

    protected void internalRegisterModify(Entity entity, @Nullable EntityAttributeChanges changes, MetaClass metaClass,
                                          String storeName, Set<String> attributes) throws IOException {
        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        Set<String> dirty;
        if (changes == null) {
            dirty = persistence.getTools().getDirtyFields(entity);
        } else {
            dirty = changes.getAttributes();
        }

        Properties properties = new Properties();
        for (String attr : attributes) {
            if (dirty.contains(attr)) {
                writeAttribute(properties, entity, attr);
            } else if (!Stores.getAdditional().isEmpty()) {
                String idAttr = metadataTools.getCrossDataStoreReferenceIdProperty(storeName, metaClass.getPropertyNN(attr));
                if (idAttr != null && dirty.contains(idAttr)) {
                    writeAttribute(properties, entity, attr);
                }
            }
        }
        if (!properties.isEmpty()) {
            EntityLogItem item = metadata.create(EntityLogItem.class);
            item.setEventTs(ts);
            item.setUser(findUser(em));
            item.setType(EntityLogItem.Type.MODIFY);
            item.setEntity(metaClass.getName());
            item.setEntityId(((HasUuid) entity).getUuid());
            item.setChanges(getChanges(properties));

            em.persist(item);
        }
    }

    private String getChanges(Properties properties) throws IOException {
        StringWriter writer = new StringWriter();
        properties.store(writer, null);
        String changes = writer.toString();
        if (changes.startsWith("#"))
            changes = changes.substring(changes.indexOf("\n") + 1); // cut off comments line
        return changes;
    }

    protected void writeAttribute(Properties properties, Entity entity, String attr) {
        if (!PersistenceHelper.isLoaded(entity, attr))
            return;

        Object value = entity.getValue(attr);
        properties.setProperty(attr, stringify(value));

        UUID valueId = getValueId(value);
        if (valueId != null)
            properties.setProperty(attr + EntityLogAttr.VALUE_ID_SUFFIX, valueId.toString());

        MessageTools messageTools = AppBeans.get(MessageTools.NAME);
        String mp = messageTools.inferMessagePack(attr, entity);
        if (mp != null)
            properties.setProperty(attr + EntityLogAttr.MP_SUFFIX, mp);
    }

    @Override
    public void registerDelete(Entity entity) {
        registerDelete(entity, false);
    }

    @Override
    public void registerDelete(Entity entity, boolean auto) {
        try {
            if (doNotRegister(entity))
                return;

            String entityName = getEntityName(entity);
            Set<String> attributes = getLoggedAttributes(entityName, auto);
            if (attributes != null && attributes.contains("*")) {
                attributes = getAllAttributes(entity);
            }
            if (attributes == null) {
                return;
            }
            String storeName = metadata.getTools().getStoreName(metadata.getClassNN(entityName));
            if (Stores.isMain(storeName)) {
                internalRegisterDelete(entity, entityName, attributes);
            } else {
                // Create a new transaction in main DB if we are saving an entity from additional data store
                try (Transaction tx = persistence.createTransaction()) {
                    internalRegisterDelete(entity, entityName, attributes);
                    tx.commit();
                }
            }
        } catch (Exception e) {
            logError(entity, e);
        }
    }

    protected void internalRegisterDelete(Entity entity, String entityName, Set<String> attributes) throws IOException {
        Date ts = timeSource.currentTimestamp();
        EntityManager em = persistence.getEntityManager();

        EntityLogItem item = metadata.create(EntityLogItem.class);
        item.setEventTs(ts);
        item.setUser(findUser(em));
        item.setType(EntityLogItem.Type.DELETE);
        item.setEntity(entityName);
        item.setEntityId(((HasUuid) entity).getUuid());

        Properties properties = new Properties();
        for (String attr : attributes) {
            writeAttribute(properties, entity, attr);
        }
        item.setChanges(getChanges(properties));

        em.persist(item);
    }

    protected Set<String> getAllAttributes(Entity entity) {
        if (entity == null) {
            return null;
        }
        Set<String> attributes = new HashSet<>();
        for (MetaProperty metaProperty : metadata.getSession().getClassNN(entity.getClass()).getProperties()) {
            attributes.add(metaProperty.getName());
        }
        return attributes;
    }

    protected UUID getValueId(Object value) {
        if (value instanceof EmbeddableEntity) {
            return null;
        } else if (value instanceof HasUuid) {
            return ((HasUuid) value).getUuid();
        } else {
            return null;
        }
    }

    protected String stringify(Object value) {
        if (value == null)
            return "";
        else if (value instanceof Instance) {
            return ((Instance) value).getInstanceName();
        } else if (value instanceof Date) {
            return Datatypes.getNN(value.getClass()).format(value);
        } else if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (Object obj : (Iterable) value) {
                sb.append(stringify(obj)).append(",");
            }
            if (sb.length() > 1)
                sb.deleteCharAt(sb.length() - 1);
            sb.append("]");
            return sb.toString();
        } else {
            return String.valueOf(value);
        }
    }

    protected void logError(Entity entity, Exception e) {
        log.warn("Unable to log entity " + entity + ", id=" + entity.getId(), e);
    }
}