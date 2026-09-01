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
package org.eclipse.fennec.mcp.inference.runtime;

/**
 * Constants for the metamodel-inference MCP capability/requirement namespace.
 * Used to declare that a bundle provides or requires the inference MCP
 * integration: the task-scoped tool set behind {@code /mcp/inference} together
 * with the model.atlas publishing tool it hands its result to.
 */
public interface InferenceMCPConstants {

	/** Implementation capability name for the inference MCP integration. */
	public static final String MCP_INFERENCE_IMPLEMENTATION = "mcp.inference";
	/** Version of the inference MCP capability. */
	public static final String MCP_INFERENCE_VERSION = "1.0";
	/** Server name of the inference MCP server, as configured in inference.config. */
	public static final String INFERENCE_SERVER_NAME = "osgi-emf-inference-mcp-server";

}
