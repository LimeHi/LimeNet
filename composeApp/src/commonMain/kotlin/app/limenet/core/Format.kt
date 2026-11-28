package app.limenet.core

/** Форматирование чисел/дат без java.time — общий код для desktop и Android. */
object Format {

    fun bytes(value: Long?): String {
        if (value == null || value < 0) return "—"
        if (value == 0L) return "0 Б"
        val units = listOf("Б", "КБ", "МБ", "ГБ", "ТБ", "ПБ")
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit == 0 || size >= 100) {
            "${size.toLong()} ${units[unit]}"
        } else {
            "${round1(size)} ${units[unit]}"
        }
    }

    /** Байт/с → «12.4 Мбит/с». */
    fun speed(bytesPerSecond: Long?): String {
        if (bytesPerSecond == null || bytesPerSecond <= 0) return "—"
        val mbit = bytesPerSecond * 8.0 / 1_000_000.0
        return if (mbit >= 100) "${mbit.toLong()} Мбит/с" else "${round1(mbit)} Мбит/с"
    }

    /** Секунды с эпохи → «15.11.2026». */
    fun date(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0) return "—"
        val (year, month, day) = civilFromDays(epochSeconds / 86_400)
        return "${day.toString().padStart(2, '0')}.${month.toString().padStart(2, '0')}.$year"
    }

    /** Сколько целых дней осталось до даты (может быть отрицательным). */
    fun daysLeft(epochSeconds: Long?, nowSeconds: Long): Long? {
        if (epochSeconds == null || epochSeconds <= 0) return null
        return (epochSeconds - nowSeconds) / 86_400
    }

    fun duration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "$seconds с"
            seconds < 3600 -> "${seconds / 60} мин"
            seconds < 86_400 -> "${seconds / 3600} ч"
            else -> "${seconds / 86_400} д"
        }
    }

    private fun round1(value: Double): String {
        val rounded = (value * 10).toLong() / 10.0
        val whole = rounded.toLong()
        val fraction = ((rounded - whole) * 10).toLong()
        return if (fraction == 0L) "$whole" else "$whole,$fraction"
    }

    private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val year = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (month <= 2) year + 1 else year).toInt(), month.toInt(), day.toInt())
    }
}
