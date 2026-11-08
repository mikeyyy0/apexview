package app.apexview

import kotlin.math.roundToLong

/**
 * The circle of doom, after MultiViewer's: the lap as a circle laid out in time, not distance.
 * Each car sits at how far into a lap it is at the leader's pace, so a pit stop costs the same
 * arc wherever it's made, and the cars a stop's length behind a driver are the ones they'd come
 * out among.
 */
internal object PitWindow {
    /** Before any stop has been seen at a circuit: a stop's usual cost, give or take a few seconds. */
    const val TYPICAL_LOSS_MS = 22_000L
    /** The reference lap is the average of the leader's last this-many green laps. */
    const val REFERENCE_LAPS = 5

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

    /** The pit lane as this session's clean stops measured it: medians of each. */
    class PitLane(val lossMs: Long, val stops: Int, val entry: Float, val exit: Float, val laneMs: Long)

    fun pitLane(timing: SessionTiming): PitLane? {
        class Stop(val loss: Long, val entry: Float, val exit: Float, val laneMs: Long)
        val stops = timing.laps.flatMap { (number, laps) ->
            val track = timing.tracks[number] ?: return@flatMap emptyList()
            laps.pitStops.mapNotNull { (inUtc, outUtc) ->
                val lapMs = laps.typicalLapMs(inUtc) ?: return@mapNotNull null
                val entry = track.at(inUtc)?.let { timing.geometry.progress(it.first, it.second) } ?: return@mapNotNull null
                val exit = track.at(outUtc)?.let { timing.geometry.progress(it.first, it.second) } ?: return@mapNotNull null
                lossFromStop(inUtc, outUtc, entry, exit, lapMs)?.let { Stop(it, entry, exit, outUtc - inUtc) }
            }
        }
        if (stops.isEmpty()) return null
        fun <T : Comparable<T>> median(of: (Stop) -> T) = stops.map(of).sorted()[stops.size / 2]
        // Entry and exit can sit either side of the line, so they're averaged as a step from the first.
        val first = stops[0]
        return PitLane(
            median { it.loss },
            stops.size,
            (first.entry + median { centred(it.entry - first.entry) }).mod(1f),
            (first.exit + median { centred(it.exit - first.exit) }).mod(1f),
            median { it.laneMs },
        )
    }

    /** A step round the lap as the shorter way: -0.5..0.5. */
    private fun centred(f: Float) = (f + 0.5f).mod(1f) - 0.5f

    /** Where a car [lapFraction] into the lap would come back out after a stop, in the same terms. */
    fun ghost(lapFraction: Float, lossMs: Long, lapMs: Long): Float = (lapFraction - lossMs.toFloat() / lapMs).mod(1f)

    /**
     * How far through the race a car is, in reference laps: its lap number plus how far into the
     * lap it is. The lap count changes at F1's timing line, a moment off the car's position, so a
     * car the count has just moved on (or not yet) is put back on the lap its position says.
     */
    fun raceDistance(lap: Int, lapFraction: Float, sinceCrossing: Float?): Float {
        var laps = lap
        if (sinceCrossing != null) {
            if (lapFraction - sinceCrossing > 0.5f) laps -= 1
            if (sinceCrossing - lapFraction > 0.5f) laps += 1
        }
        return laps + lapFraction
    }

    /**
     * The cars just ahead of and just behind [at] on track, with how far in ms. Positions are
     * lap fractions; the car stopping isn't among [others].
     */
    fun neighbours(at: Float, others: List<Pair<String, Float>>, lapMs: Long): Neighbours {
        val ahead = others.minByOrNull { (_, f) -> (f - at).mod(1f) }
        val behind = others.minByOrNull { (_, f) -> (at - f).mod(1f) }
        return Neighbours(
            ahead?.let { (number, f) -> number to ((f - at).mod(1f) * lapMs).roundToLong() },
            behind?.let { (number, f) -> number to ((at - f).mod(1f) * lapMs).roundToLong() },
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

/**
 * How far into a lap, in time, each point of the track comes: what lays the circle out in time.
 * Taken from one clean lap's positions and read as a fraction of that lap, so it scales to any
 * pace. Without one, distance stands in for time.
 */
class LapProfile private constructor(private val fractions: FloatArray) {
    /** The share of a lap it takes to get from the line to [progress] round the track. */
    fun timeFraction(progress: Float): Float {
        val x = progress.mod(1f) * BINS
        val i = x.toInt().coerceAtMost(BINS - 1)
        return fractions[i] + (fractions[i + 1] - fractions[i]) * (x - i)
    }

    companion object {
        private const val BINS = 200

        /** Distance for time: before there's a clean lap to measure. */
        val EVEN = LapProfile(FloatArray(BINS + 1) { it.toFloat() / BINS })

        /**
         * From one lap's samples: ms since the line, and progress round the track then. Null when
         * they don't go round once.
         */
        fun from(samples: List<Pair<Long, Float>>, lapMs: Long): LapProfile? {
            if (samples.size < 20 || lapMs <= 0) return null
            // How far each sample is from the first, counted up through the line; a step back
            // (the position wobbling) counts as standing still.
            val start = samples[0].second
            val travelled = FloatArray(samples.size)
            for (i in 1 until samples.size) {
                val step = (samples[i].second - samples[i - 1].second).mod(1f)
                travelled[i] = travelled[i - 1] + if (step > 0.5f) 0f else step
            }
            if (travelled.last() < MIN_COVERAGE) return null
            // For each evenly spaced point round the track, when the lap reached it.
            val fractions = FloatArray(BINS + 1) { b ->
                val target = (b.toFloat() / BINS - start).mod(1f)
                val k = travelled.indexOfFirst { it >= target }
                val ms = when {
                    k < 0 -> samples.last().first
                    k == 0 -> samples[0].first
                    else -> {
                        val span = travelled[k] - travelled[k - 1]
                        val along = if (span <= 0f) 1f else (target - travelled[k - 1]) / span
                        samples[k - 1].first + ((samples[k].first - samples[k - 1].first) * along).toLong()
                    }
                }
                (ms.toFloat() / lapMs).coerceIn(0f, 1f)
            }
            return LapProfile(fractions)
        }

        /** The lap must reach this far round for its profile to be trusted. */
        private const val MIN_COVERAGE = 0.95f
    }
}
