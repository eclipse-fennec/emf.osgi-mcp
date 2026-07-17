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
package org.eclipse.fennec.mcp.emf.tools.core;

/**
 * Exception carrying a sanitized, agent-facing error message. Messages of this
 * exception are intentionally safe to return to an MCP client (no stack traces,
 * no internal paths or class names) and should help the agent to self-correct.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
public class ToolException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public ToolException(String message) {
		super(message);
	}
}
