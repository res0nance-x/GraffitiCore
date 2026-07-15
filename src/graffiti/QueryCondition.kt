package graffiti

import r3.io.Writable
import r3.key.Key256
import r3.source.ListWritable
import java.io.DataInputStream
import java.io.DataOutputStream

class QueryCondition(val set: Set<Key256>, val conditionType: ConditionType) : Writable {
	enum class ConditionType(val typeStr: String) {
		Include("include"),
		Exclude("exclude");
	}

	fun matches(key: Key256): Boolean {
		return when (conditionType) {
			ConditionType.Include -> key in set
			ConditionType.Exclude -> key !in set
		}
	}

	override fun write(dos: DataOutputStream) {
		dos.writeInt(conditionType.ordinal)
		ListWritable(set.toList()).write(dos)
	}

	companion object {
		val ALL = QueryCondition(emptySet(), ConditionType.Exclude)
		fun read(dis: DataInputStream): QueryCondition {
			val conditionType = ConditionType.entries[dis.readInt()]
			val list = ListWritable.read(dis, Key256.Companion::read).list
			return QueryCondition(list.toSet(), conditionType)
		}
	}
}