package com.siddheshfulawade.payflow.recon.infra;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * A reader and a writer that learn the business date from the job parameter {@code businessDate} when their step
 * starts. Both only call the {@code ReconciliationRun} and the stores; the rules are not here.
 */
final class BusinessDateStep {

    static final String PARAMETER = "businessDate";

    private BusinessDateStep() {}

    private static LocalDate dateOf(StepExecution step) {
        return LocalDate.parse(step.getJobParameters().getString(PARAMETER));
    }

    /**
     * Loads its items on the first {@code read}, inside the running step: a failed download fails the step (and the job)
     * and is retried by a restart, instead of failing while the context starts.
     */
    static final class Reader<T> implements ItemReader<T>, StepExecutionListener {

        private final Function<LocalDate, List<T>> loader;
        private LocalDate businessDate;
        private Iterator<T> items;

        Reader(Function<LocalDate, List<T>> loader) {
            this.loader = loader;
        }

        @Override
        public void beforeStep(StepExecution step) {
            businessDate = dateOf(step);
            items = null;
        }

        @Override
        public T read() {
            if (items == null) {
                items = loader.apply(businessDate).iterator();
            }
            return items.hasNext() ? items.next() : null;
        }
    }

    /** Hands each chunk, one transaction, to the sink with the business date. */
    static final class Writer<T> implements ItemWriter<T>, StepExecutionListener {

        private final BiConsumer<LocalDate, List<T>> sink;
        private LocalDate businessDate;

        Writer(BiConsumer<LocalDate, List<T>> sink) {
            this.sink = sink;
        }

        @Override
        public void beforeStep(StepExecution step) {
            businessDate = dateOf(step);
        }

        @Override
        public void write(Chunk<? extends T> chunk) {
            sink.accept(businessDate, List.copyOf(chunk.getItems()));
        }
    }
}
