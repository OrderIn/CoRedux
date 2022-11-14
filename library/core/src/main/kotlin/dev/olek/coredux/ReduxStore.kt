package dev.olek.coredux

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * @param initialState The initial state. This one will be emitted directly in onSubscribe()
 * @param sideEffects The sideEffects. See [SideEffect].
 * @param launchMode store launch mode. Default is [CoroutineStart.LAZY] - when first [StateReceiver]
 * will added to [Store], it will start processing input actions.
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
    reducer: Reducer<S>
): Store<S> = createStoreInternal(
    name,
    initialState,
    sideEffects,
    launchMode,
    reducer
)

/**
 * Allows to override any store configuration.
 */
internal fun <S : Any> CoroutineScope.createStoreInternal(
    name: String,
    initialState: S,
    sideEffects: List<SideEffect<S>> = emptyList(),
    launchMode: CoroutineStart = CoroutineStart.LAZY,
    reducer: Reducer<S>
): Store<S> {
    val actionsReducerChannel = Channel<Action>(Channel.UNLIMITED)
    val actionsSideEffectsChannel = MutableSharedFlow<Action>(
        extraBufferCapacity = sideEffects.size + 1
    )

    coroutineContext[Job]?.invokeOnCompletion {
        actionsReducerChannel.close(it)
    }

    return CoreduxStore(actionsReducerChannel, initialState) { stateDispatcher ->
        // Creating reducer coroutine
        launch(
            start = launchMode,
            context = CoroutineName("$name reducer")
        ) {
            var currentState = initialState

            // Sending initial state
            stateDispatcher(INITIAL, currentState)

            // Starting side-effects coroutines
            sideEffects.forEach { sideEffect ->
                with(sideEffect) {
                    start(
                        actionsSideEffectsChannel.asSharedFlow(),
                        { currentState },
                        actionsReducerChannel
                    )
                }
            }

            try {
                for (action in actionsReducerChannel) {
                    currentState = try {
                        reducer(currentState, action)
                    } catch (e: Throwable) {
                        throw ReducerException(currentState, action, e)
                    }
                    stateDispatcher(action, currentState)

                    actionsSideEffectsChannel.emit(action)
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
     * to reducer on first [onStateChange] call.
     */
    fun dispatch(action: Action)

    fun onStateChange(): StateFlow<S>

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

        if (!actionsDispatchChannel.trySend(action).isSuccess) {
            throw IllegalStateException("Input actions overflow - buffer is full")
        }
    }

    override fun onStateChange(): StateFlow<S> {
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
