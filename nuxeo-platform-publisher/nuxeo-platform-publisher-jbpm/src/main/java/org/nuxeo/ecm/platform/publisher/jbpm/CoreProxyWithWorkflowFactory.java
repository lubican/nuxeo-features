/*
 * (C) Copyright 2009 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thomas Roger
 */

package org.nuxeo.ecm.platform.publisher.jbpm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jbpm.taskmgmt.exe.TaskInstance;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.ClientRuntimeException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoGroup;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.event.impl.DocumentEventContext;
import org.nuxeo.ecm.platform.ec.notification.NotificationConstants;
import org.nuxeo.ecm.platform.jbpm.JbpmEventNames;
import org.nuxeo.ecm.platform.jbpm.JbpmService;
import org.nuxeo.ecm.platform.jbpm.NuxeoJbpmException;
import org.nuxeo.ecm.platform.publisher.api.PublicationNode;
import org.nuxeo.ecm.platform.publisher.api.PublishedDocument;
import org.nuxeo.ecm.platform.publisher.api.PublishedDocumentFactory;
import org.nuxeo.ecm.platform.publisher.api.PublishingEvent;
import org.nuxeo.ecm.platform.publisher.api.PublishingException;
import org.nuxeo.ecm.platform.publisher.impl.core.CoreFolderPublicationNode;
import org.nuxeo.ecm.platform.publisher.impl.core.CoreProxyFactory;
import org.nuxeo.ecm.platform.publisher.impl.core.SimpleCorePublishedDocument;
import org.nuxeo.ecm.platform.publisher.rules.PublishingValidatorException;

import org.nuxeo.ecm.platform.publisher.rules.ValidatorsRule;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.runtime.api.Framework;

/**
 * Implementation of the {@link PublishedDocumentFactory} for core
 * implementation using native proxy system with validation workflow.
 *
 * @author <a href="mailto:troger@nuxeo.com">Thomas Roger</a>
 * @author <a href="mailto:tmartins@nuxeo.com">Thierry Martins</a>
 */
public class CoreProxyWithWorkflowFactory extends CoreProxyFactory implements
        PublishedDocumentFactory {

    public static final String TASK_NAME = "org.nuxeo.ecm.platform.publisher.jbpm.CoreProxyWithWorkflowFactory";

    public static final String ACL_NAME = "org.nuxeo.ecm.platform.publisher.jbpm.CoreProxyWithWorkflowFactory";


    // XXX ataillefer: remove if refactor old JBPM ACL name
    public static final String JBPM_ACL_NAME = "org.nuxeo.ecm.platform.publisher.jbpm.CoreProxyWithWorkflowFactory";

    public static final String PUBLISH_TASK_TYPE = "publish_moderate";

    public static final String LOOKUP_STATE_PARAM_KEY = "lookupState";

    public static final String LOOKUP_STATE_PARAM_BYACL = "byACL";

    public static final String LOOKUP_STATE_PARAM_BYTASK = "byTask";

    protected LookupState lookupState = new LookupStateByACL();

    @Override
    public void init(CoreSession coreSession, ValidatorsRule validatorsRule,
            Map<String, String> parameters) throws ClientException {
        super.init(coreSession, validatorsRule, parameters);
        // setup lookup state strategy if requested
        String lookupState = parameters.get(LOOKUP_STATE_PARAM_KEY);
        if (lookupState != null) {
            if (LOOKUP_STATE_PARAM_BYACL.equals(lookupState)) {
                setLookupByACL();
            } else if (LOOKUP_STATE_PARAM_BYTASK.equals(lookupState)) {
                setLookupByTask();
            }
        }
    }

    public void setLookupByTask() {
        this.lookupState = new LookupStateByTask();
    }

    public void setLookupByACL() {
        this.lookupState = new LookupStateByACL();
    }

    @Override
    public PublishedDocument publishDocument(DocumentModel doc,
            PublicationNode targetNode, Map<String, String> params)
            throws ClientException {
        DocumentModel targetDocModel;
        if (targetNode instanceof CoreFolderPublicationNode) {
            CoreFolderPublicationNode coreNode = (CoreFolderPublicationNode) targetNode;
            targetDocModel = coreNode.getTargetDocumentModel();
        } else {
            targetDocModel = coreSession.getDocument(new PathRef(
                    targetNode.getPath()));
        }
        NuxeoPrincipal principal = (NuxeoPrincipal) coreSession.getPrincipal();
        DocumentPublisherUnrestricted runner = new DocumentPublisherUnrestricted(
                coreSession, doc.getRef(), targetDocModel.getRef(), principal,
                null);
        runner.runUnrestricted();
        return runner.getPublishedDocument();
    }

    protected boolean isPublishedDocWaitingForPublication(DocumentModel doc, CoreSession session)
            throws ClientException {
        return !lookupState.isPublished(doc, session);
    }

    protected boolean isValidator(DocumentModel document,
            NuxeoPrincipal principal) throws PublishingException {
        try {
            String[] validators = getValidatorsFor(document);
            for (String s : validators) {
                if (principal.getName().equals(s) || principal.isMemberOf(s)) {
                    return true;
                }
            }
        } catch (PublishingValidatorException e) {
            throw new PublishingException(e);
        }
        return false;
    }

    protected void restrictPermission(DocumentModel newProxy,
            NuxeoPrincipal principal, CoreSession coreSession, ACL acl)
            throws PublishingValidatorException, PublishingException {
        ChangePermissionUnrestricted permissionChanger = new ChangePermissionUnrestricted(
                coreSession, newProxy, getValidatorsFor(newProxy), principal,
                ACL_NAME, acl);
        try {
            permissionChanger.runUnrestricted();
        } catch (ClientException e) {
            throw new PublishingException(e);
        }
    }

    protected void createTask(DocumentModel document, CoreSession session,
            NuxeoPrincipal principal) throws PublishingValidatorException,
            NuxeoJbpmException, PublishingException {
        TaskInstance ti = new TaskInstance();
        String[] actorIds = getValidatorsFor(document);
        List<String> prefixedActorIds = new ArrayList<String>();
        for (String s : actorIds) {
            if (s.contains(":")) {
                prefixedActorIds.add(s);
            } else {
                UserManager userManager = Framework.getLocalService(UserManager.class);
                String prefix;
                try {
                    prefix = userManager.getPrincipal(s) == null ? NuxeoGroup.PREFIX
                            : NuxeoPrincipal.PREFIX;
                } catch (ClientException e) {
                    throw new ClientRuntimeException(e);
                }
                prefixedActorIds.add(prefix + s);
            }
        }
        ti.setPooledActors(prefixedActorIds.toArray(new String[prefixedActorIds.size()]));
        Map<String, Serializable> variables = new HashMap<String, Serializable>();
        variables.put(JbpmService.VariableName.documentId.name(),
                document.getId());
        variables.put(JbpmService.VariableName.documentRepositoryName.name(),
                document.getRepositoryName());
        variables.put(JbpmService.VariableName.initiator.name(),
                principal.getName());
        ti.setVariables(variables);
        ti.setName(TASK_NAME);
        ti.setCreate(new Date());
        getJbpmService().saveTaskInstances(Collections.singletonList(ti));
        DocumentEventContext ctx = new DocumentEventContext(session, principal,
                document);
        ctx.setProperty(NotificationConstants.RECIPIENTS_KEY,
                prefixedActorIds.toArray(new String[prefixedActorIds.size()]));
        try {
            getEventProducer().fireEvent(
                    ctx.newEvent(JbpmEventNames.WORKFLOW_TASK_ASSIGNED));
            getEventProducer().fireEvent(
                    ctx.newEvent(JbpmEventNames.WORKFLOW_TASK_START));
        } catch (ClientException e) {
            throw new PublishingException(e);
        }

    }


    protected JbpmService getJbpmService() {
        return Framework.getLocalService(JbpmService.class);
    }

    @Override
    public void validatorPublishDocument(PublishedDocument publishedDocument,
            String comment) throws PublishingException {
        DocumentModel proxy = ((SimpleCorePublishedDocument) publishedDocument).getProxy();
        NuxeoPrincipal principal = (NuxeoPrincipal) coreSession.getPrincipal();
        try {
            ProxyCleaner proxyCleaner = new ProxyCleaner(coreSession, proxy);
            proxyCleaner.runUnrestricted();
        } catch (ClientException e1) {
            throw new PublishingException(e1.getMessage(), e1);
        }
        try {
            removeACL(proxy, coreSession);
            endTask(proxy, principal, coreSession, comment,
                    PublishingEvent.documentPublicationApproved);
            notifyEvent(PublishingEvent.documentPublicationApproved, proxy,
                    coreSession);
            notifyEvent(PublishingEvent.documentPublished, proxy, coreSession);
        } catch (ClientException e) {
            throw new PublishingException(e);
        }
        ((SimpleCorePublishedDocument) publishedDocument).setPending(false);
    }

    protected void removeACL(DocumentModel document, CoreSession coreSession)
            throws PublishingException {
        try {
            RemoveACLUnrestricted remover = new RemoveACLUnrestricted(
                    coreSession, document, ACL_NAME);
            remover.runUnrestricted();
        } catch (ClientException e) {
            throw new PublishingException(e);
        }
    }

    protected void endTask(DocumentModel document, NuxeoPrincipal currentUser,
            CoreSession session, String comment, PublishingEvent event)
            throws PublishingException {
        try {
            final JbpmService jbpmService = getJbpmService();
            List<TaskInstance> tis = jbpmService.getTaskInstances(
                    document, currentUser, null);
            String initiator = null;
            for (TaskInstance ti : tis) {
                if (ti.getName().equals(TASK_NAME)) {
                    initiator = (String) ti.getVariable(JbpmService.VariableName.initiator.name());
                    ti.end();
                    jbpmService.saveTaskInstances(Collections.singletonList(ti));
                    break;
                }
            }
            GetsProxySourceDocumentsUnrestricted runner = new GetsProxySourceDocumentsUnrestricted(
                    session, document);
            runner.runUnrestricted();
            Map<String, Serializable> properties = new HashMap<String, Serializable>();
            if (initiator != null) {
                properties.put(NotificationConstants.RECIPIENTS_KEY,
                        new String[] { initiator });
            }
            notifyEvent(event.name(), properties, comment, null,
                    runner.liveDocument, session);
        } catch (NuxeoJbpmException e) {
            throw new PublishingException(e);
        } catch (ClientException ce) {
            throw new PublishingException(ce);
        }
    }

    @Override
    public void validatorRejectPublication(PublishedDocument publishedDocument,
            String comment) throws PublishingException {
        DocumentModel proxy = ((SimpleCorePublishedDocument) publishedDocument).getProxy();
        NuxeoPrincipal principal = (NuxeoPrincipal) coreSession.getPrincipal();
        try {
            notifyEvent(PublishingEvent.documentPublicationRejected, proxy,
                    coreSession);
            removeProxy(proxy, coreSession);
            endTask(proxy, principal, coreSession, comment,
                    PublishingEvent.documentPublicationRejected);
        } catch (ClientException e) {
            throw new PublishingException(e);
        }
    }

    protected void removeProxy(DocumentModel doc, CoreSession coreSession)
            throws PublishingException {
        try {
            DeleteDocumentUnrestricted deleter = new DeleteDocumentUnrestricted(
                    coreSession, doc);
            deleter.runUnrestricted();
        } catch (ClientException e) {
            throw new PublishingException(e);
        }
    }

    @Override
    public PublishedDocument wrapDocumentModel(DocumentModel doc)
            throws ClientException {
        final SimpleCorePublishedDocument publishedDocument = (SimpleCorePublishedDocument) super.wrapDocumentModel(doc);

        new UnrestrictedSessionRunner(coreSession) {
            @Override
            public void run() throws ClientException {
                if (!isPublished(publishedDocument, session)) {
                    publishedDocument.setPending(true);
                }

            }
        }.runUnrestricted();

        return publishedDocument;
    }

    protected boolean isPublished(PublishedDocument publishedDocument, CoreSession session)
            throws PublishingException {
        // FIXME: should be cached
        DocumentModel proxy = ((SimpleCorePublishedDocument) publishedDocument).getProxy();
        try {
            return lookupState.isPublished(proxy, session);
        } catch (ClientException e) {
            throw new PublishingException(e);
        }
    }

    @Override
    public boolean canManagePublishing(PublishedDocument publishedDocument)
            throws ClientException {
        DocumentModel proxy = ((SimpleCorePublishedDocument) publishedDocument).getProxy();
        NuxeoPrincipal currentUser = (NuxeoPrincipal) coreSession.getPrincipal();
        return proxy.isProxy() && hasValidationTask(proxy, currentUser);
    }

    protected boolean hasValidationTask(DocumentModel proxy,
            NuxeoPrincipal currentUser) throws ClientException {
        assert currentUser != null;
        try {
            List<TaskInstance> tis = getJbpmService().getTaskInstances(proxy,
                    currentUser, null);
            for (TaskInstance ti : tis) {
                if (ti.getName().equals(TASK_NAME)) {
                    return true;
                }
            }
        } catch (NuxeoJbpmException e) {
            throw new PublishingException(e);
        }
        return false;
    }

    @Override
    public boolean hasValidationTask(PublishedDocument publishedDocument)
            throws ClientException {
        DocumentModel proxy = ((SimpleCorePublishedDocument) publishedDocument).getProxy();
        NuxeoPrincipal currentUser = (NuxeoPrincipal) coreSession.getPrincipal();
        return hasValidationTask(proxy, currentUser);
    }

    private class GetsProxySourceDocumentsUnrestricted extends
            UnrestrictedSessionRunner {

        public DocumentModel liveDocument;

        private DocumentModel sourceDocument;

        private final DocumentModel document;

        public GetsProxySourceDocumentsUnrestricted(CoreSession session,
                DocumentModel proxy) {
            super(session);
            this.document = proxy;
        }

        @Override
        public void run() throws ClientException {
            sourceDocument = session.getDocument(new IdRef(
                    document.getSourceId()));
            liveDocument = session.getDocument(new IdRef(
                    sourceDocument.getSourceId()));
        }
    }

    protected class ProxyCleaner extends UnrestrictedSessionRunner {

        DocumentModel proxy;

        public ProxyCleaner(CoreSession session, DocumentModel proxy) {
            super(session);
            this.proxy = proxy;
        }

        @Override
        public void run() throws ClientException {
            DocumentModel sourceVersion = session.getSourceDocument(proxy.getRef());
            DocumentModel dm = session.getSourceDocument(sourceVersion.getRef());
            DocumentModelList brothers = session.getProxies(dm.getRef(),
                    proxy.getParentRef());
            if (brothers != null && brothers.size() > 1) {
                // we remove the brothers of the published document if any
                // the use case is:
                // v1 is published, v2 is waiting for publication and was just
                // validated
                // v1 is removed and v2 is now being published
                for (DocumentModel doc : brothers) {
                    if (!doc.getId().equals(proxy.getId())) {
                        session.removeDocument(doc.getRef());
                    }
                }
            }
        }

    }

    /**
     * @author arussel
     */
    protected class DocumentPublisherUnrestricted extends
            UnrestrictedSessionRunner {

        protected PublishedDocument result;

        protected DocumentRef docRef;

        protected DocumentRef targetRef;

        protected NuxeoPrincipal principal;

        protected String comment = "";

        public DocumentPublisherUnrestricted(CoreSession session,
                DocumentRef docRef, DocumentRef targetRef,
                NuxeoPrincipal principal, String comment) {
            super(session);
            this.docRef = docRef;
            this.targetRef = targetRef;
            this.principal = principal;
            this.comment = comment;
        }

        public PublishedDocument getPublishedDocument() {
            return result;
        }

        @Override
        public void run() throws ClientException {
            DocumentModelList list = session.getProxies(docRef, targetRef);
            if (list.isEmpty()) {// first publication
                DocumentModel proxy = session.publishDocument(
                        session.getDocument(docRef),
                        session.getDocument(targetRef));
                SimpleCorePublishedDocument publishedDocument = new SimpleCorePublishedDocument(
                        proxy);
                session.save();
                if (!isValidator(proxy, principal)) {
                    notifyEvent(PublishingEvent.documentWaitingPublication,
                            coreSession.getDocument(proxy.getRef()),
                            coreSession);
                    restrictPermission(proxy, principal, session, null);
                    createTask(proxy, coreSession, principal);
                    publishedDocument.setPending(true);
                } else {
                    notifyEvent(PublishingEvent.documentPublished, proxy,
                            coreSession);
                }
                result = publishedDocument;
            } else if (list.size() == 1) {
                // one doc is already published or waiting for publication
                if (isPublishedDocWaitingForPublication(list.get(0), session)) {
                    DocumentModel proxy = session.publishDocument(
                            session.getDocument(docRef),
                            session.getDocument(targetRef));
                    if (!isValidator(proxy, principal)) {
                        // we're getting the old proxy acl
                        ACL acl = list.get(0).getACP().getACL(ACL_NAME);
                        acl.add(0, new ACE(principal.getName(),
                                SecurityConstants.READ, true));
                        ACP acp = proxy.getACP();
                        acp.addACL(acl);
                        session.setACP(proxy.getRef(), acp, true);
                        session.save();
                        SimpleCorePublishedDocument publishedDocument = new SimpleCorePublishedDocument(
                                proxy);
                        publishedDocument.setPending(true);
                        result = publishedDocument;
                    } else {
                        endTask(proxy, principal, coreSession, "",
                                PublishingEvent.documentPublicationApproved);
                        notifyEvent(PublishingEvent.documentPublished, proxy,
                                coreSession);
                        ACP acp = proxy.getACP();
                        acp.removeACL(ACL_NAME);
                        session.setACP(proxy.getRef(), acp, true);
                        session.save();
                        result = new SimpleCorePublishedDocument(proxy);
                    }
                } else {
                    if (!isValidator(list.get(0), principal)) {
                        DocumentModel proxy = session.publishDocument(
                                session.getDocument(docRef),
                                session.getDocument(targetRef), false);
                        // save needed to have the proxy visible from other
                        // sessions in non-JCA mode
                        session.save();
                        SimpleCorePublishedDocument publishedDocument = new SimpleCorePublishedDocument(
                                proxy);
                        notifyEvent(PublishingEvent.documentWaitingPublication,
                                proxy, coreSession);
                        restrictPermission(proxy, principal, coreSession, null);
                        session.save(); // process invalidations (non-JCA)
                        createTask(proxy, coreSession, principal);
                        publishedDocument.setPending(true);
                        result = publishedDocument;
                    } else {
                        DocumentModel proxy = session.publishDocument(
                                session.getDocument(docRef),
                                session.getDocument(targetRef));
                        // save needed to have the proxy visible from other
                        // sessions in non-JCA mode
                        session.save();
                        notifyEvent(PublishingEvent.documentPublished, proxy,
                                coreSession);
                        SimpleCorePublishedDocument publishedDocument = new SimpleCorePublishedDocument(
                                proxy);
                        result = publishedDocument;
                    }
                }
            } else if (list.size() == 2) {
                DocumentModel waitingForPublicationDoc = null;
                session.save(); // process invalidations (non-JCA)
                for (DocumentModel dm : list) {
                    if (session.getACP(dm.getRef()).getACL(ACL_NAME) != null) {
                        waitingForPublicationDoc = dm;
                    }
                }
                if (!isValidator(waitingForPublicationDoc, principal)) {
                    // we're getting the old proxy acl
                    ACL acl = waitingForPublicationDoc.getACP().getACL(ACL_NAME);
                    acl.add(0, new ACE(principal.getName(),
                            SecurityConstants.READ, true));
                    // remove publishedDoc
                    ACP acp = session.getACP(waitingForPublicationDoc.getRef());
                    acp.addACL(acl);
                    session.setACP(waitingForPublicationDoc.getRef(), acp, true);
                    SimpleCorePublishedDocument publishedDocument = new SimpleCorePublishedDocument(
                            waitingForPublicationDoc);
                    publishedDocument.setPending(true);
                    result = publishedDocument;
                } else {
                    endTask(waitingForPublicationDoc, principal, coreSession,
                            comment,
                            PublishingEvent.documentPublicationApproved);
                    session.removeDocument(waitingForPublicationDoc.getRef());
                    DocumentModel proxy = session.publishDocument(
                            session.getDocument(docRef),
                            session.getDocument(targetRef));
                    notifyEvent(PublishingEvent.documentPublished, proxy,
                            coreSession);
                    SimpleCorePublishedDocument publishedDocument = new SimpleCorePublishedDocument(
                            proxy);
                    result = publishedDocument;
                }
            }
        }

    }

}