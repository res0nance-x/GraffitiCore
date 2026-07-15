package graffiti

import r3.content.Content
import r3.content.ContentMeta
import r3.content.FileContent
import r3.content.meta
import r3.io.serialize
import r3.source.ListWritable
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/*
A LinkPage is a bit like a webpage containing a list of mixed content (text, images, videos, etc.)
 */
class LinkPage(
	override val path: String,
	override val lastModified: Long,
	val contentMetaList: List<ContentMeta>
) : Content {
	private val arr = ListWritable(contentMetaList).serialize()

	constructor(file: File) : this(
		path = file.name,
		lastModified = file.lastModified(),
		contentMetaList = (file.listFiles()
			?: error("Directory is empty")).map { if (it.isDirectory) LinkPage(it).meta else FileContent(it).meta }
	)

	override val ext: String
		get() = "link"

	override fun createInputStream(): InputStream {
		return ByteArrayInputStream(arr)
	}

	override val length: Long
		get() = arr.size.toLong()
}