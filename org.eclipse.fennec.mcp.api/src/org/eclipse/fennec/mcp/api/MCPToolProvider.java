/*
 * ******************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation.
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *   Data In Motion Consulting - initial implementation
 * ******************************************************************
 */
package org.eclipse.fennec.mcp.api;

import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

import io.modelcontextprotocol.server.McpServerFeatures.AsyncToolSpecification;

/**
 * Whiteboard aggregator that collects multiple {@link MCPTool} services and
 * converts them into MCP SDK {@link AsyncToolSpecification} objects.
 * <p>
 * Tool selection is controlled via LDAP target filters in the configuration,
 * allowing different providers to serve different subsets of tools.
 *
 * @author ilenia
 * @since Dec 3, 2025
 */
@ProviderType
public interface MCPToolProvider {

	/**
	 * Returns all collected tools as async MCP tool specifications ready
	 * for registration with the MCP server.
	 * @return list of async tool specifications, never {@code null}
	 */
	List<AsyncToolSpecification> getMCPTools();

	/**
	 * @return a human-readable description of what kinds of tools this provider collects
	 */
	String getDescription();

	/**
	 * Adds a listener notified when the provider's tool set changes after startup
	 * (dynamically registered/unregistered {@code MCPTool} services). Servers use
	 * it to propagate changes to connected MCP clients via
	 * {@code notifications/tools/list_changed}. Providers with a static tool set
	 * may ignore it.
	 * <p>
	 * Every listener added is notified: one provider can be bound by more than one
	 * server, and a server that stopped being told about changes would serve the
	 * tool list it saw at its activation for the rest of its life, silently.
	 * Implementations should therefore keep all listeners, not just the last one.
	 * Adding the same listener twice is not required to notify it twice.
	 *
	 * @param listener the change listener, ignored if {@code null}
	 */
	default void onToolsChanged(Runnable listener) {
		// static providers: nothing to notify
	}

	/**
	 * Removes a listener added by {@link #onToolsChanged(Runnable)}, identified by
	 * equality. A server has to call this when it deactivates: otherwise the
	 * provider holds on to a listener that keeps calling into a server whose MCP
	 * session is gone, and a runtime whose configuration is updated repeatedly
	 * accumulates one per activation.
	 *
	 * @param listener the change listener to remove; unknown or {@code null}
	 *            listeners are ignored
	 */
	default void removeToolsChangedListener(Runnable listener) {
		// static providers: nothing was ever registered
	}
}
