package graffiti

import r3.key.Key256
import r3.util.LGMRandomSequence

// Returns the SVG String directly
fun getAvatar(key: Key256): String {
	return createAvatar(key, 64)
}

class Coord(var x: Float, var y: Float, var r: Float, var g: Float, var b: Float) {
	override fun toString(): String {
		return "$x, $y $r $g $b"
	}
}

class IntCoord(var x: Int, var y: Int, var r: Int, var g: Int, var b: Int) {
	override fun toString(): String {
		return "$x, $y $r $g $b"
	}
}

private fun normalize(arr: List<Coord>, size: Int): List<IntCoord> {
	var minX = size.toFloat()
	var maxX = 0f
	var minY = size.toFloat()
	var maxY = 0f
	var minR = 1f
	var maxR = 0f
	var minG = 1f
	var maxG = 0f
	var minB = 1f
	var maxB = 0f

	arr.forEach { c ->
		if (c.x < minX) minX = c.x
		if (c.y < minY) minY = c.y
		if (c.r < minR) minR = c.r
		if (c.g < minG) minG = c.g
		if (c.b < minB) minB = c.b
		if (c.x > maxX) maxX = c.x
		if (c.y > maxY) maxY = c.y
		if (c.r > maxR) maxR = c.r
		if (c.g > maxG) maxG = c.g
		if (c.b > maxB) maxB = c.b
	}

	return arr.map { c ->
		IntCoord(
			((if (maxX != minX) (c.x - minX) / (maxX - minX) else 0f) * size).toInt(),
			((if (maxY != minY) (c.y - minY) / (maxY - minY) else 0f) * size).toInt(),
			((if (maxR != minR) (c.r - minR) / (maxR - minR) else 0f) * 255).toInt(),
			((if (maxG != minG) (c.g - minG) / (maxG - minG) else 0f) * 255).toInt(),
			((if (maxB != minB) (c.b - minB) / (maxB - minB) else 0f) * 255).toInt()
		)
	}
}

// Generates the data and coordinates entirely agnostic of platform UI libraries
fun createAvatar(key: Key256, size: Int): String {
	val m = LGMRandomSequence(key.arr)
	fun next(): Float {
		return ((m.nextDouble() - 0.5) * 0.01).toFloat()
	}

	var x = 0.5f
	var y = 0.5f
	var r = 0f
	var g = 0f
	var b = 0f
	val coord = ArrayList<Coord>()

	repeat(size * size / 4) {
		x += next()
		if (x < 0f) x = 0f else if (x > 1f) x = 1f
		y += next()
		if (y < 0f) y = 0f else if (y > 1f) y = 1f
		r += next()
		if (r < 0f) r = 0f else if (r > 1f) r = 1f
		g += next()
		if (g < 0f) g = 0f else if (g > 1f) g = 1f
		b += next()
		if (b < 0f) b = 0f else if (b > 1f) b = 1f
		coord.add(Coord(x, y, r, g, b))
	}
	val scaledCoord = normalize(coord, size)
	return toSvg(scaledCoord, size, size)
}

fun toHex(r: Int, g: Int, b: Int): String {
	return "#%02x%02x%02x".format(r, g, b)
}

// Converts coordinates to a scalable XML SVG text string
fun toSvg(coord: List<IntCoord>, width: Int, height: Int): String {
	val sb = StringBuilder()
	// SVG Opening Tag & background canvas shape
	sb.append(
		"""
<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg"
     viewBox="0 0 $width $height"
     width="$width"
     height="$height"
     stroke-width="1"
     stroke-linecap="square">
	""".trimIndent()
	)
	sb.append("""<g id="walk">""")
	var old = coord[0]
	coord.slice(1..<coord.size).forEach { c ->
		sb.append("""<path d="M${old.x} ${old.y} L${c.x} ${c.y}" stroke="${toHex(c.r, c.g, c.b)}"/>""")
		old = c
	}
	sb.append("</g>")

	sb.append("""<use href="#walk" transform="translate(64,0) scale(-1,1)"/>""")
	sb.append("</svg>")
	return sb.toString()
}