package com.google.ai.edge.gallery.intents

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Log
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.core.net.toUri
import com.google.ai.edge.gallery.notifications.NotificationScheduleManagerEntryPoint
import com.google.ai.edge.gallery.proto.ScheduledNotification
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.EntryPointAccessors
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

@JsonClass(generateAdapter = true)
data class SendEmailParams(
  @Json(name = "to") val to: String? = null,
  @Json(name = "extra_email") val extra_email: String? = null,
  @Json(name = "subject") val subject: String? = null,
  @Json(name = "extra_subject") val extra_subject: String? = null,
  @Json(name = "body") val body: String? = null,
  @Json(name = "extra_text") val extra_text: String? = null,
) {
  val resolvedEmail: String get() = to ?: extra_email ?: ""
  val resolvedSubject: String get() = subject ?: extra_subject ?: ""
  val resolvedText: String get() = body ?: extra_text ?: ""
}

@JsonClass(generateAdapter = true)
data class SendSmsParams(
  @Json(name = "phone_number") val phone_number: String? = null,
  @Json(name = "to") val to: String? = null,
  @Json(name = "sms_body") val sms_body: String? = null,
  @Json(name = "message") val message: String? = null,
  @Json(name = "body") val body: String? = null,
) {
  val resolvedPhone: String get() = phone_number ?: to ?: ""
  val resolvedBody: String get() = sms_body ?: message ?: body ?: ""
}

@JsonClass(generateAdapter = true)
data class CreateCalendarEventParams(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true)
data class ReadCalendarEventsParams(val date: String)

@JsonClass(generateAdapter = true)
data class CalendarEventDto(
  val title: String,
  val description: String,
  val begin_time: String,
  val end_time: String,
)

@JsonClass(generateAdapter = true)
data class ReadCalendarEventsResponse(val events: List<CalendarEventDto>)

enum class IntentAction(val action: String) {
  SEND_EMAIL("send_email"),
  SEND_SMS("send_sms"),
  CREATE_CALENDAR_EVENT("create_calendar_event"),
  READ_CALENDAR_EVENTS("read_calendar_events"),
  GET_CURRENT_DATE_AND_TIME("get_current_date_and_time"),
  SCHEDULE_NOTIFICATION("schedule_notification");

  companion object {
    fun from(action: String): IntentAction? = entries.find { it.action == action }
  }
}

@JsonClass(generateAdapter = true)
data class ScheduleNotificationParams(
  val title: String,
  val message: String,
  val hour: Int,
  val minute: Int,
  val deeplink: String? = null,
  val task_id: String? = null,
  val model_name: String? = null,
  val year: Int? = null,
  val month: Int? = null,
  val day: Int? = null,
  val repeat_daily: Boolean? = null,
)

object IntentHandler {
  private const val TAG = "IntentHandler"

  suspend fun handleAction(
    context: Context,
    action: String,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    return when (IntentAction.from(action)) {
      IntentAction.SEND_EMAIL -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendEmailParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null && params.resolvedEmail.isNotEmpty()) {
            val intent =
              Intent(Intent.ACTION_SENDTO, "mailto:${params.resolvedEmail}".toUri()).apply {
                putExtra(Intent.EXTRA_SUBJECT, params.resolvedSubject)
                putExtra(Intent.EXTRA_TEXT, params.resolvedText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_email parameters or missing email: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_email parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.SEND_SMS -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(SendSmsParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null && params.resolvedPhone.isNotEmpty()) {
            val uri = "smsto:${params.resolvedPhone}".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
              putExtra("sms_body", params.resolvedBody)
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse send_sms parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse send_sms parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.CREATE_CALENDAR_EVENT -> {
        try {
          val moshi = Moshi.Builder().build()
          val jsonAdapter = moshi.adapter(CreateCalendarEventParams::class.java)
          val params = jsonAdapter.fromJson(parameters)
          if (params != null) {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val beginTimeMillis = format.parse(params.begin_time)?.time ?: 0L
            val endTimeMillis = format.parse(params.end_time)?.time ?: 0L
            val intent =
              Intent(Intent.ACTION_INSERT).apply {
                data = Events.CONTENT_URI
                putExtra(Events.TITLE, params.title)
                putExtra(Events.DESCRIPTION, params.description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, beginTimeMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endTimeMillis)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
            context.startActivity(intent)
            "succeeded"
          } else {
            Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters")
            "failed"
          }
        } catch (e: Exception) {
          Log.e(TAG, "Failed to parse create_calendar_event parameters: $parameters", e)
          "failed"
        }
      }
      IntentAction.READ_CALENDAR_EVENTS -> {
        readCalendarEvents(context, parameters, requestPermission)
      }
      IntentAction.GET_CURRENT_DATE_AND_TIME -> {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss EEEE", Locale.getDefault())
        val currentDateAndTime = sdf.format(Date())
        Log.d(
          TAG,
          "get_current_date_and_time via handleAction. Current date and time: $currentDateAndTime",
        )
        currentDateAndTime
      }
      IntentAction.SCHEDULE_NOTIFICATION -> {
        scheduleNotification(context, parameters)
      }
      null -> "failed"
    }
  }

  suspend fun readCalendarEvents(
    context: Context,
    parameters: String,
    requestPermission: suspend (String) -> Boolean,
  ): String {
    if (
      checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      val granted = requestPermission(Manifest.permission.READ_CALENDAR)
      if (!granted) {
        Log.e(TAG, "READ_CALENDAR permission denied by user")
        return "failed: READ_CALENDAR permission denied by user"
      }
    }

    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ReadCalendarEventsParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateObj = format.parse(params.date)
        if (dateObj != null) {
          val cal =
            Calendar.getInstance().apply {
              timeInMillis = dateObj.time
              set(Calendar.HOUR_OF_DAY, 0)
              set(Calendar.MINUTE, 0)
              set(Calendar.SECOND, 0)
              set(Calendar.MILLISECOND, 0)
            }
          val startOfDayMillis = cal.timeInMillis

          cal.apply {
            add(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MILLISECOND, -1)
          }
          val endOfDayMillis = cal.timeInMillis

          val projection =
            arrayOf(Instances.TITLE, Instances.DESCRIPTION, Instances.BEGIN, Instances.END)

          val builder = Instances.CONTENT_URI.buildUpon()
          ContentUris.appendId(builder, startOfDayMillis)
          ContentUris.appendId(builder, endOfDayMillis)

          val cursor =
            context.contentResolver.query(
              builder.build(),
              projection,
              null,
              null,
              "${Instances.BEGIN} ASC",
            )

          val eventsList = mutableListOf<CalendarEventDto>()
          cursor?.use { c ->
            val titleIdx = c.getColumnIndex(Instances.TITLE)
            val descIdx = c.getColumnIndex(Instances.DESCRIPTION)
            val startIdx = c.getColumnIndex(Instances.BEGIN)
            val endIdx = c.getColumnIndex(Instances.END)
            val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            while (c.moveToNext()) {
              val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "" else ""
              val desc = if (descIdx >= 0) c.getString(descIdx) ?: "" else ""
              val start = if (startIdx >= 0) c.getLong(startIdx) else 0L
              val end = if (endIdx >= 0) c.getLong(endIdx) else 0L
              eventsList.add(
                CalendarEventDto(
                  title = title,
                  description = desc,
                  begin_time = if (start > 0) timeFormat.format(Date(start)) else "",
                  end_time = if (end > 0) timeFormat.format(Date(end)) else "",
                )
              )
            }
          }
          val responseAdapter = moshi.adapter(ReadCalendarEventsResponse::class.java)
          return responseAdapter.toJson(ReadCalendarEventsResponse(eventsList))
        } else {
          Log.e(TAG, "Failed to parse read_calendar_events date: ${params.date}")
          return "failed"
        }
      } else {
        Log.e(TAG, "Failed to parse read_calendar_events parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to read calendar events: $parameters", e)
      return "failed: ${e.message}"
    }
  }

  fun scheduleNotification(context: Context, parameters: String): String {
    try {
      val moshi = Moshi.Builder().build()
      val jsonAdapter = moshi.adapter(ScheduleNotificationParams::class.java)
      val params = jsonAdapter.fromJson(parameters)
      if (params != null) {
        val notificationProtoBuilder =
          ScheduledNotification.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setTitle(params.title)
            .setMessage(params.message)
            .setHour(params.hour)
            .setMinute(params.minute)
            .setChannelId("agent_skill_tasks_channel")
            .setChannelName("Agent Skill Task")
        if (params.deeplink != null) {
          notificationProtoBuilder.setDeeplink(params.deeplink)
        } else if (params.task_id != null && params.model_name != null) {
          val uri =
            "com.google.ai.edge.gallery://model/${params.task_id}/${params.model_name}"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else if (params.task_id != null) {
          val uri =
            "com.google.ai.edge.gallery://${params.task_id}/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting constructed deeplink to: $uri")
          notificationProtoBuilder.setDeeplink(uri)
        } else {
          val fallbackUri =
            "com.google.ai.edge.gallery://llm_agent_chat/"
              .toUri()
              .buildUpon()
              .appendQueryParameter("query", params.message)
              .build()
              .toString()
          Log.d(TAG, "Setting fallback deeplink to: $fallbackUri")
          notificationProtoBuilder.setDeeplink(fallbackUri)
        }
        if (params.year != null) {
          notificationProtoBuilder.setYear(params.year)
        }
        if (params.month != null) {
          notificationProtoBuilder.setMonth(params.month)
        }
        if (params.day != null) {
          notificationProtoBuilder.setDay(params.day)
        }
        if (params.repeat_daily != null) {
          notificationProtoBuilder.setRepeatDaily(params.repeat_daily)
        }

        val entryPoint =
          EntryPointAccessors.fromApplication(
            context.applicationContext,
            NotificationScheduleManagerEntryPoint::class.java,
          )
        val success =
          entryPoint
            .notificationScheduleManager()
            .scheduleNotification(notificationProtoBuilder.build())
        if (!success) {
          return "failed"
        }
        return "succeeded"
      } else {
        Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters")
        return "failed"
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to parse schedule_notification parameters: $parameters", e)
      return "failed"
    }
  }
}
