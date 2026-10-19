package cn.aust.mytimetable

// Minutes since 08:00. Lunch keeps 65% of its usual height; all positions use the same mapping.
internal fun timelineFraction(minutes: Int): Float {
    val compressed = (minutes - 240).coerceIn(0, 120) * 0.35f
    return (minutes - compressed) / (840f - 120f * 0.35f)
}
