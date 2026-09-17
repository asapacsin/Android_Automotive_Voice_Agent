package com.novadrive.evaluation

/**
 * Minimal JSON for results, baselines and telemetry. The module is shared by Android (which has
 * its own org.json) and the plain JVM, so it carries no JSON dependency of its own.
 *
 * Values: null, Boolean, Number, String, List<Any?>, Map<String, Any?>.
 */
object Json {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Double -> out.append(if (value.isFinite()) formatDouble(value) else "null")
            is Float -> append(out, value.toDouble())
            is Number -> out.append(value)
            is String -> quote(out, value)
            is Enum<*> -> quote(out, value.name)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    quote(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (v in value) {
                    if (!first) out.append(',')
                    first = false
                    append(out, v)
                }
                out.append(']')
            }
            else -> quote(out, value.toString())
        }
    }

    private fun formatDouble(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() + ".0" else v.toString()

    private fun quote(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    fun parse(text: String): Any? = Parser(text).run {
        val v = value()
        skipWs()
        require(pos == src.length) { "trailing content at $pos" }
        v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as Map<String, Any?>

    private class Parser(val src: String) {
        var pos = 0

        fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            require(pos < src.length) { "unexpected end" }
            return when (val c = src[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(src.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (src[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(src[pos] == ':') { "expected ':' at $pos" }
                pos++
                out[k] = value()
                skipWs()
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> error("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (src[pos] == ']') { pos++; return out }
            while (true) {
                out += value()
                skipWs()
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> error("expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            require(src[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
            val t = src.substring(start, pos)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}

internal fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
