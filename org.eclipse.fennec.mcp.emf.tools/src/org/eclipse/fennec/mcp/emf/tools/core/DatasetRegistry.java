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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.eclipse.fennec.emf.osgi.ResourceSetFactory;
import org.eclipse.fennec.mcp.emf.tools.runtime.DatasetDTO;
import org.eclipse.fennec.mcp.emf.tools.runtime.SessionDTO;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

/**
 * Session-scoped store of {@link Dataset}s. Datasets are keyed by the MCP
 * session id and are only ever visible to the session that created them
 * (ownership check on every access — a foreign session cannot address another
 * session's datasets, see the IDOR mitigation in the module plan). Dataset ids
 * are server-generated random UUIDs and therefore unguessable.
 * <p>
 * Idle sessions are evicted lazily on access after the configured TTL.
 *
 * @author Mark Hoffmann
 * @since Jun 12, 2026
 */
@Component(name = "EMFDatasetRegistry", service = DatasetRegistry.class, configurationPid = "EMFDatasetRegistry")
@Designate(ocd = DatasetRegistryConfig.class)
public class DatasetRegistry {

	private static final Logger LOGGER = Logger.getLogger(DatasetRegistry.class.getName());

	@Reference
	private ResourceSetFactory resourceSetFactory;

	private final Map<String, SessionStore> sessions = new ConcurrentHashMap<>();
	private volatile DatasetLimits limits = DatasetLimits.defaults();
	private volatile Path workDir = defaultWorkDir();
	private volatile Runnable changeListener;

	private record SessionStore(Map<String, Dataset> datasets, long[] lastAccess) {
		static SessionStore create() {
			return new SessionStore(new ConcurrentHashMap<>(), new long[] { System.currentTimeMillis() });
		}

		void touch() {
			lastAccess[0] = System.currentTimeMillis();
		}
	}

	public DatasetRegistry() {
		// default constructor for DS
	}

	/**
	 * Test constructor wiring the resource set factory and limits directly.
	 */
	DatasetRegistry(ResourceSetFactory resourceSetFactory, DatasetLimits limits) {
		this.resourceSetFactory = resourceSetFactory;
		this.limits = limits;
	}

	/**
	 * Test constructor additionally pinning the export working directory.
	 */
	DatasetRegistry(ResourceSetFactory resourceSetFactory, DatasetLimits limits, Path workDir) {
		this(resourceSetFactory, limits);
		this.workDir = workDir;
	}

	@Activate
	@Modified
	void activate(DatasetRegistryConfig config) {
		this.limits = new DatasetLimits(
				config.max_datasets_per_session(),
				config.max_objects_per_dataset(),
				config.max_recipe_ops(),
				config.max_value_chars(),
				config.max_json_payload_bytes(),
				config.max_inline_export_bytes(),
				config.session_ttl_minutes() * 60_000L);
		String configured = config.work_dir();
		this.workDir = configured == null || configured.isBlank() ? defaultWorkDir() : Path.of(configured);
	}

	private static Path defaultWorkDir() {
		// nested below the OS temp dir so session cleanup only ever deletes our own files
		return Path.of(System.getProperty("java.io.tmpdir"), "fennec-mcp-exports");
	}

	/**
	 * @return the configured resource limits
	 */
	public DatasetLimits limits() {
		return limits;
	}

	/**
	 * Sets the single change listener, notified whenever the set of sessions
	 * or datasets changes (create, delete, eviction). Used by the runtime
	 * introspection service to bump its {@code service.changecount}.
	 */
	public void onChange(Runnable listener) {
		this.changeListener = listener;
	}

	/**
	 * @return a per-session runtime snapshot (datasets and last access);
	 *         {@code registeredPackages} is left for the package registry
	 */
	public Map<String, SessionDTO> sessionSnapshots() {
		Map<String, SessionDTO> result = new TreeMap<>();
		sessions.forEach((sessionId, store) -> {
			SessionDTO session = new SessionDTO();
			session.sessionId = sessionId;
			session.lastAccess = store.lastAccess()[0];
			session.datasets = store.datasets().values().stream()
					.sorted(Comparator.comparingLong(Dataset::getCreatedAt))
					.map(DatasetRegistry::toDto)
					.toArray(DatasetDTO[]::new);
			result.put(sessionId, session);
		});
		return result;
	}

	private static DatasetDTO toDto(Dataset dataset) {
		DatasetDTO dto = new DatasetDTO();
		dto.datasetId = dataset.getId();
		dto.objectCount = dataset.objectCount();
		dto.recipeSize = dataset.recipeSize();
		dto.createdAt = dataset.getCreatedAt();
		dto.lastAccess = dataset.getLastAccess();
		return dto;
	}

	private void notifyChanged() {
		Runnable listener = changeListener;
		if (listener != null) {
			listener.run();
		}
	}

	/**
	 * Writes an export exceeding the inline cap into the working directory.
	 * Files live in a per-session subdirectory (hashed session id, so foreign
	 * session ids can never traverse outside the directory) and are removed
	 * when their dataset is deleted or the session is evicted.
	 *
	 * @param sessionId the owning MCP session id
	 * @param datasetId the dataset id (server-generated UUID)
	 * @param format    the export format extension ({@code xmi} or {@code json})
	 * @param content   the serialized export
	 * @return the absolute path of the written file
	 * @throws ToolException if the file cannot be written
	 */
	public Path storeExport(String sessionId, String datasetId, String format, String content) {
		Path sessionDir = workDir.resolve(sessionDirName(requireSession(sessionId)));
		Path file = sessionDir.resolve(datasetId + "." + format);
		try {
			Files.createDirectories(sessionDir);
			Files.writeString(file, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ToolException(String.format("Could not write the export to the working directory '%s': %s", workDir, e.getMessage()));
		}
		return file.toAbsolutePath();
	}

	/**
	 * Creates a new dataset for the given session.
	 *
	 * @param sessionId the MCP session id
	 * @param seed      optional reproducibility seed stored with the dataset
	 * @return the new dataset
	 * @throws ToolException if the per-session dataset cap is reached
	 */
	public Dataset create(String sessionId, Long seed) {
		evictExpired();
		SessionStore store = sessions.computeIfAbsent(requireSession(sessionId), s -> SessionStore.create());
		store.touch();
		// Cap check and insert must be atomic: a session may fire concurrent tool
		// calls on the multi-threaded reactive scheduler, so a plain size()-then-put
		// would let two calls both pass the check and exceed the cap.
		synchronized (store) {
			if (store.datasets().size() >= limits.maxDatasetsPerSession()) {
				throw new ToolException(String.format("Session dataset limit of %d reached. Delete a dataset with manage_dataset first.",
						limits.maxDatasetsPerSession()));
			}
			Dataset dataset = new Dataset(UUID.randomUUID().toString(), resourceSetFactory.createResourceSet(), seed);
			store.datasets().put(dataset.getId(), dataset);
			LOGGER.info(() -> String.format("Created dataset '%s' (%d in session)", dataset.getId(), store.datasets().size()));
			notifyChanged();
			return dataset;
		}
	}

	/**
	 * Resolves a dataset of the given session. Only datasets owned by the
	 * session are visible.
	 *
	 * @param sessionId the MCP session id
	 * @param datasetId the dataset id
	 * @return the dataset, never {@code null}
	 * @throws ToolException if the dataset does not exist in this session
	 */
	public Dataset require(String sessionId, String datasetId) {
		evictExpired();
		SessionStore store = sessions.get(requireSession(sessionId));
		Dataset dataset = store == null ? null : store.datasets().get(datasetId);
		if (dataset == null) {
			throw new ToolException(String.format("Unknown datasetId '%s' in this session. Use inspect_dataset to list your datasets.", datasetId));
		}
		store.touch();
		dataset.touch();
		return dataset;
	}

	/**
	 * @param sessionId the MCP session id
	 * @return the session's datasets ordered by creation time
	 */
	public List<Dataset> list(String sessionId) {
		evictExpired();
		SessionStore store = sessions.get(requireSession(sessionId));
		if (store == null) {
			return List.of();
		}
		store.touch();
		return store.datasets().values().stream().sorted(Comparator.comparingLong(Dataset::getCreatedAt)).toList();
	}

	/**
	 * Deletes a dataset of the given session.
	 *
	 * @return {@code true} if the dataset existed and was removed
	 */
	public boolean delete(String sessionId, String datasetId) {
		SessionStore store = sessions.get(requireSession(sessionId));
		if (store == null) {
			return false;
		}
		store.touch();
		Dataset removed = store.datasets().remove(datasetId);
		if (removed != null) {
			removed.reset();
			deleteExports(sessionId, datasetId);
			LOGGER.info(() -> String.format("Deleted dataset '%s'", datasetId));
			notifyChanged();
			return true;
		}
		return false;
	}

	private String requireSession(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new ToolException("No MCP session available. Dataset tools require a session-aware MCP connection.");
		}
		return sessionId;
	}

	private void evictExpired() {
		long now = System.currentTimeMillis();
		boolean[] evicted = { false };
		sessions.entrySet().removeIf(entry -> {
			boolean expired = now - entry.getValue().lastAccess()[0] > limits.sessionTtlMillis();
			if (expired) {
				LOGGER.fine(() -> String.format("Evicting %d expired dataset(s) of an idle MCP session", entry.getValue().datasets().size()));
				entry.getValue().datasets().values().forEach(Dataset::reset);
				deleteSessionDir(entry.getKey());
				evicted[0] = true;
			}
			return expired;
		});
		if (evicted[0]) {
			notifyChanged();
		}
	}

	private void deleteExports(String sessionId, String datasetId) {
		Path sessionDir = workDir.resolve(sessionDirName(sessionId));
		try {
			Files.deleteIfExists(sessionDir.resolve(datasetId + ".xmi"));
			Files.deleteIfExists(sessionDir.resolve(datasetId + ".json"));
			// drop the session dir once its last export is gone
			try (Stream<Path> remaining = Files.exists(sessionDir) ? Files.list(sessionDir) : Stream.empty()) {
				if (remaining.findAny().isEmpty()) {
					Files.deleteIfExists(sessionDir);
				}
			}
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, e, () -> String.format("Could not clean up export files of dataset '%s'", datasetId));
		}
	}

	private void deleteSessionDir(String sessionId) {
		Path sessionDir = workDir.resolve(sessionDirName(sessionId));
		if (!Files.exists(sessionDir)) {
			return;
		}
		try (Stream<Path> tree = Files.walk(sessionDir)) {
			for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, e, () -> "Could not clean up the export directory of an evicted MCP session");
		}
	}

	private static String sessionDirName(String sessionId) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(sessionId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
		}
	}
}
