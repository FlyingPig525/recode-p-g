package io.github.homchom.recode.event.trial

import io.github.homchom.recode.*
import io.github.homchom.recode.event.*
import io.github.homchom.recode.lifecycle.*
import io.github.homchom.recode.ui.sendSystemToast
import io.github.homchom.recode.ui.translateText
import io.github.homchom.recode.util.nullable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.*
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Creates a [DetectorModule] that runs via one or more [DetectorTrial] objects.
 *
 * This is provided as a convenience function; for more complex DetectorModules, use the more generic
 * [io.github.homchom.recode.lifecycle.module] function and [detectorDetail].
 *
 * @param name The name of what is being detected (used for debugging purposes).
 */
fun <T : Any, R : Any> detector(
    name: String,
    primaryTrial: DetectorTrial<T, R>,
    vararg secondaryTrials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): DetectorModule<T, R> {
    return module(
        detectorDetail(name, primaryTrial, *secondaryTrials, timeoutDuration = timeoutDuration)
    )
}

/**
 * Creates a [RequesterModule] that runs via one or more [RequesterTrial] objects.
 *
 * This is provided as a convenience function; for more complex RequesterModules, use the more generic
 * [io.github.homchom.recode.lifecycle.module] function and [requesterDetail].
 *
 * @param name The name of what is being detected (used for debugging purposes).
 * @param lifecycle The [Listenable] object that defines the requester's lifecycle. If the event is run during
 * a request, the request is cancelled.
 */
fun <T : Any, R : Any> requester(
    name: String,
    lifecycle: Listenable<*>,
    primaryTrial: RequesterTrial<T, R>,
    vararg secondaryTrials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): RequesterModule<T, R> {
    return module(
        requesterDetail(name, lifecycle, primaryTrial, *secondaryTrials, timeoutDuration = timeoutDuration)
    )
}

/**
 * Creates a [ModuleDetail] for a standard [Detector] implementation.
 *
 * @see detector
 */
fun <T : Any, R : Any> detectorDetail(
    name: String,
    primaryTrial: DetectorTrial<T, R>,
    vararg secondaryTrials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): ModuleDetail<ExposedModule, DetectorModule<T, R>> {
    return TrialDetector(name, listOf(primaryTrial, *secondaryTrials), timeoutDuration)
}

/**
 * Creates a [ModuleDetail] for a standard [Requester] implementation.
 *
 * @see requester
 */
fun <T : Any, R : Any> requesterDetail(
    name: String,
    lifecycle: Listenable<*>,
    primaryTrial: RequesterTrial<T, R>,
    vararg secondaryTrials: DetectorTrial<T, R>,
    timeoutDuration: Duration = DEFAULT_TIMEOUT_DURATION
): ModuleDetail<ExposedModule, RequesterModule<T, R>> {
    return TrialRequester(name, lifecycle, primaryTrial, secondaryTrials, timeoutDuration)
}

private sealed class DetectorDetail<T : Any, R : Any, M : DetectorModule<T, R>> :
    Detector<T, R>, ModuleDetail<ExposedModule, M>
{
    protected abstract val name: String
    protected abstract val trials: List<Trial<T, R>>

    private val event = createEvent<R, R> { it }

    private val entries = ConcurrentLinkedQueue<TrialEntry<T, R>>()

    override val previous get() = event.previous

    override fun getNotificationsFrom(module: RModule) = event.getNotificationsFrom(module)

    override fun detectFrom(module: RModule, input: T?) = responseFlow(input, false)

    protected fun responseFlow(input: T?, isRequest: Boolean) = flow {
        coroutineScope {
            val responses = Channel<R?>(Channel.UNLIMITED)
            try {
                // add entry after all current detection loops
                launch(RecodeDispatcher) {
                    yield()
                    entries += TrialEntry(isRequest, input, responses)
                }
                while (isActive) emit(responses.receive())
            } finally {
                responses.close()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override fun applyTo(module: ExposedModule): M {
        module.onEnable {
            for (trialIndex in trials.indices) module.launch {
                trials[trialIndex].supplyResultsFrom(module).collect { supplier ->
                    val successContext = CompletableDeferred<R>(coroutineContext.job)
                    if (entries.isEmpty()) {
                        module.considerEntry(trialIndex, null, supplier, successContext)
                    }

                    val iterator = entries.iterator()
                    for (entry in iterator) {
                        if (entry.responses.isClosedForSend) {
                            iterator.remove()
                            continue
                        }
                        module.considerEntry(trialIndex, entry, supplier, successContext)
                    }

                    successContext.invokeOnCompletion { exception ->
                        if (exception == null) {
                            val completed = successContext.getCompleted()
                            logDebug("${this@DetectorDetail} succeeded; running with context $completed")
                            event.run(completed)
                        }
                    }
                }
            }
        }

        return moduleFrom(module)
    }

    protected abstract fun moduleFrom(input: ExposedModule): M

    @OptIn(DelicateCoroutinesApi::class)
    fun CoroutineModule.considerEntry(
        trialIndex: Int,
        entry: TrialEntry<T, R>?,
        supplier: Trial.ResultSupplier<T, R>,
        successContext: CompletableDeferred<R>,
    ) {
        val entryScope = CoroutineScope(coroutineContext + Job(successContext))
        val result = nullable {
            val trialScope = TrialScope(this@considerEntry, this@nullable, entryScope)
            logDebug("trial $trialIndex started for ${debugString(entry?.input)}")
            supplier.supplyIn(trialScope, entry?.input, entry?.isRequest ?: false)
        }

        val entryJob = entryScope.launch {
            if (result == null) {
                entry?.responses?.trySend(null)
                return@launch
            }

            val awaited = result.await()
            entry?.responses?.trySend(awaited)
            awaited?.let(successContext::complete)
        }

        entryJob.invokeOnCompletion { exception ->
            val state = when (exception) {
                is CancellationException -> "cancelled"
                null -> "ended"
                else -> "ended with exception $exception"
            }
            logDebug("trial $trialIndex $state for ${debugString(entry?.input)}")
            entryScope.cancel("TrialEntry consideration completed")
        }
    }

    override fun toString() = "$name detector"

    protected fun debugString(input: T?): String {
        val requestString = if (input == null) "default entry" else "explicit entry with input $input"
        return "$this ($requestString)"
    }
}

private data class TrialEntry<T : Any, R : Any>(
    val isRequest: Boolean,
    val input: T?,
    val responses: SendChannel<R?>
)

private class TrialDetector<T : Any, R : Any>(
    override val name: String,
    override val trials: List<DetectorTrial<T, R>>,
    override val timeoutDuration: Duration
) : DetectorDetail<T, R, DetectorModule<T, R>>() {
    override fun moduleFrom(input: ExposedModule): DetectorModule<T, R> =
        SimpleDetectorModule(this, input)
}

private class TrialRequester<T : Any, R : Any>(
    override val name: String,
    private val lifecycle: Listenable<*>,
    primaryTrial: RequesterTrial<T, R>,
    secondaryTrials: Array<out DetectorTrial<T, R>>,
    override val timeoutDuration: Duration
) : DetectorDetail<T, R, RequesterModule<T, R>>(), Requester<T, R> {
    override val trials = listOf(primaryTrial, *secondaryTrials)
    private val start = primaryTrial.start

    override val activeRequests get() = _activeRequests.get()

    private val _activeRequests = AtomicInteger(0)

    override suspend fun requestFrom(module: RModule, input: T) = withContext(NonCancellable) {
        lifecycle.getNotificationsFrom(module)
            .onEach { cancel("Requester lifecycle ended during a request") }
            .launchIn(this)

        val detectChannel = responseFlow(input, true)
            .filterNotNull()
            .produceIn(this)

        _activeRequests.incrementAndGet()
        try {
            delay(25.milliseconds) // https://github.com/PaperMC/Velocity/issues/909 TODO: remove
            val response = start(input) ?: withTimeout(timeoutDuration) { detectChannel.receive() }
            coroutineContext.cancelChildren()
            response
        } catch (timeout: TimeoutCancellationException) {
            mc.sendSystemToast(
                translateText("multiplayer.recode.request_timeout.toast.title"),
                translateText("multiplayer.recode.request_timeout.toast")
            )
            logError("${debugString(input)} timed out after $timeoutDuration")
            throw timeout
        } finally {
            _activeRequests.decrementAndGet()
        }
    }

    override fun applyTo(module: ExposedModule): RequesterModule<T, R> {
        module.onEnable { _activeRequests.set(0) }
        return super.applyTo(module)
    }

    override fun moduleFrom(input: ExposedModule) = SimpleRequesterModule(this, input)

    override fun toString() = "$name requester"
}

private open class SimpleDetectorModule<T : Any, R : Any>(
    private val detail: DetectorDetail<T, R, *>,
    module: RModule
) : DetectorModule<T, R>, RModule by module {
    override val timeoutDuration get() = detail.timeoutDuration
    override val previous get() = detail.previous

    override fun getNotificationsFrom(module: RModule): Flow<R> {
        module.depend(this)
        return detail.getNotificationsFrom(module)
    }

    override fun detectFrom(module: RModule, input: T?): Flow<R?> {
        module.depend(this)
        return detail.detectFrom(module, input)
    }
}

private class SimpleRequesterModule<T : Any, R : Any>(
    private val detail: TrialRequester<T, R>,
    module: RModule
) : SimpleDetectorModule<T, R>(detail, module), RequesterModule<T, R> {
    override val activeRequests get() = detail.activeRequests

    override suspend fun requestFrom(module: RModule, input: T): R {
        module.depend(this)
        return detail.requestFrom(module, input)
    }
}