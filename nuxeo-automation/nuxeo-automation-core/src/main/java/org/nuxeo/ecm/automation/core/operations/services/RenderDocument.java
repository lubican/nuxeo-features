/*
 * (C) Copyright 2006-2008 Nuxeo SAS (http://nuxeo.com/) and contributors.
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
 *     bstefanescu
 */
package org.nuxeo.ecm.automation.core.operations.services;

import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.collectors.BlobCollector;
import org.nuxeo.ecm.automation.core.rendering.RenderingService;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.impl.blob.StringBlob;
import org.nuxeo.runtime.services.resource.ResourceService;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 */
@Operation(id = RenderDocument.ID, category = Constants.CAT_SERVICES, label = "Render Document", description = "Get a document or a list of document in input and outputs one or more blobs that contain a rendered view for each input document given a rendering template. The template attribute may contain either the template content either a template URI. Template URis are strings in the form 'resource:template_name' and will be located using the runtime resource service. Return the rendered file(s) as blob(s)")
public class RenderDocument {

    public static final String ID = "Render.Document";

    @Context
    protected ResourceService rs;

    @Context
    protected OperationContext ctx;

    @Param(name = "template", widget = Constants.W_TEMPLATE_RESOURCE)
    protected String template;

    @Param(name = "type", widget = Constants.W_OPTION, required = false, values = {"ftl", "mvel"})
    protected String type = "ftl";

    @OperationMethod(collector = BlobCollector.class)
    public Blob run(DocumentModel doc) throws Exception {
        String content = RenderingService.getInstance().render(type, template, ctx);
        StringBlob blob = new StringBlob(content);
        return blob;
    }

}