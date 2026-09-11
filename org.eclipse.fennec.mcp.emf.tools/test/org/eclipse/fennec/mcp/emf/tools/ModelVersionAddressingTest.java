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
package org.eclipse.fennec.mcp.emf.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.eclipse.emf.ecore.EcorePackage;
import org.eclipse.emf.ecore.impl.EPackageRegistryImpl;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.emf.osgi.metadata.MetadataService;
import org.eclipse.fennec.emf.osgi.metadata.MetadataServices;
import org.eclipse.fennec.emf.osgi.metadata.MetadataWhiteboard;
import org.eclipse.fennec.emf.osgi.model.metadata.PackageMetadata;
import org.eclipse.fennec.mcp.api.AnnotationVisibility;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetLimits;
import org.eclipse.fennec.mcp.emf.tools.core.DatasetRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.ModelGuard;
import org.eclipse.fennec.mcp.emf.tools.core.PackageRegistry;
import org.eclipse.fennec.mcp.emf.tools.core.TestModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/**
 * Addressing a model <em>version</em> from the EMF model tools.
 * <p>
 * The package registry these tools read through maps one nsURI to one package,
 * so it cannot represent two registered versions of a namespace and cannot
 * report that a second exists. The fingerprint resolves through the metadata
 * layer instead, which is keyed by version — so the decisive case here is
 * reaching a version the registry does <b>not</b> hold, and refusing the
 * nsURI-only reads that would otherwise answer about the one it does.
 *
 * @author ilenia
 * @since Sep 11, 2026
 */
class ModelVersionAddressingTest {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	@TempDir
	Path workDir;

	private EPackage.Registry packageRegistry;
	private EPackage registryVersion;
	private MetadataWhiteboard whiteboard;
	private String registeredFingerprint;
	private String unregisteredFingerprint;

	@BeforeEach
	void setUp() {
		EPackage inRegistry = TestModels.libraryPackage();
		EPackage notInRegistry = diverged(TestModels.libraryPackage());

		// The registry holds one of the two, as it always does: one package per nsURI.
		packageRegistry = TestModels.registryWith(inRegistry);
		registryVersion = inRegistry;

		whiteboard = MetadataServices.createWhiteboard();
		registeredFingerprint = fingerprintOf(inRegistry);
		unregisteredFingerprint = fingerprintOf(notInRegistry);
	}

	/** Same namespace, one extra attribute, therefore a different fingerprint. */
	private static EPackage diverged(EPackage library) {
		EClass book = (EClass) library.getEClassifier("Book");
		EAttribute added = EcoreFactory.eINSTANCE.createEAttribute();
		added.setName("isbn");
		added.setEType(EcorePackage.Literals.ESTRING);
		book.getEStructuralFeatures().add(added);
		return library;
	}

	private String fingerprintOf(EPackage ePackage) {
		return whiteboard.registerPackage(ePackage).map(PackageMetadata::getModelFingerprint).orElseThrow();
	}

	@Test
	void theFixtureHasTwoVersionsOfWhichTheRegistryHoldsOne() {
		assertThat(registeredFingerprint).isNotEqualTo(unregisteredFingerprint);
		assertThat(whiteboard.getPackageMetadataVersions(TestModels.NS_URI)).hasSize(2);
		assertThat(packageRegistry.getEPackage(TestModels.NS_URI)).isNotNull();
	}

	// ---- the refusal ----

	@Test
	void listMetamodelRefusesAnNsUriWithSeveralVersions() {
		String error = errorFrom(listMetamodel(guardWithMetadata()), Map.of("nsURI", TestModels.NS_URI));

		assertThat(error)
				.contains("holds 2 registered model versions")
				.contains("will not guess")
				.contains(registeredFingerprint)
				.contains(unregisteredFingerprint);
	}

	@Test
	void describeEClassRefusesAnAmbiguousClassIdentifier() {
		assertThat(errorFrom(describeEClass(guardWithMetadata()), Map.of("eClass", TestModels.BOOK)))
				.contains("holds 2 registered model versions");
	}

	// ---- reaching the version the registry does not hold ----

	@Test
	void aFingerprintReachesTheVersionThatIsNotInTheRegistry() {
		Map<String, Object> result = call(listMetamodel(guardWithMetadata()),
				Map.of("fingerprint", unregisteredFingerprint));

		assertThat(result).containsEntry("modelFingerprint", unregisteredFingerprint);
		assertThat(result).containsEntry("nsURI", TestModels.NS_URI);
	}

	@Test
	void describeEClassReadsTheAddressedVersionsFeatures() {
		Map<String, Object> result = call(describeEClass(guardWithMetadata()),
				Map.of("eClass", TestModels.BOOK, "fingerprint", unregisteredFingerprint));

		assertThat(result).containsEntry("modelFingerprint", unregisteredFingerprint);
		// 'isbn' exists only in the diverged version — proof the read followed the
		// fingerprint rather than the package the registry holds under this nsURI.
		assertThat(MAPPER.writeValueAsString(result)).contains("isbn");

		Map<String, Object> registered = call(describeEClass(guardWithMetadata()),
				Map.of("eClass", TestModels.BOOK, "fingerprint", registeredFingerprint));
		assertThat(MAPPER.writeValueAsString(registered)).doesNotContain("isbn");
	}

	@Test
	void aFingerprintFromAnotherNamespaceIsRefused() {
		assertThat(errorFrom(listMetamodel(guardWithMetadata()), Map.of(
				"nsURI", TestModels.UPLINK_NS_URI,
				"fingerprint", registeredFingerprint)))
				.contains("identifies a model version of namespace");
	}

	@Test
	void anUnknownFingerprintIsRefused() {
		assertThat(errorFrom(listMetamodel(guardWithMetadata()), Map.of("fingerprint", "fp1:not-registered")))
				.contains("No model version with fingerprint");
	}

	// ---- without the metadata layer, nothing changes ----

	@Test
	void anNsUriStillAnswersWithNoMetadataLayerDeployed() {
		Map<String, Object> result = call(listMetamodel(guardWithoutMetadata()), Map.of("nsURI", TestModels.NS_URI));

		assertThat(result).containsEntry("nsURI", TestModels.NS_URI);
		assertThat(result.get("modelFingerprint")).isNull();
	}

	@Test
	void aFingerprintSaysWhyItCannotBeResolvedWithNoMetadataLayer() {
		assertThat(errorFrom(listMetamodel(guardWithoutMetadata()), Map.of("fingerprint", registeredFingerprint)))
				.contains("no metadata layer deployed");
	}

	// ---- export refuses to hand out the wrong version ----

	@Test
	void exportRefusesWhenTheSessionHoldsADifferentVersionOfTheNamespace() throws Exception {
		// The session owns a package for this namespace, and export_package prefers it -
		// correctly, it is the agent's own model. But it is not the version asked for.
		PackageRegistry sessionPackages = sessionRegistryHolding(registryVersion);
		ExportPackageTool tool = exportTool(guardWithMetadata(), sessionPackages);

		String error = errorFrom(tool, Map.of(
				"nsURI", TestModels.NS_URI,
				"fingerprint", unregisteredFingerprint));

		assertThat(error)
				.contains("resolves here to model version")
				.contains("nothing was exported")
				.contains(unregisteredFingerprint);
	}

	@Test
	void exportReportsTheVersionItActuallyWrote() throws Exception {
		ExportPackageTool tool = exportTool(guardWithMetadata(), sessionRegistryHolding(registryVersion));

		Map<String, Object> result = call(tool, Map.of(
				"nsURI", TestModels.NS_URI,
				"fingerprint", registeredFingerprint));

		assertThat(result).containsEntry("modelFingerprint", registeredFingerprint);
		assertThat(String.valueOf(result.get("content"))).doesNotContain("isbn");
	}

	@Test
	void exportWithoutAFingerprintStillWorksForAnUnambiguousNamespace() throws Exception {
		ExportPackageTool tool = exportTool(guardWithMetadata(), sessionRegistryHolding(registryVersion));

		// The session path is unambiguous by construction - one package per nsURI per
		// session, and it is the session's own - so it is not subject to the refusal.
		assertThat(call(tool, Map.of("nsURI", TestModels.NS_URI)))
				.containsEntry("modelFingerprint", registeredFingerprint);
	}

	// ---- plumbing ----

	private ExportPackageTool exportTool(ModelGuard guard, PackageRegistry sessionPackages) throws Exception {
		ExportPackageTool tool = new ExportPackageTool();
		tool.guard = guard;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.packages = sessionPackages;
		tool.registry = datasetRegistry();
		tool.activate();
		return tool;
	}

	private PackageRegistry sessionRegistryHolding(EPackage ePackage) throws Exception {
		var constructor = PackageRegistry.class.getDeclaredConstructor(Set.class, Set.class, int.class);
		constructor.setAccessible(true);
		PackageRegistry registry = constructor.newInstance(Set.of("*"), Set.of(), 100);
		registry.register("session-1", ePackage);
		return registry;
	}

	private DatasetRegistry datasetRegistry() throws Exception {
		ResourceSetFactory factory = () -> {
			ResourceSetImpl resourceSet = new ResourceSetImpl();
			resourceSet.setPackageRegistry(new EPackageRegistryImpl(packageRegistry));
			return resourceSet;
		};
		var constructor = DatasetRegistry.class.getDeclaredConstructor(ResourceSetFactory.class, DatasetLimits.class, Path.class);
		constructor.setAccessible(true);
		return constructor.newInstance(factory, DatasetLimits.defaults(), workDir);
	}

	private ModelGuard guardWithMetadata() {
		ModelGuard guard = guardWithoutMetadata();
		set(guard, "metadata", whiteboard);
		return guard;
	}

	private ModelGuard guardWithoutMetadata() {
		try {
			var constructor = ModelGuard.class.getDeclaredConstructor(EPackage.Registry.class, Set.class, Set.class);
			constructor.setAccessible(true);
			return constructor.newInstance(packageRegistry, Set.of("*"), Set.of("*"));
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void set(ModelGuard guard, String field, MetadataService value) {
		try {
			var declared = ModelGuard.class.getDeclaredField(field);
			declared.setAccessible(true);
			declared.set(guard, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ListMetamodelTool listMetamodel(ModelGuard guard) {
		ListMetamodelTool tool = new ListMetamodelTool();
		tool.guard = guard;
		tool.activate();
		return tool;
	}

	private static DescribeEClassTool describeEClass(ModelGuard guard) {
		DescribeEClassTool tool = new DescribeEClassTool();
		tool.guard = guard;
		tool.visibility = AnnotationVisibility.unrestricted();
		tool.activate();
		return tool;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> call(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange(), arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).as("tool error: %s", text(result)).isNotEqualTo(Boolean.TRUE);
		return MAPPER.readValue(text(result), Map.class);
	}

	private static String errorFrom(AbstractEMFTool tool, Map<String, Object> arguments) {
		McpSchema.CallToolResult result = tool.execute(exchange(), arguments).block();
		assertThat(result).isNotNull();
		assertThat(result.isError()).isEqualTo(Boolean.TRUE);
		return text(result);
	}

	private static McpAsyncServerExchange exchange() {
		McpAsyncServerExchange exchange = mock(McpAsyncServerExchange.class);
		when(exchange.sessionId()).thenReturn("session-1");
		return exchange;
	}

	private static String text(McpSchema.CallToolResult result) {
		List<McpSchema.Content> content = result.content();
		return ((McpSchema.TextContent) content.get(0)).text();
	}
}
