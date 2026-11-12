package r3.graffiti

import r3.content.BinaryContent
import r3.content.Content
import r3.io.DEFAULT_COPY_BUFFER_SIZE
import r3.io.consistentFile
import r3.io.log
import r3.io.serialize
import r3.io.toDataInputStream
import r3.pack.BinaryPack
import r3.pke.EncryptedMetaKey
import r3.pke.IdentityKey
import r3.pke.Peer
import r3.pke.PeerKey
import r3.source.BinarySource
import r3.source.FileSink
import r3.source.FileSource
import r3.source.Source
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles binary index file persistence (`meta.idx`, `deleted.idx`, `ignored.idx`),
 * legacy directory migrations, quota settings persistence, and portable `.pmsg` pack
 * import/export for [GraffitiP2P].
 */
class GraffitiIndexStore(val graffitiDir: File) {
	val metaIndexFile = File(graffitiDir, "meta.idx")
	val metaIndexBakFile = File(graffitiDir, "meta.idx.bak")
	val deletedIndexFile = File(graffitiDir, "deleted.idx")
	val ignoredIndexFile = File(graffitiDir, "ignored.idx")
	private val quotaFile = File(graffitiDir, "quota.properties")

	private val legacyMetaDir = File(graffitiDir, "meta")
	private val legacyDeletedDir = File(graffitiDir, "deleted")
	private val legacyIgnoredIdentitiesDir = File(graffitiDir, "ignored_identities")
	private val legacyIgnoredContentDir = File(graffitiDir, "ignored_content")

	private val metaIndexLock = Any()
	private val deletedIndexLock = Any()
	private val ignoredIndexLock = Any()

	companion object {
		const val MAGIC_META_INDEX = 0x47524D49 // "GRMI"
		const val MAGIC_DELETED_INDEX = 0x47524449 // "GRDI"
		const val MAGIC_IGNORED_INDEX = 0x47524949 // "GRII"
		const val INDEX_VERSION: Short = 1
		const val MAX_META_ENTRY_BYTES = 10 * 1024 * 1024 // 10 MB
		const val META_INDEX_COMPACT_THRESHOLD = 100
	}

	private fun replaceFileAtomically(tempFile: File, destFile: File) {
		try {
			Files.move(
				tempFile.toPath(),
				destFile.toPath(),
				StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE
			)
		} catch (_: Exception) {
			try {
				Files.move(
					tempFile.toPath(),
					destFile.toPath(),
					StandardCopyOption.REPLACE_EXISTING
				)
			} catch (_: Exception) {
				tempFile.copyTo(destFile, overwrite = true, bufferSize = DEFAULT_COPY_BUFFER_SIZE)
				tempFile.delete()
			}
		}
	}

	fun loadQuota(defaultQuota: Long): Long {
		if (!quotaFile.exists()) return defaultQuota
		return try {
			val props = Properties()
			quotaFile.inputStream().use { props.load(it) }
			val loaded = props.getProperty("quotaBytes", defaultQuota.toString()).toLongOrNull() ?: defaultQuota
			log("Loaded storage quota: $loaded bytes")
			loaded
		} catch (e: Exception) {
			log("Failed to load quota: ${e.message}")
			defaultQuota
		}
	}

	fun saveQuota(bytes: Long) {
		val props = Properties()
		props.setProperty("quotaBytes", bytes.toString())
		quotaFile.outputStream().use { props.store(it, "Storage Quota Setting") }
		log("Storage quota set to $bytes bytes")
	}

	// ── Deleted Index ─────────────────────────────────────────────────────────

	fun loadOrMigrateDeleted(deletedCache: MutableSet<EncryptedMetaKey>) {
		if (!loadDeletedIndex(deletedCache)) {
			if (legacyDeletedDir.exists()) {
				legacyDeletedDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching { deletedCache.add(EncryptedMetaKey(file.name)) }
				}
				saveDeletedIndex(deletedCache)
				legacyDeletedDir.deleteRecursively()
			}
		}
	}

	private fun loadDeletedIndex(deletedCache: MutableSet<EncryptedMetaKey>): Boolean = synchronized(deletedIndexLock) {
		if (!deletedIndexFile.exists()) return false
		return runCatching {
			deletedIndexFile.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bis ->
				DataInputStream(bis).use { dis ->
					val magic = dis.readInt()
					if (magic != MAGIC_DELETED_INDEX) throw IOException("Invalid deleted.idx magic")
					val version = dis.readShort()
					if (version != INDEX_VERSION) throw IOException("Unsupported deleted.idx version: $version")
					val keyBuf = ByteArray(32)
					try {
						while (true) {
							val first = dis.read()
							if (first < 0) break
							keyBuf[0] = first.toByte()
							dis.readFully(keyBuf, 1, 31)
							deletedCache.add(EncryptedMetaKey(keyBuf.copyOf()))
						}
					} catch (_: EOFException) {
					}
				}
			}
			true
		}.onFailure {
			log("Failed to load deleted.idx, rebuilding from directory: ${it.message}")
		}.getOrDefault(false)
	}

	fun saveDeletedIndex(deletedCache: Set<EncryptedMetaKey>) {
		synchronized(deletedIndexLock) {
			runCatching {
				val tempFile = File(graffitiDir, "deleted.idx.tmp")
				tempFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeInt(MAGIC_DELETED_INDEX)
						dos.writeShort(INDEX_VERSION.toInt())
						for (key in deletedCache) {
							dos.write(key.arr)
						}
					}
				}
				replaceFileAtomically(tempFile, deletedIndexFile)
			}.onFailure {
				log("Failed to save deleted.idx: ${it.message}")
			}
		}
	}

	fun appendDeletedIndex(key: EncryptedMetaKey, deletedCache: Set<EncryptedMetaKey>) {
		synchronized(deletedIndexLock) {
			if (!deletedIndexFile.exists()) {
				saveDeletedIndex(deletedCache)
				return
			}
			runCatching {
				FileOutputStream(deletedIndexFile, true).use { fos ->
					fos.write(key.arr)
				}
			}.onFailure {
				log("Failed to append to deleted.idx: ${it.message}")
			}
		}
	}

	// ── Ignored Index ─────────────────────────────────────────────────────────

	fun loadOrMigrateIgnored(
		ignoredIdentitiesCache: MutableSet<IdentityKey>,
		ignoredContentCache: MutableSet<EncryptedMetaKey>
	) {
		if (!loadIgnoredIndex(ignoredIdentitiesCache, ignoredContentCache)) {
			var hasLegacyIgnored = false
			if (legacyIgnoredIdentitiesDir.exists()) {
				legacyIgnoredIdentitiesDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching { ignoredIdentitiesCache.add(IdentityKey(file.name)) }
				}
				hasLegacyIgnored = true
				legacyIgnoredIdentitiesDir.deleteRecursively()
			}
			if (legacyIgnoredContentDir.exists()) {
				legacyIgnoredContentDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching { ignoredContentCache.add(EncryptedMetaKey(file.name)) }
				}
				hasLegacyIgnored = true
				legacyIgnoredContentDir.deleteRecursively()
			}
			if (hasLegacyIgnored) {
				saveIgnoredIndex(ignoredIdentitiesCache, ignoredContentCache)
			}
		}
	}

	private fun loadIgnoredIndex(
		ignoredIdentitiesCache: MutableSet<IdentityKey>,
		ignoredContentCache: MutableSet<EncryptedMetaKey>
	): Boolean = synchronized(ignoredIndexLock) {
		if (!ignoredIndexFile.exists()) return false
		return runCatching {
			ignoredIndexFile.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bis ->
				DataInputStream(bis).use { dis ->
					val magic = dis.readInt()
					if (magic != MAGIC_IGNORED_INDEX) throw IOException("Invalid ignored.idx magic")
					val version = dis.readShort()
					if (version != INDEX_VERSION) throw IOException("Unsupported ignored.idx version: $version")
					val keyBuf = ByteArray(32)
					val idCount = dis.readInt()
					for (i in 0 until idCount) {
						dis.readFully(keyBuf)
						ignoredIdentitiesCache.add(IdentityKey(keyBuf.copyOf()))
					}
					val contentCount = dis.readInt()
					for (i in 0 until contentCount) {
						dis.readFully(keyBuf)
						ignoredContentCache.add(EncryptedMetaKey(keyBuf.copyOf()))
					}
				}
			}
			true
		}.onFailure {
			log("Failed to load ignored.idx: ${it.message}")
		}.getOrDefault(false)
	}

	fun saveIgnoredIndex(
		ignoredIdentitiesCache: Set<IdentityKey>,
		ignoredContentCache: Set<EncryptedMetaKey>
	) {
		synchronized(ignoredIndexLock) {
			runCatching {
				val tempFile = File(graffitiDir, "ignored.idx.tmp")
				tempFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeInt(MAGIC_IGNORED_INDEX)
						dos.writeShort(INDEX_VERSION.toInt())
						val idList = ignoredIdentitiesCache.toList()
						dos.writeInt(idList.size)
						for (idKey in idList) {
							dos.write(idKey.arr)
						}
						val contentList = ignoredContentCache.toList()
						dos.writeInt(contentList.size)
						for (cKey in contentList) {
							dos.write(cKey.arr)
						}
					}
				}
				replaceFileAtomically(tempFile, ignoredIndexFile)
			}.onFailure {
				log("Failed to save ignored.idx: ${it.message}")
			}
		}
	}

	// ── Meta Index ────────────────────────────────────────────────────────────

	fun loadOrMigrateMeta(
		metaCache: ConcurrentHashMap<EncryptedMetaKey, EncryptedContentMetaData>,
		metaTimeCache: ConcurrentHashMap<EncryptedMetaKey, Long>,
		deletedCache: Set<EncryptedMetaKey>,
		uncompactedDeletes: AtomicInteger
	) {
		if (!loadMetaIndex(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)) {
			metaCache.clear()
			metaTimeCache.clear()
			if (legacyMetaDir.exists()) {
				legacyMetaDir.listFiles { f -> f.isFile }?.forEach { file ->
					runCatching {
						val eMeta = file.toDataInputStream().use { EncryptedContentMetaData.read(it) }
						if (!deletedCache.contains(eMeta.key)) {
							metaCache[eMeta.key] = eMeta
							metaTimeCache[eMeta.key] = file.lastModified()
						}
					}
				}
				saveMetaIndex(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)
				legacyMetaDir.deleteRecursively()
			}
		}
	}

	private fun readMetaIndexStream(
		file: File,
		metaCache: ConcurrentHashMap<EncryptedMetaKey, EncryptedContentMetaData>,
		metaTimeCache: ConcurrentHashMap<EncryptedMetaKey, Long>,
		deletedCache: Set<EncryptedMetaKey>,
		uncompactedDeletes: AtomicInteger
	): Boolean {
		if (!file.exists()) return false
		var staleCount = 0
		val ok = runCatching {
			file.inputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bis ->
				DataInputStream(bis).use { dis ->
					val magic = dis.readInt()
					if (magic != MAGIC_META_INDEX) throw IOException("Invalid meta.idx magic")
					val version = dis.readShort()
					if (version != INDEX_VERSION) throw IOException("Unsupported meta.idx version: $version")
					try {
						while (true) {
							val arrivalTime = try {
								dis.readLong()
							} catch (_: EOFException) {
								break
							}
							val dataLen = dis.readInt()
							if (dataLen !in 1..MAX_META_ENTRY_BYTES) {
								throw IOException("Invalid meta entry length in meta.idx: $dataLen")
							}
							val entryBytes = ByteArray(dataLen)
							dis.readFully(entryBytes)
							runCatching {
								val eMeta = entryBytes.toDataInputStream().use { EncryptedContentMetaData.read(it) }
								if (deletedCache.contains(eMeta.key)) {
									staleCount++
								} else {
									if (metaCache.put(eMeta.key, eMeta) != null) {
										staleCount++
									}
									metaTimeCache[eMeta.key] = arrivalTime
								}
							}
						}
					} catch (_: EOFException) {
						staleCount++
					}
				}
			}
			true
		}.onFailure {
			log("Failed to load meta index from ${file.name}: ${it.message}")
		}.getOrDefault(false)

		if (ok && staleCount >= META_INDEX_COMPACT_THRESHOLD) {
			saveMetaIndex(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)
		} else if (ok && staleCount > 0) {
			uncompactedDeletes.set(staleCount)
		}
		return ok
	}

	private fun loadMetaIndex(
		metaCache: ConcurrentHashMap<EncryptedMetaKey, EncryptedContentMetaData>,
		metaTimeCache: ConcurrentHashMap<EncryptedMetaKey, Long>,
		deletedCache: Set<EncryptedMetaKey>,
		uncompactedDeletes: AtomicInteger
	): Boolean = synchronized(metaIndexLock) {
		if (readMetaIndexStream(metaIndexFile, metaCache, metaTimeCache, deletedCache, uncompactedDeletes)) return true
		if (readMetaIndexStream(metaIndexBakFile, metaCache, metaTimeCache, deletedCache, uncompactedDeletes)) {
			log("Recovered metadata from meta.idx.bak")
			saveMetaIndex(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)
			return true
		}
		return false
	}

	fun saveMetaIndex(
		metaCache: Map<EncryptedMetaKey, EncryptedContentMetaData>,
		metaTimeCache: Map<EncryptedMetaKey, Long>,
		deletedCache: Set<EncryptedMetaKey>,
		uncompactedDeletes: AtomicInteger
	) {
		synchronized(metaIndexLock) {
			runCatching {
				val tempFile = File(graffitiDir, "meta.idx.tmp")
				val sortedEntries = metaCache.entries
					.filter { !deletedCache.contains(it.key) }
					.sortedBy { metaTimeCache[it.key] ?: 0L }
				tempFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeInt(MAGIC_META_INDEX)
						dos.writeShort(INDEX_VERSION.toInt())
						for ((key, eMeta) in sortedEntries) {
							val ts = metaTimeCache[key] ?: System.currentTimeMillis()
							val bytes = eMeta.serialize()
							dos.writeLong(ts)
							dos.writeInt(bytes.size)
							dos.write(bytes)
						}
					}
				}
				if (metaIndexFile.exists()) {
					runCatching {
						metaIndexFile.copyTo(metaIndexBakFile, overwrite = true)
					}
				}
				replaceFileAtomically(tempFile, metaIndexFile)
				uncompactedDeletes.set(0)
			}.onFailure {
				log("Failed to save meta.idx: ${it.message}")
			}
		}
	}

	fun appendMetaIndex(
		eMeta: EncryptedContentMetaData,
		timestamp: Long,
		serializedBytes: ByteArray?,
		metaCache: Map<EncryptedMetaKey, EncryptedContentMetaData>,
		metaTimeCache: Map<EncryptedMetaKey, Long>,
		deletedCache: Set<EncryptedMetaKey>,
		uncompactedDeletes: AtomicInteger
	) {
		synchronized(metaIndexLock) {
			if (!metaIndexFile.exists()) {
				saveMetaIndex(metaCache, metaTimeCache, deletedCache, uncompactedDeletes)
				return
			}
			runCatching {
				val bytes = serializedBytes ?: eMeta.serialize()
				FileOutputStream(metaIndexFile, true).buffered(DEFAULT_COPY_BUFFER_SIZE).use { bos ->
					DataOutputStream(bos).use { dos ->
						dos.writeLong(timestamp)
						dos.writeInt(bytes.size)
						dos.write(bytes)
					}
				}
			}.onFailure {
				log("Failed to append to meta.idx: ${it.message}")
			}
		}
	}

	// ── Portable Messages (.pmsg Pack) Export & Import ────────────────────────

	fun exportMessages(p2p: GraffitiP2P, keys: Collection<EncryptedMetaKey>, destinationFile: File): Int {
		val items = mutableListOf<Content>()
		val peerKeysAdded = mutableSetOf<PeerKey>()
		var count = 0

		for (key in keys) {
			val eMeta = p2p.getMetaByKey(key) ?: continue
			val metaTime = p2p.getMetaTimeByKey(key) ?: System.currentTimeMillis()
			val metaBytes = eMeta.serialize()

			// 1. Add meta entry
			items.add(object : Content, Source by BinarySource(metaBytes) {
				override val path: String = "meta/$key"
				override val ext: String = "meta"
				override val length: Long = metaBytes.size.toLong()
				override val lastModified: Long = metaTime
			})

			// 2. Add content entry if present
			val contentFile = File(p2p.contentDir, "$key").consistentFile()
			if (contentFile.exists() && contentFile.length() > 0) {
				items.add(object : Content, Source by FileSource(contentFile) {
					override val path: String = "content/$key"
					override val ext: String = "content"
					override val length: Long = contentFile.length()
					override val lastModified: Long = contentFile.lastModified()
					override fun createInputStream(): InputStream = contentFile.inputStream()
				})
			} else {
				val smallBytes = p2p.getSmallContentBytes(key)
				if (smallBytes != null && smallBytes.isNotEmpty()) {
					items.add(BinaryContent(smallBytes, path = "content/$key", ext = "content"))
				}
			}

			// 3. Add author peer public key if available
			if (peerKeysAdded.add(eMeta.author)) {
				val peer = p2p.getPeerByKey(eMeta.author)
					?: p2p.listIdentities().find { it.asPeer().key == eMeta.author }?.asPeer()
				if (peer != null) {
					items.add(BinaryContent(peer.serialize(), path = "peer/${eMeta.author}", ext = "peer"))
				}
			}
			count++
		}

		if (items.isEmpty()) return 0

		destinationFile.parentFile?.mkdirs()
		val sink = FileSink(destinationFile, append = false)
		BinaryPack.create(items, sink)
		return count
	}

	fun importPack(p2p: GraffitiP2P, source: Source): GraffitiP2P.ImportResult {
		val pack = BinaryPack.open(source)
		val entries = pack.toList()
		var importedPeers = 0
		var importedMessages = 0
		var skippedMessages = 0

		// 1. Import any peers bundled in the pack
		for (entry in entries) {
			if (entry.path.startsWith("peer/")) {
				try {
					val peer = entry.createInputStream().use { Peer.read(DataInputStream(it)) }
					if (p2p.getPeerByKey(peer.key) == null) {
						p2p.savePeer(peer)
						importedPeers++
					}
				} catch (e: Exception) {
					log("Failed to import peer entry ${entry.path}: ${e.message}")
				}
			}
		}

		// 2. Index meta and content entries
		val metaEntries = mutableMapOf<EncryptedMetaKey, Content>()
		val contentEntries = mutableMapOf<EncryptedMetaKey, Content>()

		for (entry in entries) {
			if (entry.path.startsWith("meta/")) {
				val keyStr = entry.path.removePrefix("meta/")
				try {
					val key = EncryptedMetaKey(keyStr)
					metaEntries[key] = entry
				} catch (_: Exception) {}
			} else if (entry.path.startsWith("content/")) {
				val keyStr = entry.path.removePrefix("content/")
				try {
					val key = EncryptedMetaKey(keyStr)
					contentEntries[key] = entry
				} catch (_: Exception) {}
			}
		}

		val newlyImportedKeys = mutableListOf<EncryptedMetaKey>()

		for ((key, metaEntry) in metaEntries) {
			if (p2p.isMessageDeleted(key)) {
				skippedMessages++
				continue
			}

			val contentFile = File(p2p.contentDir, "$key").consistentFile()
			val contentEntry = contentEntries[key]

			val alreadyHasMeta = p2p.getMetaByKey(key) != null
			val alreadyHasContent = p2p.hasContent(key)

			if (alreadyHasMeta && (alreadyHasContent || contentEntry == null)) {
				skippedMessages++
				continue
			}

			var metaSaved = false
			if (!alreadyHasMeta) {
				try {
					val metaBytes = metaEntry.createInputStream().use { it.readBytes() }
					val eMeta = DataInputStream(ByteArrayInputStream(metaBytes)).use { EncryptedContentMetaData.read(it) }
					if (eMeta.key != key) {
						log("Key mismatch for portable meta: expected $key but got ${eMeta.key}")
						skippedMessages++
						continue
					}
					val originalTime = metaEntry.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis()
					p2p.putImportedMeta(key, eMeta, originalTime, metaBytes)
					metaSaved = true
				} catch (e: Exception) {
					log("Failed to write portable meta for $key: ${e.message}")
					skippedMessages++
					continue
				}
			}

			var contentSaved = false
			if (contentEntry != null && !alreadyHasContent) {
				try {
					contentFile.outputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { out ->
						contentEntry.createInputStream().buffered(DEFAULT_COPY_BUFFER_SIZE).use { inp ->
							inp.copyTo(out, DEFAULT_COPY_BUFFER_SIZE)
						}
					}
					p2p.onImportedContentStored(key, contentFile)
					contentSaved = true
				} catch (e: Exception) {
					log("Failed to write portable content for $key: ${e.message}")
				}
			}

			if (metaSaved || contentSaved) {
				importedMessages++
				newlyImportedKeys.add(key)
			}
		}

		if (newlyImportedKeys.isNotEmpty()) {
			p2p.triggerMetaWarmup(newlyImportedKeys)
			for (k in newlyImportedKeys) {
				p2p.pushNewMessage(k)
			}
			p2p.checkAndEnforceQuota()
		}

		return GraffitiP2P.ImportResult(importedMessages, skippedMessages, importedPeers)
	}
}
