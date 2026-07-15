package graffiti

import org.nanohttpd.protocols.http.tempfiles.ITempFile
import org.nanohttpd.protocols.http.tempfiles.ITempFileManager
import org.nanohttpd.util.IFactory
import r3.key.Key256
import java.io.File
import java.io.OutputStream

class CustomTempFileManagerFactory(val getTmpDir: () -> File) : IFactory<ITempFileManager> {
	/** Convenience constructor that accepts a fixed directory. */
	constructor(tmpDir: File) : this({ tmpDir })

	override fun create(): ITempFileManager {
		val tmpFileList = mutableListOf<File>()
		return object : ITempFileManager {
			override fun clear() {
				for (tmpFile in tmpFileList) {
					tmpFile.delete()
				}
				tmpFileList.clear()
			}

			override fun createTempFile(filename_hint: String?): ITempFile {
				val name = Key256.randomKey().toString()
				val dir = getTmpDir().also { it.mkdirs() }
				val tmpFile = File(dir, name)
				tmpFileList.add(tmpFile)
				return object : ITempFile {
					override fun delete() {
						tmpFile.delete()
					}

					override fun getName(): String {
						return tmpFile.absolutePath
					}

					override fun open(): OutputStream {
						return tmpFile.outputStream().buffered()
					}
				}
			}
		}
	}
}