package build.wallet.analytics.events

import build.wallet.queueprocessor.PeriodicProcessor
import build.wallet.queueprocessor.PeriodicProcessorImpl
import kotlin.time.Duration.Companion.minutes

class AnalyticsEventPeriodicProcessorImpl(
  queue: AnalyticsEventQueue,
  processor: AnalyticsEventProcessor,
) : AnalyticsEventPeriodicProcessor,
  PeriodicProcessor by PeriodicProcessorImpl(
    queue = queue,
    processor = processor,
    retryFrequency = 1.minutes,
    retryBatchSize = 50
  )
