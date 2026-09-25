package app.aino.mobile.core.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Navigation asks from outside the NavHost (ring answers, chat meeting cards,
 * the meeting-started toast). A channel, not a SharedFlow: a request made
 * while the shell is recomposing is delivered once, when it collects again.
 */
object RouteRequests {
    private val channel = Channel<String>(Channel.BUFFERED)
    val routes: Flow<String> = channel.receiveAsFlow()

    fun open(route: String) {
        channel.trySend(route)
    }
}
