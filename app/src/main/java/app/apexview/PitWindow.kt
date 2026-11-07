package app.apexview

import kotlin.math.roundToLong

/**
 * Where a pit stop would put a driver: the lap as a circle, every car at how far round it is,
 * and the driver's "ghost" as far back as a stop costs. The cars around the ghost are the ones
 * they'd come out among.
 */
internal object PitWindow {
    /** Before any stop has been seen at a circuit: a stop's usual cost, give or take a few seconds. */
    const val TYPICAL_LOSS_MS = 22_000L

    /**
     * What one stop cost: its time in the pit lane, less the time the same stretch takes on track.
     * [entry] and [exit] are how far round the lap the car was going in and coming out. Null when
     * it doesn't look like one ordinary stop (a long stay under a red flag, a garage visit).
     */
    fun lossFromStop(inUtc: Long, outUtc: Long, entry: Float, exit: Float, lapMs: Long): Long? {
        val laneMs = outUtc - inUtc
        if (laneMs !in MIN_LANE_MS..MAX_LANE_MS) return null
        val stretch = (exit - entry).mod(1f)
        if (stretch > MAX_STRETCH) return null
        val loss = laneMs - (stretch * lapMs).toLong()
        return loss.takeIf { it in MIN_LOSS_MS..MAX_LOSS_MS }
    }

    /** What a stop costs in this session, from every stop that measured cleanly: the median and how many. */
    fun sessionLoss(timing: SessionTiming): Pair<Long, Int>? {
        val losses = timing.laps.flatMap { (number, laps) ->
            val track = timing.tracks[number] ?: return@flatMap emptyList()
            laps.pitStops.mapNotNull { (inUtc, outUtc) ->
                val lapMs = laps.typicalLapMs(inUtc) ?: return@mapNotNull null
                val entry = track.at(inUtc)?.let { timing.geometry.progress(it.first, it.second) } ?: return@mapNotNull null
                val exit = track.at(outUtc)?.let { timing.geometry.progress(it.first, it.second) } ?: return@mapNotNull null
                lossFromStop(inUtc, outUtc, entry, exit, lapMs)
            }
        }.sorted()
        return if (losses.isEmpty()) null else losses[losses.size / 2] to losses.size
    }

    /** Where a car at [at] round the lap would come back out after a stop. */
    fun ghost(at: Float, lossMs: Long, lapMs: Long): Float = (at - lossMs.toFloat() / lapMs).mod(1f)

    /**
     * The cars just ahead of and just behind the ghost on track, with how far in seconds. The
     * driver stopping isn't among them.
     */
    fun neighbours(ghost: Float, others: List<Pair<String, Float>>, lapMs: Long): Neighbours {
        val ahead = others.minByOrNull { (_, f) -> (f - ghost).mod(1f) }
        val behind = others.minByOrNull { (_, f) -> (ghost - f).mod(1f) }
        return Neighbours(
            ahead?.let { (number, f) -> number to ((f - ghost).mod(1f) * lapMs).roundToLong() },
            behind?.let { (number, f) -> number to ((ghost - f).mod(1f) * lapMs).roundToLong() },
        )
    }

    class Neighbours(val ahead: Pair<String, Long>?, val behind: Pair<String, Long>?)

    private const val MIN_LANE_MS = 12_000L
    private const val MAX_LANE_MS = 60_000L
    /** The pit lane skips a short stretch of the lap; more than this and the positions were off. */
    private const val MAX_STRETCH = 0.25f
    private const val MIN_LOSS_MS = 10_000L
    private const val MAX_LOSS_MS = 45_000L
}
