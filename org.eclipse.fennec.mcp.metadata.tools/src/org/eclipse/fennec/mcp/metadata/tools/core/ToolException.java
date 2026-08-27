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
package org.eclipse.fennec.mcp.metadata.tools.core;

/**
 * Exception carrying a sanitized, agent-facing error message. Messages of this
 * exception are intentionally safe to return to an MCP client (no stack traces,
 * no internal paths or class names) and should help the agent to self-correct.
 * <p>
 * Deliberately a copy of the equally named type in
 * {@code org.eclipse.fennec.mcp.emf.tools.core} rather than a shared dependency:
 * that package is private to its bundle, and exporting it only to share a
 * three-line exception would turn an internal helper into API.
 *
 * @author ilenia
 * @since Aug 26, 2026
 */
public class ToolException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ToolException(String message) {
		super(message);
	}
}
