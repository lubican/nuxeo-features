package org.nuxeo.opensocial.container.client.event.priv.service;

import com.google.gwt.event.shared.EventHandler;

/**
 * @author Stéphane Fourrier
 */
public interface WebContentsLoadedEventHandler extends EventHandler {
    void onWebContentsLoaded(WebContentsLoadedEvent event);
}