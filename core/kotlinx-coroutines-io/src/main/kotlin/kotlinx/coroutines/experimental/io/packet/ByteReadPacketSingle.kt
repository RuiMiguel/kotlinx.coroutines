package kotlinx.coroutines.experimental.io.packet

import kotlinx.coroutines.experimental.io.internal.*
import java.io.*
import java.nio.*
import java.nio.charset.*

internal class ByteReadPacketSingle(internal var buffer: ByteBuffer?, internal val pool: ObjectPool<ByteBuffer>) : ByteReadPacket {
    override val remaining: Int
        get() = buffer?.remaining() ?: 0

    internal fun steal(): ByteBuffer = buffer?.also { buffer = null } ?: throw IllegalStateException("EOF")

    override fun readAvailable(dst: ByteArray, offset: Int, length: Int): Int {
        var copied = 0

        val rc = reading { buffer ->
            val size = minOf(buffer.remaining(), length - copied)
            buffer.get(dst, offset, size)
            copied += size

            if (copied == length) return copied
        }

        return if (rc) copied else -1
    }

    override fun readAvailable(dst: ByteBuffer): Int {
        var copied = 0

        val rc = reading { buffer ->
            if (dst.remaining() >= buffer.remaining()) {
                copied += buffer.remaining()
                dst.put(buffer)
            } else {
                while (dst.hasRemaining() && buffer.hasRemaining()) {
                    dst.put(buffer.get())
                    copied++
                }
            }

            if (!dst.hasRemaining()) return copied
        }

        return if (rc) copied else -1
    }

    override fun readFully(dst: ByteArray, offset: Int, length: Int) {
        val rc = readAvailable(dst, offset, length)
        if (rc < length) throw EOFException("Not enough bytes in the packet")
    }

    override fun readFully(dst: ByteBuffer): Int {
        val rc = readAvailable(dst)
        if (dst.hasRemaining()) throw EOFException("Not enough bytes in the packet")
        return rc
    }

    override fun readLong(): Long {
        var v = 0L
        val rc = reading { v = it.getLong() }
        if (!rc) throw EOFException("Couldn't read long from empty packet")
        return v
    }

    override fun readInt(): Int {
        var v = 0
        val rc = reading { v = it.getInt() }
        if (!rc) throw EOFException("Couldn't read int from empty packet")
        return v
    }

    override fun readShort(): Short {
        var v: Short = 0
        val rc = reading { v = it.getShort() }
        if (!rc) throw EOFException("Couldn't read short from empty packet")
        return v
    }

    override fun readByte(): Byte {
        var v: Byte = 0
        val rc = reading { v = it.get() }
        if (!rc) throw EOFException("Couldn't read byte from empty packet")
        return v
    }

    override fun readDouble(): Double {
        var v = 0.0
        val rc = reading { v = it.getDouble() }
        if (!rc) throw EOFException("Couldn't read double from empty packet")
        return v
    }

    override fun readFloat(): Float {
        var v = 0.0f
        val rc = reading { v = it.getFloat() }
        if (!rc) throw EOFException("Couldn't read float from empty packet")
        return v
    }

    override fun readUTF8LineTo(out: Appendable, limit: Int): Boolean {
        var decoded = 0
        var cr = false

        return reading { bb ->
            val rc = bb.decodeUTF8 { ch ->
                when (ch) {
                    '\r' -> {
                        if (cr) {
                            false
                        } else {
                            cr = true
                            true
                        }
                    }
                    '\n' ->  false
                    else -> {
                        if (cr) {
                            false
                        } else {
                            if (decoded == limit) throw BufferOverflowException()
                            decoded++
                            out.append(ch)
                            true
                        }
                    }
                }
            }

            if (rc == -1) {
                val v = bb.get()
                if (v != 0x0a.toByte() && v != 0x0d.toByte()) {
                    bb.position(bb.position() - 1)
                }
            } else if (rc > 0) throw MalformedInputException(0)
        }
    }

    override fun readText(): CharSequence {
        val sb = StringBuilder(remaining)

        var size = 0

        reading { bb ->
            size = bb.decodeUTF8 { ch ->
                sb.append(ch)
                true
            }

            size == 0
        }

        if (size > 0) throw CharacterCodingException()

        return sb
    }

    override fun skip(n: Int): Int {
        var skipped = 0

        reading {
            val m = minOf(n - skipped, it.remaining())
            it.position(it.position() + m)
            skipped += m
        }

        return skipped
    }

    override fun inputStream(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                var v: Byte = 0
                val rc = reading { v = it.get() }
                return if (rc) v.toInt() and 0xff else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int) = readAvailable(b, off, len)
            override fun skip(n: Long): Long {
                if (n > Int.MAX_VALUE) return this@ByteReadPacketSingle.skip(Int.MAX_VALUE).toLong()
                return this@ByteReadPacketSingle.skip(n.toInt()).toLong()
            }

            override fun available() = remaining

            override fun close() {
                release()
            }
        }
    }

    override fun readerUTF8(): Reader {
        return object : Reader() {
            override fun close() {
                release()
            }

            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                var decoded = 0

                val rc = reading { bb ->
                    if (len == 0) return@reading

                    val rc = bb.decodeUTF8 { ch ->
                        if (decoded == len) false
                        else {
                            cbuf[off + decoded] =ch
                            decoded++
                            true
                        }
                    }

                    if (rc > 0) throw MalformedInputException(0)
                }

                return if (!rc) -1 else decoded
            }
        }
    }

    override fun release() {
        recycle(buffer ?: return)
        buffer = null
    }

    override fun copy(): ByteReadPacket {
        val bb = buffer?.takeIf { it.hasRemaining() } ?: return ByteReadPacketEmpty
        val copyBuffer = pool.borrow()
        copyBuffer.put(bb.duplicate())
        copyBuffer.flip()
        return ByteReadPacketSingle(copyBuffer, pool)
    }

    private inline fun reading(block: (ByteBuffer) -> Unit): Boolean {
        val buffer = buffer ?: return false

        if (!buffer.hasRemaining()) {
            this.buffer = null
            recycle(buffer)
            return false
        }

        block(buffer)

        if (!buffer.hasRemaining()) {
            this.buffer = null
            recycle(buffer)
        }

        return true
    }

    private fun recycle(buffer: ByteBuffer) {
        pool.recycle(buffer)
    }
}