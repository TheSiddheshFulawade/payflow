package com.siddheshfulawade.payflow.recon.app;

import java.util.ArrayList;
import java.util.List;

/** Splits a list into chunks of at most {@link #SIZE}: one database transaction per chunk (REQ-PF-040). */
public final class Chunks {

    public static final int SIZE = 1_000;

    private Chunks() {}

    /** In order, none lost, no empty chunk. */
    public static <T> List<List<T>> partition(List<T> items) {
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += SIZE) {
            chunks.add(List.copyOf(items.subList(from, Math.min(from + SIZE, items.size()))));
        }
        return chunks;
    }
}
