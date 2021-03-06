/*
 * (C) Copyright 2006-2007 Nuxeo SAS <http://nuxeo.com> and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Jean-Marc Orliaguet, Chalmers
 *
 * $Id$
 */

package org.nuxeo.theme.webwidgets;

public class WidgetException extends Exception {

    private static final long serialVersionUID = 1L;

    public WidgetException() {
    }

    public WidgetException(String message) {
        super(message);
    }

    public WidgetException(String message, Throwable cause) {
        super(message, cause);
    }

    public WidgetException(Throwable cause) {
        super(cause);
    }

}
