/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     <a href="mailto:grenard@nuxeo.com">Guillaume</a>
 */
package org.nuxeo.ecm.collections.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;
import org.nuxeo.common.utils.i18n.I18NUtils;
import org.nuxeo.ecm.collections.api.CollectionConstants;
import org.nuxeo.ecm.collections.api.CollectionManager;
import org.nuxeo.ecm.collections.core.adapter.Collection;
import org.nuxeo.ecm.collections.core.adapter.CollectionMember;
import org.nuxeo.ecm.collections.core.listener.CollectionAsynchrnonousQuery;
import org.nuxeo.ecm.collections.core.worker.DuplicateCollectionMemberWork;
import org.nuxeo.ecm.collections.core.worker.RemoveFromCollectionWork;
import org.nuxeo.ecm.collections.core.worker.RemovedAbstractWork;
import org.nuxeo.ecm.collections.core.worker.RemovedCollectionMemberWork;
import org.nuxeo.ecm.collections.core.worker.RemovedCollectionWork;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.DocumentSecurityException;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.LifeCycleConstants;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.api.event.CoreEventConstants;
import org.nuxeo.ecm.core.api.event.DocumentEventCategories;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.ecm.core.event.Event;
import org.nuxeo.ecm.core.event.EventService;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.core.versioning.VersioningService;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.platform.audit.service.NXAuditEventsService;
import org.nuxeo.ecm.platform.dublincore.listener.DublinCoreListener;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.userworkspace.api.UserWorkspaceService;
import org.nuxeo.ecm.platform.web.common.locale.LocaleProvider;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * @since 5.9.3
 */
public class CollectionManagerImpl extends DefaultComponent implements CollectionManager {

    private static final String PERMISSION_ERROR_MESSAGE = "Privilege '%s' is not granted to '%s'";

    public static void disableEvents(final DocumentModel doc) {
        doc.putContextData(DublinCoreListener.DISABLE_DUBLINCORE_LISTENER, true);
        doc.putContextData(NotificationConstants.DISABLE_NOTIFICATION_SERVICE, true);
        doc.putContextData(NXAuditEventsService.DISABLE_AUDIT_LOGGER, true);
        doc.putContextData(VersioningService.DISABLE_AUTO_CHECKOUT, true);
    }

    @Override
    public void addToCollection(final DocumentModel collection, final DocumentModel documentToBeAdded,
            final CoreSession session) throws DocumentSecurityException {
        checkCanAddToCollection(collection, documentToBeAdded, session);
        final Map<String, Serializable> props = new HashMap<>();
        props.put(CollectionConstants.COLLECTION_REF_EVENT_CTX_PROP, collection.getRef());
        fireEvent(documentToBeAdded, session, CollectionConstants.BEFORE_ADDED_TO_COLLECTION, props);
        Collection colAdapter = collection.getAdapter(Collection.class);
        colAdapter.addDocument(documentToBeAdded.getId());
        collection.getCoreSession().saveDocument(colAdapter.getDocument());

        new UnrestrictedSessionRunner(session) {

            @Override
            public void run() {

                DocumentModel temp = documentToBeAdded;

                temp.addFacet(CollectionConstants.COLLECTABLE_FACET);

                disableEvents(temp);

                temp = session.saveDocument(temp);

                // We want to disable the following listener on a
                // collection member when it is added to a collection
                disableEvents(temp);

                CollectionMember docAdapter = temp.getAdapter(CollectionMember.class);
                docAdapter.addToCollection(collection.getId());
                DocumentModel addedDoc = session.saveDocument(docAdapter.getDocument());
                fireEvent(addedDoc, session, CollectionConstants.ADDED_TO_COLLECTION, props);
            }

        }.runUnrestricted();
    }

    @Override
    public void addToCollection(final DocumentModel collection, final List<DocumentModel> documentListToBeAdded,
            final CoreSession session) {
        for (DocumentModel documentToBeAdded : documentListToBeAdded) {
            addToCollection(collection, documentToBeAdded, session);
        }
    }

    @Override
    public void addToNewCollection(final String newTitle, final String newDescription,
            final DocumentModel documentToBeAdded, final CoreSession session) {
        addToCollection(createCollection(newTitle, newDescription, documentToBeAdded, session), documentToBeAdded,
                session);
    }

    @Override
    public void addToNewCollection(final String newTitle, final String newDescription,
            final List<DocumentModel> documentListToBeAdded, CoreSession session) {
        DocumentModel newCollection = createCollection(newTitle, newDescription, documentListToBeAdded.get(0), session);
        for (DocumentModel documentToBeAdded : documentListToBeAdded) {
            addToCollection(newCollection, documentToBeAdded, session);
        }
    }

    @Override
    public boolean canAddToCollection(final DocumentModel collection, final CoreSession session) {
        return isCollection(collection)
                && session.hasPermission(collection.getRef(), SecurityConstants.WRITE_PROPERTIES);
    }

    @Override
    public boolean canManage(final DocumentModel collection, final CoreSession session) {
        return isCollection(collection) && session.hasPermission(collection.getRef(), SecurityConstants.EVERYTHING);
    }

    public void checkCanAddToCollection(final DocumentModel collection, final DocumentModel documentToBeAdded,
            final CoreSession session) {
        if (!isCollectable(documentToBeAdded)) {
            throw new IllegalArgumentException(String.format("Document %s is not collectable",
                    documentToBeAdded.getTitle()));
        }
        if (!isCollection(collection)) {
            throw new IllegalArgumentException(String.format("Document %s is not a collection",
                    documentToBeAdded.getTitle()));
        }
        if (!session.hasPermission(collection.getRef(), SecurityConstants.WRITE_PROPERTIES)) {
            throw new DocumentSecurityException(String.format(PERMISSION_ERROR_MESSAGE,
                    CollectionConstants.CAN_COLLECT_PERMISSION, session.getPrincipal().getName()));
        }
    }

    protected DocumentModel createCollection(final String newTitle, final String newDescription,
            final DocumentModel context, final CoreSession session) {
        DocumentModel defaultCollections = getUserDefaultCollections(context, session);
        DocumentModel newCollection = session.createDocumentModel(defaultCollections.getPath().toString(), newTitle,
                CollectionConstants.COLLECTION_TYPE);
        newCollection.setPropertyValue("dc:title", newTitle);
        newCollection.setPropertyValue("dc:description", newDescription);
        return session.createDocument(newCollection);
    }

    protected DocumentModel createDefaultCollections(final CoreSession session, DocumentModel userWorkspace)
            {
        DocumentModel doc = session.createDocumentModel(userWorkspace.getPath().toString(),
                CollectionConstants.DEFAULT_COLLECTIONS_NAME, CollectionConstants.COLLECTIONS_TYPE);
        String title = null;
        try {
            title = I18NUtils.getMessageString("messages", CollectionConstants.DEFAULT_COLLECTIONS_TITLE,
                    new Object[0], getLocale(session));
        } catch (MissingResourceException e) {
            title = CollectionConstants.DEFAULT_COLLECTIONS_TITLE;
        }
        doc.setPropertyValue("dc:title", title);
        doc.setPropertyValue("dc:description", "");
        doc = session.createDocument(doc);

        ACP acp = new ACPImpl();
        ACE denyEverything = new ACE(SecurityConstants.EVERYONE, SecurityConstants.EVERYTHING, false);
        ACE allowEverything = new ACE(session.getPrincipal().getName(), SecurityConstants.EVERYTHING, true);
        ACL acl = new ACLImpl();
        acl.setACEs(new ACE[] { allowEverything, denyEverything });
        acp.addACL(acl);
        doc.setACP(acp, true);

        return doc;
    }

    @Override
    public DocumentModel getUserDefaultCollections(final DocumentModel context, final CoreSession session)
            {
        final UserWorkspaceService userWorkspaceService = Framework.getLocalService(UserWorkspaceService.class);
        final DocumentModel userWorkspace = userWorkspaceService.getCurrentUserPersonalWorkspace(session, context);
        final DocumentRef lookupRef = new PathRef(userWorkspace.getPath().toString(),
                CollectionConstants.DEFAULT_COLLECTIONS_NAME);
        if (session.exists(lookupRef)) {
            return session.getChild(userWorkspace.getRef(), CollectionConstants.DEFAULT_COLLECTIONS_NAME);
        } else {
            // does not exist yet, let's create it
            synchronized (this) {
                TransactionHelper.commitOrRollbackTransaction();
                TransactionHelper.startTransaction();
                if (!session.exists(lookupRef)) {
                    boolean succeed = false;
                    try {
                        createDefaultCollections(session, userWorkspace);
                        succeed = true;
                    } finally {
                        if (succeed) {
                            TransactionHelper.commitOrRollbackTransaction();
                            TransactionHelper.startTransaction();
                        }
                    }
                }
                return session.getDocument(lookupRef);
            }
        }
    }

    @Override
    public List<DocumentModel> getVisibleCollection(final DocumentModel collectionMember, final CoreSession session)
            {
        return getVisibleCollection(collectionMember, CollectionConstants.MAX_COLLECTION_RETURNED, session);
    }

    @Override
    public List<DocumentModel> getVisibleCollection(final DocumentModel collectionMember, int maxResult,
            CoreSession session) {
        List<DocumentModel> result = new ArrayList<DocumentModel>();
        CollectionMember collectionMemberAdapter = collectionMember.getAdapter(CollectionMember.class);
        List<String> collectionIds = collectionMemberAdapter.getCollectionIds();
        for (int i = 0; i < collectionIds.size() && result.size() < maxResult; i++) {
            final String collectionId = collectionIds.get(i);
            DocumentRef documentRef = new IdRef(collectionId);
            if (session.exists(documentRef) && session.hasPermission(documentRef, SecurityConstants.READ)
                    && !LifeCycleConstants.DELETED_STATE.equals(session.getCurrentLifeCycleState(documentRef))) {
                DocumentModel collection = session.getDocument(documentRef);
                if (!collection.isVersion()) {
                    result.add(collection);
                }
            }
        }
        return result;
    }

    @Override
    public boolean hasVisibleCollection(final DocumentModel collectionMember, CoreSession session)
            {
        CollectionMember collectionMemberAdapter = collectionMember.getAdapter(CollectionMember.class);
        List<String> collectionIds = collectionMemberAdapter.getCollectionIds();
        for (final String collectionId : collectionIds) {
            DocumentRef documentRef = new IdRef(collectionId);
            if (session.exists(documentRef) && session.hasPermission(documentRef, SecurityConstants.READ)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isCollectable(final DocumentModel doc) {
        return !doc.hasFacet(CollectionConstants.NOT_COLLECTABLE_FACET) && !doc.isVersion() && !doc.isProxy();
    }

    @Override
    public boolean isCollected(final DocumentModel doc) {
        return doc.hasFacet(CollectionConstants.COLLECTABLE_FACET);
    }

    @Override
    public boolean isCollection(final DocumentModel doc) {
        return doc.hasFacet(CollectionConstants.COLLECTION_FACET);
    }

    @Override
    public boolean isInCollection(DocumentModel collection, DocumentModel document, CoreSession session)
            {
        if (isCollected(document)) {
            final CollectionMember collectionMemberAdapter = document.getAdapter(CollectionMember.class);
            return collectionMemberAdapter.getCollectionIds().contains(collection.getId());
        }
        return false;
    }

    @Override
    public void processCopiedCollection(final DocumentModel collection) {
        Collection collectionAdapter = collection.getAdapter(Collection.class);
        List<String> documentIds = collectionAdapter.getCollectedDocumentIds();

        int i = 0;
        while (i < documentIds.size()) {
            int limit = (int) (((i + CollectionAsynchrnonousQuery.MAX_RESULT) > documentIds.size()) ? documentIds.size()
                    : (i + CollectionAsynchrnonousQuery.MAX_RESULT));
            DuplicateCollectionMemberWork work = new DuplicateCollectionMemberWork(collection.getRepositoryName(),
                    collection.getId(), documentIds.subList(i, limit), i);
            WorkManager workManager = Framework.getLocalService(WorkManager.class);
            workManager.schedule(work, WorkManager.Scheduling.IF_NOT_SCHEDULED, true);

            i = limit;
        }
    }

    @Override
    public void processRemovedCollection(final DocumentModel collection) {
        final WorkManager workManager = Framework.getLocalService(WorkManager.class);
        final RemovedAbstractWork work = new RemovedCollectionWork();
        work.setDocument(collection.getRepositoryName(), collection.getId());
        workManager.schedule(work, WorkManager.Scheduling.IF_NOT_SCHEDULED, true);
    }

    @Override
    public void processRemovedCollectionMember(final DocumentModel collectionMember) {
        final WorkManager workManager = Framework.getLocalService(WorkManager.class);
        final RemovedAbstractWork work = new RemovedCollectionMemberWork();
        work.setDocument(collectionMember.getRepositoryName(), collectionMember.getId());
        workManager.schedule(work, WorkManager.Scheduling.IF_NOT_SCHEDULED, true);
    }

    @Override
    public void processRestoredCollection(DocumentModel collection, DocumentModel version) {
        final Set<String> collectionMemberIdsToBeRemoved = new TreeSet<String>(
                collection.getAdapter(Collection.class).getCollectedDocumentIds());
        collectionMemberIdsToBeRemoved.removeAll(version.getAdapter(Collection.class).getCollectedDocumentIds());

        final Set<String> collectionMemberIdsToBeAdded = new TreeSet<String>(
                version.getAdapter(Collection.class).getCollectedDocumentIds());
        collectionMemberIdsToBeAdded.removeAll(collection.getAdapter(Collection.class).getCollectedDocumentIds());

        int i = 0;
        while (i < collectionMemberIdsToBeRemoved.size()) {
            int limit = (int) (((i + CollectionAsynchrnonousQuery.MAX_RESULT) > collectionMemberIdsToBeRemoved.size())
                    ? collectionMemberIdsToBeRemoved.size() : (i + CollectionAsynchrnonousQuery.MAX_RESULT));
            RemoveFromCollectionWork work = new RemoveFromCollectionWork(collection.getRepositoryName(),
                    collection.getId(), new ArrayList<String>(collectionMemberIdsToBeRemoved).subList(i, limit), i);
            WorkManager workManager = Framework.getLocalService(WorkManager.class);
            workManager.schedule(work, WorkManager.Scheduling.IF_NOT_SCHEDULED, true);

            i = limit;
        }
        i = 0;
        while (i < collectionMemberIdsToBeAdded.size()) {
            int limit = (int) (((i + CollectionAsynchrnonousQuery.MAX_RESULT) > collectionMemberIdsToBeAdded.size())
                    ? collectionMemberIdsToBeAdded.size() : (i + CollectionAsynchrnonousQuery.MAX_RESULT));
            DuplicateCollectionMemberWork work = new DuplicateCollectionMemberWork(collection.getRepositoryName(),
                    collection.getId(), new ArrayList<String>(collectionMemberIdsToBeAdded).subList(i, limit), i);
            WorkManager workManager = Framework.getLocalService(WorkManager.class);
            workManager.schedule(work, WorkManager.Scheduling.IF_NOT_SCHEDULED, true);

            i = limit;
        }
    }

    @Override
    public void removeAllFromCollection(final DocumentModel collection,
            final List<DocumentModel> documentListToBeRemoved, final CoreSession session) {
        for (DocumentModel documentToBeRemoved : documentListToBeRemoved) {
            removeFromCollection(collection, documentToBeRemoved, session);
        }
    }

    @Override
    public void removeFromCollection(final DocumentModel collection, final DocumentModel documentToBeRemoved,
            final CoreSession session) {
        checkCanAddToCollection(collection, documentToBeRemoved, session);
        Map<String, Serializable> props = new HashMap<>();
        props.put(CollectionConstants.COLLECTION_REF_EVENT_CTX_PROP, new IdRef(collection.getId()));
        fireEvent(documentToBeRemoved, session, CollectionConstants.BEFORE_REMOVED_FROM_COLLECTION, props);
        Collection colAdapter = collection.getAdapter(Collection.class);
        colAdapter.removeDocument(documentToBeRemoved.getId());
        collection.getCoreSession().saveDocument(colAdapter.getDocument());

        new UnrestrictedSessionRunner(session) {

            @Override
            public void run() {
                doRemoveFromCollection(documentToBeRemoved, collection.getId(), session);
            }

        }.runUnrestricted();
    }

    @Override
    public void doRemoveFromCollection(DocumentModel documentToBeRemoved, String collectionId, CoreSession session) {
        // We want to disable the following listener on a
        // collection member when it is removed from a collection
        disableEvents(documentToBeRemoved);

        CollectionMember docAdapter = documentToBeRemoved.getAdapter(CollectionMember.class);
        docAdapter.removeFromCollection(collectionId);
        DocumentModel removedDoc = session.saveDocument(docAdapter.getDocument());
        Map<String, Serializable> props = new HashMap<>();
        props.put(CollectionConstants.COLLECTION_REF_EVENT_CTX_PROP, new IdRef(collectionId));
        fireEvent(removedDoc, session, CollectionConstants.REMOVED_FROM_COLLECTION, props);
    }

    @Override
    public DocumentModel createCollection(final CoreSession session, String title, String description, String path)
            {
        DocumentModel newCollection = null;
        // Test if the path is null or empty
        if (StringUtils.isEmpty(path)) {
            // A default collection is created with the given name
            newCollection = createCollection(title, description, null, session);
        } else {
            // If the path does not exist, an exception is thrown
            if (!session.exists(new PathRef(path))) {
                throw new NuxeoException(String.format("Path \"%s\" specified in parameter not found", path));
            }
            // Create a new collection in the given path
            DocumentModel collectionModel = session.createDocumentModel(path, title,
                    CollectionConstants.COLLECTION_TYPE);
            collectionModel.setPropertyValue("dc:title", title);
            collectionModel.setPropertyValue("dc:description", description);
            newCollection = session.createDocument(collectionModel);
        }
        return newCollection;
    }

    protected Locale getLocale(final CoreSession session) {
        Locale locale = null;
        locale = Framework.getLocalService(LocaleProvider.class).getLocale(session);
        if (locale == null) {
            locale = Locale.getDefault();
        }
        return new Locale(Locale.getDefault().getLanguage());
    }

    protected void fireEvent(DocumentModel doc, CoreSession session, String eventName, Map<String, Serializable> props)
            {
        EventService eventService = Framework.getService(EventService.class);
        DocumentEventContext ctx = new DocumentEventContext(session, session.getPrincipal(), doc);
        ctx.setProperty(CoreEventConstants.REPOSITORY_NAME, session.getRepositoryName());
        ctx.setProperty(CoreEventConstants.SESSION_ID, session.getSessionId());
        ctx.setProperty("category", DocumentEventCategories.EVENT_DOCUMENT_CATEGORY);
        ctx.setProperties(props);
        Event event = ctx.newEvent(eventName);
        eventService.fireEvent(event);
    }

}