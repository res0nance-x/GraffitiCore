package graffiti

// Sent in response to a QueryMessage, or pushed unsolicited when new messages arrive.
// Header: StringWritable(type)
// File body: ListWritable<EncryptedContentMetaData> (serialized with ListWritable)
// The data is sent as a streaming file body to handle arbitrarily large metadata lists.
object QueryResponseMessage {
	val type = "syncresponse"
}

