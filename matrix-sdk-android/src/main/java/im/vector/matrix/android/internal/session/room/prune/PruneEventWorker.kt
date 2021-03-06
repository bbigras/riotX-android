/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.room.prune

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.squareup.moshi.JsonClass
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.internal.database.mapper.ContentMapper
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.MatrixKoinComponent
import im.vector.matrix.android.internal.util.WorkerParamsFactory
import im.vector.matrix.android.internal.util.tryTransactionSync
import io.realm.Realm
import org.koin.standalone.inject

internal class PruneEventWorker(context: Context,
                                workerParameters: WorkerParameters
) : Worker(context, workerParameters), MatrixKoinComponent {

    @JsonClass(generateAdapter = true)
    internal data class Params(
            val redactionEvents: List<Event>
    )

    private val monarchy by inject<Monarchy>()

    override fun doWork(): Result {
        val params = WorkerParamsFactory.fromData<Params>(inputData)
                     ?: return Result.failure()

        val result = monarchy.tryTransactionSync { realm ->
            params.redactionEvents.forEach { event ->
                pruneEvent(realm, event)
            }
        }
        return result.fold({ Result.retry() }, { Result.success() })
    }

    private fun pruneEvent(realm: Realm, redactionEvent: Event?) {
        if (redactionEvent == null || redactionEvent.redacts.isNullOrEmpty()) {
            return
        }
        val eventToPrune = EventEntity.where(realm, eventId = redactionEvent.redacts).findFirst()
                           ?: return

        val allowedKeys = computeAllowedKeys(eventToPrune.type)
        if (allowedKeys.isNotEmpty()) {
            val prunedContent = ContentMapper.map(eventToPrune.content)?.filterKeys { key -> allowedKeys.contains(key) }
            eventToPrune.content = ContentMapper.map(prunedContent)
        }
    }

    private fun computeAllowedKeys(type: String): List<String> {
        // Add filtered content, allowed keys in content depends on the event type
        return when (type) {
            EventType.STATE_ROOM_MEMBER       -> listOf("membership")
            EventType.STATE_ROOM_CREATE       -> listOf("creator")
            EventType.STATE_ROOM_JOIN_RULES   -> listOf("join_rule")
            EventType.STATE_ROOM_POWER_LEVELS -> listOf("users",
                                                        "users_default",
                                                        "events",
                                                        "events_default",
                                                        "state_default",
                                                        "ban",
                                                        "kick",
                                                        "redact",
                                                        "invite")
            EventType.STATE_ROOM_ALIASES      -> listOf("aliases")
            EventType.STATE_CANONICAL_ALIAS   -> listOf("alias")
            EventType.FEEDBACK                -> listOf("type", "target_event_id")
            else                                                                                -> emptyList()
        }
    }

}