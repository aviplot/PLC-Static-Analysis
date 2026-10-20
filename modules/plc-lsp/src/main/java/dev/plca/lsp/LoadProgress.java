package dev.plca.lsp;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.WorkDoneProgressReport;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Work-done progress of loading a project (LSP {@code window/workDoneProgress/create} and {@code $/progress}): reading
 * files, building the model, checking the rules. Reports are thinned to one per 100 ms and never go backwards; any
 * thread may report. Without a client that supports progress, every call does nothing.
 */
final class LoadProgress {

    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final long MIN_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(100);

    private final LanguageClient client;
    private final Either<String, Integer> token;
    private long lastReport = System.nanoTime() - MIN_INTERVAL_NS;
    private int percentage;
    private boolean ended;

    private LoadProgress(LanguageClient client, Either<String, Integer> token) {
        this.client = client;
        this.token = token;
    }

    /** Creates the progress at the client (waits briefly for its answer) and begins it; {@code client} may be null. */
    static LoadProgress begin(LanguageClient client, String title) {
        if (client == null) {
            return new LoadProgress(null, null);
        }
        Either<String, Integer> token = Either.forLeft("plca-load-" + TOKENS.incrementAndGet());
        try {
            client.createProgress(new WorkDoneProgressCreateParams(token)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new LoadProgress(null, null); // the client said no or did not answer: no progress
        }
        LoadProgress p = new LoadProgress(client, token);
        WorkDoneProgressBegin begin = new WorkDoneProgressBegin();
        begin.setTitle(title);
        begin.setCancellable(false);
        begin.setPercentage(0);
        client.notifyProgress(new ProgressParams(token, Either.forLeft(begin)));
        return p;
    }

    /** A count within a phase (files read): sent at most every 100 ms. */
    synchronized void report(String message, int percent) {
        if (System.nanoTime() - lastReport >= MIN_INTERVAL_NS) {
            send(message, percent);
        }
    }

    /** A new phase (building the model, checking the rules): always sent. */
    synchronized void phase(String message, int percent) {
        send(message, percent);
    }

    private void send(String message, int percent) {
        if (client == null || ended) {
            return;
        }
        lastReport = System.nanoTime();
        percentage = Math.max(percentage, Math.min(100, percent));
        WorkDoneProgressReport r = new WorkDoneProgressReport();
        r.setMessage(message);
        r.setPercentage(percentage);
        client.notifyProgress(new ProgressParams(token, Either.forLeft(r)));
    }

    synchronized void end(String message) {
        if (client == null || ended) {
            return;
        }
        ended = true;
        client.notifyProgress(new ProgressParams(token, Either.forLeft(endWith(message))));
    }

    private static WorkDoneProgressEnd endWith(String message) {
        WorkDoneProgressEnd e = new WorkDoneProgressEnd();
        e.setMessage(message);
        return e;
    }
}
