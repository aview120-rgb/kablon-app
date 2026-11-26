package com.autoagent.app.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import java.util.Calendar

/** Platform date then time picker; calls [onPicked] with the chosen epoch millis. */
fun pickDateTime(context: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(context, { _, y, m, d ->
        TimePickerDialog(context, { _, h, min ->
            val out = Calendar.getInstance().apply {
                set(y, m, d, h, min, 0)
                set(Calendar.MILLISECOND, 0)
            }
            onPicked(out.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), DateFormat.is24HourFormat(context)).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}
