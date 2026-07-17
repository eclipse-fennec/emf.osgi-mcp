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

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration of the {@link DatasetRegistry} resource limits.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@ObjectClassDefinition(name = "EMF Dataset Registry", description = "Session store and resource limits for the EMF model MCP tools")
public @interface DatasetRegistryConfig {

	@AttributeDefinition(name = "Max datasets per session")
	int max_datasets_per_session() default 16;

	@AttributeDefinition(name = "Max objects per dataset")
	int max_objects_per_dataset() default 10_000;

	@AttributeDefinition(name = "Max recipe operations per dataset")
	int max_recipe_ops() default 100_000;

	@AttributeDefinition(name = "Max characters of a single feature value")
	int max_value_chars() default 65_536;

	@AttributeDefinition(name = "Max bytes of a declarative JSON payload")
	int max_json_payload_bytes() default 1_048_576;

	@AttributeDefinition(name = "Max bytes returned inline by an export; larger exports return a descriptor only")
	int max_inline_export_bytes() default 65_536;

	@AttributeDefinition(name = "Idle minutes after which a session store is evicted")
	int session_ttl_minutes() default 120;
}
