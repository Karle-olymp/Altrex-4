package com.google.ai.edge.gallery.search

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

/**
 * [ALTREX] Meaning vectors of the memory's pieces, one per [MemoryIndex] piece, in the same order.
 * The vectors come L2-normalized from the embedding model; each value is kept on one byte with a
 * scale per vector (768 bytes + 4 per piece), which keeps the cosine within about ±0.005.
 * A piece whose vector is not computed yet has a scale of 0.
 */
class VectorStore(
  val dim: Int,
  val count: Int,
  val model: String,
  private val data: ByteArray = ByteArray(dim * count),
  private val scales: FloatArray = FloatArray(count),
) {
  init {
    require(data.size == dim * count && scales.size == count) { "bad vector data size" }
  }

  fun has(i: Int): Boolean = scales[i] > 0f

  val computed: Int
    get() = (0 until count).count { has(it) }

  fun set(i: Int, vector: FloatArray) {
    require(vector.size == dim)
    val max = vector.maxOf { kotlin.math.abs(it) }
    if (max == 0f) return
    val scale = max / 127f
    val base = i * dim
    for (j in 0 until dim) data[base + j] = (vector[j] / scale).roundToInt().coerceIn(-127, 127).toByte()
    scales[i] = scale
  }

  /** Copies the vector of piece [from] of [other] into piece [to] of this store. */
  fun copyFrom(other: VectorStore, from: Int, to: Int) {
    System.arraycopy(other.data, from * dim, data, to * dim, dim)
    scales[to] = other.scales[from]
  }

  /** Cosine between piece [i] and a normalized [query]. */
  fun cosine(i: Int, query: FloatArray): Float {
    val base = i * dim
    var sum = 0f
    for (j in 0 until dim) sum += data[base + j] * query[j]
    return sum * scales[i]
  }

  /** The [k] pieces closest to [query] (only the computed ones), best first. */
  fun nearest(query: FloatArray, k: Int): List<Pair<Int, Float>> {
    if (query.size != dim) return emptyList()
    val best = java.util.PriorityQueue<Pair<Int, Float>>(compareBy { it.second })
    for (i in 0 until count) {
      if (!has(i)) continue
      val c = cosine(i, query)
      if (best.size < k) best.add(i to c) else if (c > best.peek()!!.second) {
        best.poll()
        best.add(i to c)
      }
    }
    return best.sortedByDescending { it.second }
  }

  fun write(out: DataOutputStream) {
    out.writeInt(MAGIC)
    out.writeUTF(model)
    out.writeInt(dim)
    out.writeInt(count)
    for (sc in scales) out.writeFloat(sc)
    out.write(data)
  }

  companion object {
    private const val MAGIC = 0x414C5632 // "ALV2"

    fun read(input: DataInputStream): VectorStore? {
      if (input.readInt() != MAGIC) return null
      val model = input.readUTF()
      val dim = input.readInt()
      val count = input.readInt()
      if (dim <= 0 || count < 0 || dim.toLong() * count > 200_000_000L) return null
      val scales = FloatArray(count) { input.readFloat() }
      val data = ByteArray(dim * count)
      input.readFully(data)
      return VectorStore(dim, count, model, data, scales)
    }
  }
}
