package eu.kanade.tachiyomi.provider.runtime;

interface IProviderRuntimeService {
    String evaluate(String source, long wallClockTimeoutMs, long jsExecutionTimeoutMs);
    int processUid();
    int processPid();
}
