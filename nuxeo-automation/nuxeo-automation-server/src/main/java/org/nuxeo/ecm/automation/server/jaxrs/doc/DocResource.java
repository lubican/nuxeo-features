/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     bstefanescu
 */
package org.nuxeo.ecm.automation.server.jaxrs.doc;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.StringUtils;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationDocumentation;
import org.nuxeo.ecm.automation.core.trace.Trace;
import org.nuxeo.ecm.automation.core.trace.TracerFactory;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.webengine.WebEngine;
import org.nuxeo.ecm.webengine.jaxrs.context.RequestContext;
import org.nuxeo.ecm.webengine.jaxrs.views.TemplateView;
import org.nuxeo.runtime.api.Framework;


/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
public class DocResource {

    private final Log log = LogFactory.getLog(DocResource.class);

    protected AutomationService service;

    protected List<OperationDocumentation> ops;

    public DocResource() {
        try {
            service = Framework.getService(AutomationService.class);
            ops = service.getDocumentation();
        } catch (Exception e) {
            log.error("Failed to get automation service", e);
            throw new WebApplicationException(e, 500);
        }
    }

    protected TemplateView getTemplate() {
        return getTemplate("index.ftl");
    }

    protected TemplateView getTemplate(String name) {
        Map<String, List<OperationDocumentation>> cats = new LinkedHashMap<String, List<OperationDocumentation>>();
        for (OperationDocumentation op : ops) {
            List<OperationDocumentation> list = cats.get(op.getCategory());
            if (list == null) {
                list = new ArrayList<OperationDocumentation>();
                cats.put(op.getCategory(), list);
            }
            list.add(op);
        }
        return new TemplateView(this, name).arg("categories", cats).arg(
                "operations", ops);
    }

    @GET
    @Produces("text/html")
    public Object doGet(@QueryParam("id")
            String id) {
        if (id == null) {
            return getTemplate();
        } else {
            OperationDocumentation opDoc = null;
            for (OperationDocumentation op : ops) {
                if (op.getId().equals(id)) {
                    opDoc = op;
                    break;
                }
            }
            if (opDoc == null) {
                throw new WebApplicationException(Response.status(404).build());
            }
            TemplateView tpl = getTemplate();
            tpl.arg("operation", opDoc);
            return tpl;
        }
    }

    protected boolean canManageTraces() {
        return ((NuxeoPrincipal)WebEngine.getActiveContext().getPrincipal()).isAdministrator();
    }

    @GET
    @Path("wiki")
    @Produces("text/html")
    public Object doGetWiki() {
        return getTemplate("wiki.ftl");
    }

    public boolean isTraceEnabled() {
        TracerFactory tracerFactory = Framework.getLocalService(TracerFactory.class);
        return tracerFactory.getRecordingState();
    }

    @GET
    @Path("toggleTraces")
    @Produces("text/html")
    public Object toggleTraces() throws Exception {
        if (!canManageTraces()) {
            return "You can not manage traces";
        }
        TracerFactory tracerFactory = Framework.getLocalService(TracerFactory.class);
        tracerFactory.toggleRecording();
        HttpServletRequest request = RequestContext.getActiveContext().getRequest();
        String url = request.getHeader("Referer");
        return Response.seeOther(new URI(url)).build();
    }

    @GET
    @Path("traces")
    @Produces("text/plain")
    public String doGetTrace(@QueryParam("opId") String opId) {
        if (!canManageTraces()) {
            return "You can not manage traces";
        }
        TracerFactory tracerFactory = Framework.getLocalService(TracerFactory.class);
        Trace trace = tracerFactory.getTrace(opId);
        if (trace!=null) {
            return tracerFactory.getTrace(opId).getFormattedText();
        } else {
            return "no trace";
        }
    }

    public String[] getInputs(OperationDocumentation op) {
        if (op == null) {
            throw new IllegalArgumentException("Operation must not be null");
        }
        if (op.signature == null || op.signature.length == 0) {
            return new String[0];
        }
        String[] result = new String[op.signature.length / 2];
        for (int i = 0, k = 0; i < op.signature.length; i += 2, k++) {
            result[k] = op.signature[i];
        }
        return result;
    }

    public String[] getOutputs(OperationDocumentation op) {
        if (op == null) {
            throw new IllegalArgumentException("Operation must not be null");
        }
        if (op.signature == null || op.signature.length == 0) {
            return new String[0];
        }
        String[] result = new String[op.signature.length / 2];
        for (int i = 1, k = 0; i < op.signature.length; i += 2, k++) {
            result[k] = op.signature[i];
        }
        return result;
    }

    public String getInputsAsString(OperationDocumentation op) {
        String[] result = getInputs(op);
        if (result == null || result.length == 0) {
            return "void";
        }
        return StringUtils.join(result, ", ");
    }

    public String getOutputsAsString(OperationDocumentation op) {
        String[] result = getOutputs(op);
        if (result == null || result.length == 0) {
            return "void";
        }
        return StringUtils.join(result, ", ");
    }

    public String getParamDefaultValue(OperationDocumentation.Param param) {
        if (param.values != null && param.values.length > 0) {
            return StringUtils.join(param.values, ", ");
        }
        return "";
    }

    public boolean hasOperation(OperationDocumentation op) {
        if (op == null) {
            throw new IllegalArgumentException("Operation must not be null");
        }
        if (op.getOperations() == null || op.getOperations().length == 0) {
            return false;
        }
        return true;
    }
}
