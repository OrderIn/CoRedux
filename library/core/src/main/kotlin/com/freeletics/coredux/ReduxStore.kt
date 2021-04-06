package com.freeletics.coredux

import com.freeletics.coredux.Action
import com.freeletics.coredux.INITIAL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * A [createStore] is a Kotlin coroutine based implementation of Redux and redux.js.org.
 *
 * @param name preferably unique name associated with this [Store] instance.
 * It will be used as a `name` param for [LogEntry] to distinguish log events from different store
 * instances.
 * @param initialState The initial state. This one will be emitted directly in onSubscribe()
 * @param sideEffects The sideEffects. See [SideEffect].
 * @param launchMode store launch mode. Default is [CoroutineStart.LAZY] - when first [StateReceiver]
 * will added to [Store], it will start processing input actions.
 * @param logSinks list of [LogSink] implementations, that will receive log events.
 * To disable logging, use [emptyList] (default).
 * @param reducer The reducer.  See [Reducer].
 * @param S The type of the State
 *
 * @return instance of [Store] object
 */
fun <S : Any> CoroutineScope.createStore(
    name: String,
    initialState: S,
    sideEffects: List<SideEffect<S>> = emptyList(),
    launchMode: CoroutineStart = CoroutineStart.LAZY,
    logSinks: List<LogSink> = emptyList(),
    reducer: Reducer<S>
): Store<S> = createStoreInternal(
    name,
    initialState,
    sideEffects,
    launchMode,
    logSinks,
    Dispatchers.Default,
    reducer
)

/**
 * Allows to override any store configuration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <S : Any> CoroutineScope.createStoreInternal(
    name: String,
    initialState: S,
    sideEffects: List<SideEffect<S>> = emptyList(),
    launchMode: CoroutineStart = CoroutineStart.LAZY,
    logSinks: List<LogSink> = emptyList(),
    logsDispatcher: CoroutineDispatcher = Dispatchers.Default,
    reducer: Reducer<S>
): Store<S> {
    val logger = Logger(name, this, logSinks.map { it.sink }, logsDispatcher)
    val actionsReducerChannel = Channel<Action>(Channel.UNLIMITED)
    val actionsSideEffectsChannel = BroadcastChannel<Action>(sideEffects.size + 1)

    coroutineContext[Job]?.invokeOnCompletion {
        logger.logAfterCancel { LogEvent.StoreFinished }
        actionsReducerChannel.close(it)
        actionsSideEffectsChannel.close(it)
    }

    logger.logEvent { LogEvent.StoreCreated }
    return CoreduxStore(actionsReducerChannel, initialState) { stateDispatcher ->
        // Creating reducer coroutine
        launch(
            start = launchMode,
            context = CoroutineName("$name reducer")
        ) {
            logger.logEvent { LogEvent.ReducerEvent.Start }
            var currentState = initialState

            // Sending initial state
            logger.logEvent { LogEvent.ReducerEvent.DispatchState(currentState) }
            stateDispatcher(INITIAL, currentState)

            // Starting side-effects coroutines
            sideEffects.forEach { sideEffect ->
                logger.logEvent { LogEvent.SideEffectEvent.Start(sideEffect.name) }
                with(sideEffect) {
                    start(
                        actionsSideEffectsChannel.openSubscription(),
                        { currentState },
                        actionsReducerChannel,
                        logger
                    )
                }
            }

            try {
                for (action in actionsReducerChannel) {
                    logger.logEvent { LogEvent.ReducerEvent.InputAction(action, currentState) }
                    currentState = try {
                        reducer(currentState, action)
                    } catch (e: Throwable) {
                        logger.logEvent { LogEvent.ReducerEvent.Exception(e) }
                        throw ReducerException(currentState, action, e)
                    }
                    logger.logEvent { LogEvent.ReducerEvent.DispatchState(currentState) }
                    stateDispatcher(action, currentState)

                    logger.logEvent { LogEvent.ReducerEvent.DispatchToSideEffects(action) }
                    actionsSideEffectsChannel.send(action)
                }
            } finally {
                if (isActive) cancel()
            }
        }
    }
}

/**
 * Provides methods to interact with [createStore] instance.
 */
interface Store<S : Any> {
    /**
     * Dispatches new actions to given [createStore] instance.
     *
     * It is safe to call this method from different threads,
     * action will consumed on [createStore] [CoroutineScope] context.
     *
     * If `launchMode` for [createStore] is [CoroutineStart.LAZY] dispatched actions will be collected and passed
     * to reducer on first [observe] call.
     */
    fun dispatch(action: Action)

    fun observe(): StateFlow<S>

    fun <T : Action> on(actionClass: KClass<T>): Flow<T>
}

private class CoreduxStore<S : Any>(
    private val actionsDispatchChannel: SendChannel<Action>,
    private val initialState: S,
    reducerCoroutineBuilder: ((action: Action, newState: (S)) -> Unit) -> Job
) : Store<S> {
    private val stateFlow = MutableStateFlow(initialState)
    private val actionFlow = MutableStateFlow<Any>(INITIAL)

    private val lock = ReentrantLock(true)

    private val reducerCoroutine = reducerCoroutineBuilder { action, newState ->
        actionFlow.value = action
        stateFlow.value = newState
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun dispatch(action: Action) {
        if (actionsDispatchChannel.isClosedForSend) throw IllegalStateException("CoroutineScope is cancelled")

        if (!actionsDispatchChannel.offer(action)) {
            throw IllegalStateException("Input actions overflow - buffer is full")
        }
    }

    override fun observe(): StateFlow<S> {
        if (reducerCoroutine.isCompleted) throw IllegalStateException("CoroutineScope is cancelled")

        lock.withLock {
            if (stateFlow.value == initialState && !reducerCoroutine.isActive) {
                reducerCoroutine.start()
            }
        }

        return stateFlow.asStateFlow()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Action> on(actionClass: KClass<T>): Flow<T> {
        if (reducerCoroutine.isCompleted) throw IllegalStateException("CoroutineScope is cancelled")
        return actionFlow.asStateFlow()
            .filter { (it::class == actionClass) }
            .map { it as T }
    }
}

/**
 * A simple type alias for a reducer function.
 * A Reducer takes a State and an Action as input and produces a state as output.
 *
 * **Note**: Implementations of [Reducer] must be fast and _lock-free_.
 *
 * @param S The type of the state
 */
typealias Reducer<S> = (currentState: S, newAction: Action) -> S

/**
 * Wraps [Reducer] call exception.
 */
class ReducerException(
    state: Any,
    action: Any,
    cause: Throwable
) : RuntimeException("Exception was thrown by reducer, state = '$state', action = '$action'", cause)
