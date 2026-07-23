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
package org.eclipse.fennec.mcp.service.tools;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.api.MCPTool;
import org.eclipse.fennec.model.metadata.api.MetadataWhiteboard;
import org.eclipse.fennec.service.api.ServiceClient;
import org.eclipse.fennec.service.api.ServiceOperation;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Whiteboard bridge from {@link ServiceClient} services (imported
 * SOAP/OpenAPI/gRPC documents, see the emf.util repo) to {@link MCPTool}
 * services: one tool per allow-listed {@link ServiceOperation}, registered
 * with a {@code tool.namespace=service-bridge} marker so a dedicated
 * {@code MCPToolProvider} instance can aggregate them without enumerating
 * tool names.
 * <p>
 * Deny-all by default: the {@code clients.target} reference filter selects
 * the bridged clients, {@code operations.allow} the exposed operations.
 * Tool names are {@code <client>_<operation>} (sanitized); duplicates are
 * logged and skipped. The operations' EPackages are announced to the codec
 * {@link MetadataWhiteboard} (if present) so the JSON conversion works for
 * dynamically imported models, and retracted when the client goes away.
 * Authentication stays inside the {@code ServiceClient}; the bridge never
 * sees credentials.
 *
 * @author Mark Hoffmann
 * @since Jul 23, 2026
 */
@Component(name = "ServiceClientToolBridge", configurationPolicy = ConfigurationPolicy.REQUIRE)
@Designate(ocd = ServiceToolsConfig.class, factory = true)
public class ServiceClientToolBridge {

	private static final Logger LOGGER = Logger.getLogger(ServiceClientToolBridge.class.getName());

	/** Marker service property for the dedicated tool provider's target filter. */
	static final String TOOL_NAMESPACE = "service-bridge";

	/** Conversion and schema seams; the codec-backed default is created on activation. */
	interface SchemaGenerator {
		/** @return the JSON schema for the EClass, or an empty-object schema for {@code null} */
		String schemaFor(EClass eClass);
	}

	private final Map<ServiceClient, List<ServiceRegistration<MCPTool>>> registrations = new ConcurrentHashMap<>();
	private final Map<ServiceClient, Set<EPackage>> announcedPackages = new ConcurrentHashMap<>();
	private final Map<ServiceClient, Map<String, Object>> clients = new LinkedHashMap<>();
	private final Set<String> usedToolNames = ConcurrentHashMap.newKeySet();

	@Reference
	ResourceSetFactory resourceSetFactory;

	private volatile MetadataWhiteboard metadataWhiteboard;
	private volatile BundleContext context;
	private volatile ServiceToolsConfig config;
	private volatile String configName = "";

	SchemaGenerator schemaGenerator;
	ServiceOperationTool.PayloadCodec payloadCodec;

	@Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
	void setMetadataWhiteboard(MetadataWhiteboard whiteboard) {
		this.metadataWhiteboard = whiteboard;
		announcedPackages.values().forEach(packages -> packages.forEach(p -> announce(whiteboard, p)));
	}

	void unsetMetadataWhiteboard(MetadataWhiteboard whiteboard) {
		if (this.metadataWhiteboard == whiteboard) {
			this.metadataWhiteboard = null;
		}
	}

	@Reference(name = "clients", cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
	void addClient(ServiceClient client, Map<String, Object> properties) {
		synchronized (clients) {
			clients.put(client, properties);
			if (context != null) {
				registerTools(client, properties);
			}
		}
	}

	void removeClient(ServiceClient client) {
		synchronized (clients) {
			clients.remove(client);
			unregisterTools(client);
		}
	}

	@Activate
	void activate(BundleContext context, ServiceToolsConfig config, Map<String, Object> properties) {
		this.config = config;
		this.configName = instanceName(properties);
		if (schemaGenerator == null || payloadCodec == null) {
			CodecPayloads codec = new CodecPayloads(resourceSetFactory);
			this.schemaGenerator = codec;
			this.payloadCodec = codec;
		}
		synchronized (clients) {
			this.context = context;
			clients.forEach(this::registerTools);
		}
	}

	@Deactivate
	void deactivate() {
		synchronized (clients) {
			this.context = null;
			List.copyOf(registrations.keySet()).forEach(this::unregisterTools);
		}
	}

	private void registerTools(ServiceClient client, Map<String, Object> properties) {
		String prefix = toolPrefix(properties);
		List<ServiceRegistration<MCPTool>> registered = new ArrayList<>();
		Set<EPackage> packages = new HashSet<>();
		for (ServiceOperation operation : client.operations()) {
			if (!allowed(operation.name())) {
				continue;
			}
			String toolName = sanitize(prefix + "_" + operation.name());
			if (!usedToolNames.add(toolName)) {
				LOGGER.warning(() -> String.format("Skipping duplicate bridged tool name '%s' (operation '%s')", toolName, operation.name()));
				continue;
			}
			collectPackage(operation.requestType(), packages);
			collectPackage(operation.responseType(), packages);
			ServiceOperationTool tool = new ServiceOperationTool(toolName,
					String.format("Invokes the '%s' operation of service client '%s'", operation.name(), prefix),
					schemaGenerator.schemaFor(operation.requestType()),
					operation.responseType() == null ? null : schemaGenerator.schemaFor(operation.responseType()),
					client, operation, payloadCodec);
			registered.add(context.registerService(MCPTool.class, tool, toolProperties(tool)));
		}
		MetadataWhiteboard whiteboard = metadataWhiteboard;
		if (whiteboard != null) {
			packages.forEach(p -> announce(whiteboard, p));
		}
		announcedPackages.put(client, packages);
		registrations.put(client, registered);
		LOGGER.info(() -> String.format("Bridged %d operation(s) of service client '%s' as MCP tools", registered.size(), prefix));
	}

	private void unregisterTools(ServiceClient client) {
		List<ServiceRegistration<MCPTool>> registered = registrations.remove(client);
		if (registered != null) {
			for (ServiceRegistration<MCPTool> registration : registered) {
				usedToolNames.remove((String) registration.getReference().getProperty("tool.name"));
				try {
					registration.unregister();
				} catch (IllegalStateException alreadyUnregistered) {
					// bundle stop races the unbind — nothing left to do
				}
			}
		}
		Set<EPackage> packages = announcedPackages.remove(client);
		MetadataWhiteboard whiteboard = metadataWhiteboard;
		if (packages != null && whiteboard != null) {
			packages.forEach(p -> retract(whiteboard, p));
		}
	}

	private Dictionary<String, Object> toolProperties(ServiceOperationTool tool) {
		Hashtable<String, Object> properties = new Hashtable<>();
		properties.put("tool.name", tool.getName());
		properties.put("tool.description", tool.getDescription());
		properties.put("tool.namespace", TOOL_NAMESPACE);
		return properties;
	}

	private boolean allowed(String operationName) {
		for (String pattern : config.operations_allow()) {
			if (pattern.endsWith("*")) {
				if (operationName.startsWith(pattern.substring(0, pattern.length() - 1))) {
					return true;
				}
			} else if (pattern.equals(operationName)) {
				return true;
			}
		}
		return false;
	}

	private String toolPrefix(Map<String, Object> clientProperties) {
		String prefix = config.tools_prefix();
		if (prefix != null && !prefix.isBlank()) {
			return prefix;
		}
		if (clientProperties.get(ServiceClient.PROP_NAME) instanceof String name && !name.isBlank()) {
			return name;
		}
		return configName.isBlank() ? "service" : configName;
	}

	private static String instanceName(Map<String, Object> componentProperties) {
		Object pid = componentProperties.get(Constants.SERVICE_PID);
		if (pid instanceof String s && s.contains("~")) {
			return s.substring(s.lastIndexOf('~') + 1);
		}
		return "";
	}

	private static void collectPackage(EClass eClass, Set<EPackage> packages) {
		if (eClass != null && eClass.getEPackage() != null) {
			packages.add(eClass.getEPackage());
		}
	}

	private static void announce(MetadataWhiteboard whiteboard, EPackage ePackage) {
		try {
			whiteboard.registerPackage(ePackage);
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, e, () -> String.format("Could not announce package '%s' to the codec metadata service", ePackage.getNsURI()));
		}
	}

	private static void retract(MetadataWhiteboard whiteboard, EPackage ePackage) {
		try {
			whiteboard.unregisterPackage(ePackage);
		} catch (RuntimeException e) {
			LOGGER.log(Level.WARNING, e, () -> String.format("Could not retract package '%s' from the codec metadata service", ePackage.getNsURI()));
		}
	}

	static String sanitize(String name) {
		return name.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
	}
}
