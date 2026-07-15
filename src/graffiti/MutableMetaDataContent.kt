package graffiti

import r3.content.Content

class MutableMetaDataContent(
	private val inner: Content,
	override var path: String = inner.path,
	override var ext: String = inner.ext,
	override var lastModified: Long = inner.lastModified
) : Content by inner